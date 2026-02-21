package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;

import java.util.UUID;
import java.net.InetSocketAddress;

/**
 * Centralized authentication processing logic.
 * Used by both NeoForge mixin-based and FFAPI-based networking handlers.
 */
public final class AuthProcessor {
    
    private AuthProcessor() {}
    
    /**
     * Extracts the IP address from a connection's remote address.
     *
     * @param connection The network connection
     * @return The IP address string, or null if not available
     */
    public static String getIpAddress(Connection connection) {
        if (connection.getRemoteAddress() instanceof InetSocketAddress isa) {
            return isa.getAddress().getHostAddress();
        }
        return null;
    }
    
    /**
     * Sends a disconnect packet and closes the connection asynchronously.
     *
     * @param connection The network connection to disconnect
     * @param reason The disconnect reason to send to the client
     */
    public static void sendDisconnectAsync(Connection connection, Component reason) {
        new Thread(() -> {
            try {
                connection.send(new ClientboundLoginDisconnectPacket(reason));
            } catch (Throwable ignored) {}
            try {
                connection.disconnect(reason);
            } catch (Throwable ignored) {}
        }, "TrueAuth-AsyncDisconnect").start();
    }
    
    /**
     * Checks if the player passes whitelist requirements.
     * Returns true if the player can proceed (whitelist disabled or player is whitelisted).
     * Returns false and disconnects the player if whitelist is enabled and player is not whitelisted.
     *
     * @param connection The network connection for disconnect if needed
     * @param playerName The player name to check
     * @return true if player passes whitelist check, false if rejected
     */
    public static boolean whitelistCheck(Connection connection, String playerName) {
        if (!TrueauthConfig.whitelistEnabled()) {
            return true;
        }
        
        WhitelistChecker whitelistChecker = WhitelistChecker.getInstance();
        if (whitelistChecker.isWhitelisted(playerName)) {
            return true;
        }
        
        String msg = "You are not whitelisted to join this server.";
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] player " + playerName + " not in whitelist");
        }
        sendDisconnectAsync(connection, Component.literal(msg));
        return false;
    }
    
    /**
     * Checks for name collision between premium and offline players.
     * - If isPremium=true: rejects if an offline player with this name is registered
     * - If isPremium=false: rejects if a premium player with this name is registered
     *
     * @param connection The network connection for disconnect if needed
     * @param playerName The player name to check
     * @param isPremium Whether the connecting player is premium (verified via Mojang)
     * @return true if no collision (player can proceed), false if collision detected (disconnect sent)
     */
    public static boolean nameCollisionCheck(Connection connection, String playerName, boolean isPremium) {
        if (!TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName)) {
            return true;
        }
        
        boolean registeredAsPremium = TrueauthRuntime.NAME_REGISTRY.isPremium(playerName);
        
        if (isPremium && !registeredAsPremium) {
            String msg = "This name is already registered as an offline player.";
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] online player blocked - name registered as offline, player: " + playerName);
            }
            sendDisconnectAsync(connection, Component.literal(msg));
            return false;
        }
        
        if (!isPremium && registeredAsPremium) {
            String msg = "This name is already registered as an premium player.";
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] denying offline entry for known premium name: " + playerName);
            }
            sendDisconnectAsync(connection, Component.literal(msg));
            return false;
        }
        
        return true;
    }
    
    /**
     * Builds a GameProfile from a Mojang session verification result.
     * Copies the UUID, name, and texture properties from the session response.
     * Also caches the property map for later use in nomojang grace scenarios.
     *
     * @param result The session check result from Mojang
     * @return A new GameProfile with the verified player data
     */
    public static GameProfile buildProfileFromSession(SessionCheck.HasJoinedResult result) {
        GameProfile profile = new GameProfile(result.uuid(), result.name());
        var propMap = profile.getProperties();
        propMap.removeAll("textures");
        for (var p : result.properties()) {
            if (p.signature() != null) {
                propMap.put(p.name(), new Property(p.name(), p.value(), p.signature()));
            } else {
                propMap.put(p.name(), new Property(p.name(), p.value()));
            }
        }
        TrueauthRuntime.SKIN_CACHE.setPropMap(result.uuid(), propMap);
        return profile;
    }
    
    /**
     * Handles nomojang IP grace check.
     * If recent IP grace finds a premium record, treats player as premium.
     * Records in registries and returns a GameProfile with cached skin properties.
     *
     * @param playerName The player name
     * @param ip The player IP address
     * @return A GameProfile with premium UUID and cached skin if found, null otherwise
     */
    public static GameProfile handleNomojangGrace(String playerName, String ip) {
        if (TrueauthConfig.recentIpGraceEnabled() && ip != null) {
            var pOpt = TrueauthRuntime.IP_GRACE.tryGrace(playerName, ip, TrueauthConfig.recentIpGraceTtlSeconds());
            if (pOpt.isPresent()) {
                UUID premium = pOpt.get();
                if (premium != null) {
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] nomojang: found same IP premium record, treating as premium, uuid=" + premium);
                    }
                    TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(playerName, premium, ip, "");
                    TrueauthRuntime.IP_GRACE.record(playerName, ip, premium);
                    
                    GameProfile profile = new GameProfile(premium, playerName);
                    var propMap = profile.getProperties();
                    propMap.removeAll("textures");
                    var cachedProps = TrueauthRuntime.SKIN_CACHE.getPropMap(premium);
                    if (cachedProps != null) {
                        propMap.putAll(cachedProps);
                    }
                    return profile;
                }
            }
        }
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] nomojang: no same IP premium record found, allowing offline entry");
        }
        
        return null;
    }
    
    /**
     * Handles password confirmation from client after AUTH_RESULT.
     * This is the final auth decision for offline players.
     * - If password hash matches: registers/updates player and marks offline fallback
     * - If password hash mismatches: disconnects the player
     *
     * @param connection The network connection for disconnect if needed
     * @param profile The player profile (contains name and UUID for new players)
     * @param pendingPasswordHash The password hash stored on server (waiting for confirmation)
     * @param clientPasswordHash The password hash sent by client for verification
     */
    public static void passwordConfirmationCheck(
            Connection connection,
            GameProfile profile,
            String pendingPasswordHash,
            String clientPasswordHash
    ) {
        if (pendingPasswordHash.equals(clientPasswordHash)) {
            String ip = getIpAddress(connection);
            String playerName = profile.getName();
            if (!TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName)) {
                TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(playerName, profile.getId(), ip, pendingPasswordHash);
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] new player registered after client confirmation: " + playerName);
                }
            } else {
                String storedHash = TrueauthRuntime.NAME_REGISTRY.getPassword(playerName);
                if (!pendingPasswordHash.equals(storedHash)) {
                    UUID uuid = TrueauthRuntime.NAME_REGISTRY.getUuid(playerName);
                    TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(playerName, uuid, ip, pendingPasswordHash);
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] password updated for player: " + playerName);
                    }
                }
            }
            
            AuthState.markOfflineFallback(connection, AuthState.FallbackReason.FAILURE);
        } else {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] password hash mismatch from client, player: " + profile.getName());
            }
            sendDisconnectAsync(connection, Component.literal("Password storage verification failed. Please try again."));
        }
    }
}
