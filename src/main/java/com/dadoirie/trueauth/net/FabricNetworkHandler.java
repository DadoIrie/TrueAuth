package com.dadoirie.trueauth.net;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.mixin.server.ServerLoginAccessor;
import com.dadoirie.trueauth.server.AuthState;
import com.dadoirie.trueauth.server.MinecraftWhitelistChecker;
import com.dadoirie.trueauth.server.SessionCheck;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.networking.v1.LoginPacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.netty.buffer.Unpooled;

/**
 * Server-side Fabric API networking handler for TrueAuth.
 * This class is only loaded on the server side.
 */
public final class FabricNetworkHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Nonce storage per connection - key is the handler instance (unique per connection)
    private static final ConcurrentHashMap<ServerLoginPacketListenerImpl, String> NONCE_MAP = new ConcurrentHashMap<>();
    
    private FabricNetworkHandler() {}
    
    // ==================== INITIALIZATION ====================
    
    public static void initServer() {
        ServerLoginNetworking.registerGlobalReceiver(NetIds.AUTH, FabricNetworkHandler::handleAuthResponse);
        ServerLoginNetworking.registerGlobalReceiver(NetIds.PASSWORD, FabricNetworkHandler::handlePasswordResponse);
        ServerLoginNetworking.registerGlobalReceiver(NetIds.AUTH_RESULT, FabricNetworkHandler::handleAuthResultResponse);
        ServerLoginConnectionEvents.QUERY_START.register(FabricNetworkHandler::onQueryStart);
        LOGGER.info("[TrueAuth] Registered Fabric API server login networking");
    }
    
    // ==================== SERVER SIDE ====================
    
    private static void onQueryStart(
            ServerLoginPacketListenerImpl handler,
            MinecraftServer server,
            LoginPacketSender sender,
            ServerLoginNetworking.LoginSynchronizer synchronizer
    ) {
        // Skip auth queries on singleplayer (integrated server)
        if (!server.isDedicatedServer()) return;
        
        // Use accessor mixin to get private fields
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        // Skip if server is in online mode (vanilla handles auth) or no profile
        if (server.usesAuthentication() || profile == null) return;
        
        String nonce = UUID.randomUUID().toString().replace("-", "");
        NONCE_MAP.put(handler, nonce);
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] QUERY_START: sending auth query with nonce=" + nonce);
        
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(nonce);
        sender.sendPacket(NetIds.AUTH, buf);
    }
    
    private static void handleAuthResponse(
            MinecraftServer server,
            ServerLoginPacketListenerImpl handler,
            boolean understood,
            FriendlyByteBuf buf,
            ServerLoginNetworking.LoginSynchronizer synchronizer,
            PacketSender responseSender
    ) {
        // Get nonce but DON'T remove yet - wait until after successful verification
        String nonce = NONCE_MAP.get(handler);
        
        // Use accessor mixin to get private fields
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        // Profile should always be set at this point (QUERY_START fires after handleHello)
        // If null, something is seriously wrong - disconnect
        if (profile == null) {
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - profile is null, disconnecting");
            sendDisconnectAsync(accessor, Component.literal("Server error: no profile found. Please try again."));
            NONCE_MAP.remove(handler);
            return;
        }
        
        String playerName = profile.getName();
        
        // Get IP via accessor - make final for lambda capture
        final String ip;
        if (accessor.trueauth$getConnection().getRemoteAddress() instanceof InetSocketAddress isa) {
            ip = isa.getAddress().getHostAddress();
        } else {
            ip = null;
        }
        
        // Whitelist check
        MinecraftWhitelistChecker whitelistChecker = MinecraftWhitelistChecker.getInstance();
        if (TrueauthConfig.whitelistEnabled() && !whitelistChecker.isWhitelisted(playerName)) {
            String msg = "You are not whitelisted to join this server.";
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] player not in whitelist, player: " + playerName + ", ip: " + ip + ", message: " + msg);
            sendDisconnectAsync(accessor, Component.literal(msg));
            NONCE_MAP.remove(handler);
            return;
        }
        
        // Read client response
        boolean clientOk = buf.readBoolean();
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: received response, player=" + playerName + ", nonce=" + nonce + ", understood=" + understood + ", clientOk=" + clientOk);
        
        // If client says joinServer succeeded, verify with Mojang
        if (clientOk) {
            // Track timing for debug
            long startTime = System.currentTimeMillis();
            
            // Use LoginSynchronizer to block login until Mojang verification completes
            CompletableFuture<Void> verificationFuture = SessionCheck.hasJoinedAsync(playerName, nonce, ip)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .thenAccept(result -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: Mojang verification took " + elapsed + "ms for player=" + playerName);
                        // Handle timeout
                        if (result == null) {
                            // This shouldn't happen, but handle gracefully
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - result is null for player=" + playerName);
                            sendDisconnectAsync(accessor, Component.literal("Session verification failed. Please try again."));
                            NONCE_MAP.remove(handler);
                            return;
                        }
                        
                        if (result.isPresent()) {
                            SessionCheck.HasJoinedResult res = result.get();
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: PREMIUM CONFIRMED for player=" + res.name() + ", uuid=" + res.uuid());
                            
                            if (TrueauthRuntime.NAME_REGISTRY.isRegistered(res.name()) && !TrueauthRuntime.NAME_REGISTRY.isPremium(res.name())) {
                                String msg = "This name is already registered as an offline player.";
                                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] online player blocked - name registered as offline, player: " + res.name());
                                sendDisconnectAsync(accessor, Component.literal(msg));
                                NONCE_MAP.remove(handler);
                                return;
                            }
                            
                            GameProfile newProfile = new GameProfile(res.uuid(), res.name());
                            var propMap = newProfile.getProperties();
                            propMap.removeAll("textures");
                            for (var p : res.properties()) {
                                if (p.signature() != null) {
                                    propMap.put(p.name(), new Property(p.name(), p.value(), p.signature()));
                                } else {
                                    propMap.put(p.name(), new Property(p.name(), p.value()));
                                }
                            }
                            
                            // Record in registries
                            TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(res.name(), res.uuid(), ip);
                            TrueauthRuntime.IP_GRACE.record(res.name(), ip, res.uuid());
                            
                            // Complete the login - vanilla takes over from here
                            accessor.trueauth$startClientVerification(newProfile);
                            
                            // NOW remove the nonce - verification complete
                            NONCE_MAP.remove(handler);
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: login completed for premium player=" + res.name());
                        } else {
                            // clientOk=true but Mojang returned empty - SUSPICIOUS!
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: SUSPICIOUS - client claimed premium but Mojang returned empty for player=" + playerName);
                            sendDisconnectAsync(accessor, Component.literal("Session verification failed. Please try again."));
                            NONCE_MAP.remove(handler);
                        }
                    })
                    .exceptionally(throwable -> {
                        // Handle timeout and other errors
                        long elapsed = System.currentTimeMillis() - startTime;
                        // TimeoutException is wrapped in CompletionException
                        Throwable cause = throwable.getCause();
                        if (cause instanceof TimeoutException) {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: TIMEOUT after " + elapsed + "ms - Mojang verification timed out for player=" + playerName);
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: TIMEOUT - Mojang verification timed out for player=" + playerName);
                            sendDisconnectAsync(accessor, Component.literal("Session verification timed out. Please try again."));
                        } else {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - Mojang verification failed for player=" + playerName + ": " + throwable);
                            sendDisconnectAsync(accessor, Component.literal("Session verification failed. Please try again."));
                        }
                        NONCE_MAP.remove(handler);
                        return null;
                    });
            
            // Block the login until verification completes
            synchronizer.waitFor(verificationFuture);
        } else {
            // clientOk=false - Offline player (no Minecraft session)
            
            if (TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName) && TrueauthRuntime.NAME_REGISTRY.isPremium(playerName)) {
                String msg = "This name is bound to a registered official Profile.";
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] denying offline entry for known premium name: " + playerName);
                sendDisconnectAsync(accessor, Component.literal(msg));
                NONCE_MAP.remove(handler);
                return;
            }
            
            // Send password query as follow-up
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: OFFLINE PLAYER - sending password query for player=" + playerName);
            
            // Use LoginSynchronizer to wait for password response
            if (responseSender instanceof LoginPacketSender loginSender) {
                synchronizer.waitFor(server.submit(() -> {
                    FriendlyByteBuf passwordQuery = new FriendlyByteBuf(Unpooled.buffer());
                    loginSender.sendPacket(NetIds.PASSWORD, passwordQuery);
                }));
            }
        }
    }
    
    /**
     * Handle password response from client (follow-up query for offline players).
     */
    private static void handlePasswordResponse(
            MinecraftServer server,
            ServerLoginPacketListenerImpl handler,
            boolean understood,
            FriendlyByteBuf buf,
            ServerLoginNetworking.LoginSynchronizer synchronizer,
            PacketSender responseSender
    ) {
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        if (profile == null) {
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: PASSWORD RESPONSE - profile is null, disconnecting");
            sendDisconnectAsync(accessor, Component.literal("Server error: no profile found."));
            NONCE_MAP.remove(handler);
            return;
        }
        
        String playerName = profile.getName();
        
        // Get IP for recording
        final String ip;
        if (accessor.trueauth$getConnection().getRemoteAddress() instanceof InetSocketAddress isa) {
            ip = isa.getAddress().getHostAddress();
        } else {
            ip = null;
        }
        
        // Read password response
        boolean hasPassword = buf.readBoolean();
        String passwordHash = buf.readUtf();
        boolean hasNewPassword = buf.readBoolean();
        String newPasswordHash = buf.readUtf();
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] SERVER: PASSWORD RESPONSE - player=" + playerName + 
                    ", hasPassword=" + hasPassword + 
                    ", hasNewPassword=" + hasNewPassword);
        }
        
        // Check if player is already registered
        if (TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName)) {
            // Existing player - verify password
            String storedHash = TrueauthRuntime.NAME_REGISTRY.getPassword(playerName);
            if (storedHash != null && storedHash.equals(passwordHash)) {
                // Password matches - mark as offline fallback for SkinRefreshHandler
                AuthState.markOfflineFallback(accessor.trueauth$getConnection(), AuthState.FallbackReason.FAILURE);
                
                boolean passwordChanged = false;
                String finalPasswordHash = passwordHash;
                
                if (hasNewPassword && !newPasswordHash.isEmpty()) {
                    // Update password
                    TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(playerName, profile.getId(), ip, newPasswordHash);
                    finalPasswordHash = newPasswordHash;
                    passwordChanged = true;
                    if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: password updated for player=" + playerName);
                }
                
                // Send AUTH_RESULT to client with password hash and passwordChanged flag
                final String finalHash = finalPasswordHash;
                final boolean finalPasswordChanged = passwordChanged;
                if (responseSender instanceof LoginPacketSender loginSender) {
                    synchronizer.waitFor(server.submit(() -> {
                        FriendlyByteBuf authResult = new FriendlyByteBuf(Unpooled.buffer());
                        authResult.writeUtf(finalHash);
                        authResult.writeBoolean(finalPasswordChanged);
                        loginSender.sendPacket(NetIds.AUTH_RESULT, authResult);
                    }));
                }
                
                NONCE_MAP.remove(handler);
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: login completed for offline player=" + playerName);
            } else {
                // Password doesn't match
                sendDisconnectAsync(accessor, Component.literal("Incorrect password!"));
                NONCE_MAP.remove(handler);
            }
        } else {
            // New player - register with password
            if (hasPassword && !passwordHash.isEmpty()) {
                // Mark as offline fallback for SkinRefreshHandler
                AuthState.markOfflineFallback(accessor.trueauth$getConnection(), AuthState.FallbackReason.FAILURE);
                
                TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(playerName, profile.getId(), ip, passwordHash);
                
                // Send AUTH_RESULT to client with password hash (passwordChanged=false for new players)
                if (responseSender instanceof LoginPacketSender loginSender) {
                    synchronizer.waitFor(server.submit(() -> {
                        FriendlyByteBuf authResult = new FriendlyByteBuf(Unpooled.buffer());
                        authResult.writeUtf(passwordHash);
                        authResult.writeBoolean(false); // Not a password change, just registration
                        loginSender.sendPacket(NetIds.AUTH_RESULT, authResult);
                    }));
                }
                
                NONCE_MAP.remove(handler);
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: new offline player registered=" + playerName);
            } else {
                // No password provided - can't register
                sendDisconnectAsync(accessor, Component.literal("No password provided. Please set a password first."));
                NONCE_MAP.remove(handler);
            }
        }
    }
    
    /**
     * Handle auth result acknowledgment from client.
     * This is the final step - client acknowledges receipt of password hash.
     * The login is already complete at this point, we just need to handle the response.
     */
    private static void handleAuthResultResponse(
            MinecraftServer server,
            ServerLoginPacketListenerImpl handler,
            boolean understood,
            FriendlyByteBuf buf,
            ServerLoginNetworking.LoginSynchronizer synchronizer,
            PacketSender responseSender
    ) {
        // Client acknowledged receipt of AUTH_RESULT - nothing more to do
        // The login state is already complete, this handler just prevents the
        // "Unexpected custom data from client" error
        if (TrueauthConfig.debug()) {
            ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
            GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
            String playerName = profile != null ? profile.getName() : "unknown";
            System.out.println("[TrueAuth] SERVER: AUTH_RESULT acknowledged by client for player=" + playerName);
        }
    }
    
    private static void sendDisconnectAsync(ServerLoginAccessor accessor, Component reason) {
        new Thread(() -> {
            try {
                accessor.trueauth$getConnection().send(new ClientboundLoginDisconnectPacket(reason));
            } catch (Throwable e) {}
            try {
                accessor.trueauth$getConnection().disconnect(reason);
            } catch (Throwable e) {}
        }, "TrueAuth-AsyncDisconnect").start();
    }
}
