package com.dadoirie.trueauth.net;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.mixin.server.ServerLoginAccessor;
import com.dadoirie.trueauth.server.AuthProcessor;
import com.dadoirie.trueauth.server.SessionCheck;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.networking.v1.LoginPacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;

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
    // Pending password hash storage - stored until client confirms
    private static final ConcurrentHashMap<ServerLoginPacketListenerImpl, String> PENDING_PASSWORD_MAP = new ConcurrentHashMap<>();
    
    private FabricNetworkHandler() {}
    
    // ==================== INITIALIZATION ====================
    
    public static void initServer() {
        ServerLoginNetworking.registerGlobalReceiver(NetIds.AUTH, FabricNetworkHandler::handleAuthResponse);
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
        if (!server.isDedicatedServer() || server.usesAuthentication()) return;
        
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        if (profile == null) return;
        
        if (TrueauthConfig.nomojangEnabled()) {
            String name = profile.getName();
            String ip = AuthProcessor.getIpAddress(accessor.trueauth$getConnection());
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] FFAPI nomojang mode: skipping Mojang session auth, player: " + name + ", ip: " + ip);
            }
            
            if (AuthProcessor.checkNomojangGrace(name, ip) || (TrueauthRuntime.NAME_REGISTRY.isRegistered(name) && TrueauthRuntime.NAME_REGISTRY.isPremium(name))) {
                GameProfile premiumProfile = AuthProcessor.restoreUuid(name);
                
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] FFAPI nomojang grace: restored profile for " + name);
                    System.out.println("[TrueAuth] FFAPI nomojang grace: uuid=" + premiumProfile.getId());
                    System.out.println("[TrueAuth] FFAPI nomojang grace: properties=" + premiumProfile.getProperties().asMap());
                }
                
                // Set the profile and let the normal auth query flow continue
                // The client will respond with ackOk=false (nomojang mode) and we handle it in handleAuthResponse
                accessor.trueauth$setAuthenticatedProfile(premiumProfile);
                // Don't return - let the normal flow continue and send the auth query
            }
        }
        
        String nonce = UUID.randomUUID().toString().replace("-", "");
        NONCE_MAP.put(handler, nonce);
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] QUERY_START: sending auth query with nonce=" + nonce + ", nomojang=" + TrueauthConfig.nomojangEnabled());
        
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(nonce);
        buf.writeBoolean(TrueauthConfig.nomojangEnabled());
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
        String nonce = NONCE_MAP.get(handler);
        
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        if (profile == null) {
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - profile is null, disconnecting");
            AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Server error: no profile found. Please try again."));
            NONCE_MAP.remove(handler);
            return;
        }
        
        String playerName = profile.getName();
        
        final String ip = AuthProcessor.getIpAddress(accessor.trueauth$getConnection());
        
        if (!AuthProcessor.whitelistCheck(accessor.trueauth$getConnection(), playerName)) {
            NONCE_MAP.remove(handler);
            return;
        }
        
        boolean clientOk = buf.readBoolean();
        String passwordHash = buf.readUtf();
        String newPasswordHash = buf.readUtf();
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: received response, player=" + playerName + ", nonce=" + nonce + ", understood=" + understood + ", clientOk=" + clientOk);
        
        if (clientOk) {
            CompletableFuture<Void> verificationFuture = SessionCheck.hasJoinedAsync(playerName, nonce, ip)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .thenAccept(result -> {
                        if (result == null) {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - result is null for player=" + playerName);
                            AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Session verification failed. Please try again."));
                            NONCE_MAP.remove(handler);
                            return;
                        }
                        
                        if (result.isPresent()) {
                            SessionCheck.HasJoinedResult res = result.get();
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: PREMIUM CONFIRMED for player=" + res.name() + ", uuid=" + res.uuid());
                            
                            if (!AuthProcessor.nameCollisionCheck(accessor.trueauth$getConnection(), res.name(), true)) {
                                NONCE_MAP.remove(handler);
                                return;
                            }
                            
                            GameProfile newProfile = AuthProcessor.buildProfileFromSession(res);
                            
                            TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(res.name(), res.uuid(), ip, passwordHash);
                            TrueauthRuntime.IP_GRACE.record(res.name(), ip, res.uuid());
                            
                            accessor.trueauth$startClientVerification(newProfile);
                            
                            NONCE_MAP.remove(handler);
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: login completed for premium player=" + res.name());
                        } else {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: SUSPICIOUS - client claimed premium but Mojang returned empty for player=" + playerName);
                            AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Session verification failed. Please try again."));
                            NONCE_MAP.remove(handler);
                        }
                    })
                    .exceptionally(throwable -> {
                        Throwable cause = throwable.getCause();
                        if (cause instanceof TimeoutException) {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: TIMEOUT - Mojang verification timed out for player=" + playerName);
                            AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Session verification timed out. Please try again."));
                        } else {
                            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: ERROR - Mojang verification failed for player=" + playerName + ": " + throwable);
                            AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Session verification failed. Please try again."));
                        }
                        NONCE_MAP.remove(handler);
                        return null;
                    });
            
            synchronizer.waitFor(verificationFuture);
        } else {
            /* if (!AuthProcessor.nameCollisionCheck(accessor.trueauth$getConnection(), playerName, false)) {
                NONCE_MAP.remove(handler);
                return;
            } */
            
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: OFFLINE PLAYER - verifying password for player=" + playerName);
            
            AuthProcessor.PasswordVerifyResult result = AuthProcessor.verifyPassword(playerName, passwordHash, newPasswordHash);
            
            switch (result.outcome()) {
                case MATCH -> {
                    if (result.passwordChanged() && TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] SERVER: password change requested for player=" + playerName);
                    }
                    PENDING_PASSWORD_MAP.put(handler, result.passwordToStore());
                    if (responseSender instanceof LoginPacketSender loginSender) {
                        synchronizer.waitFor(server.submit(() -> {
                            FriendlyByteBuf authResult = new FriendlyByteBuf(Unpooled.buffer());
                            authResult.writeUtf(result.passwordToStore());
                            authResult.writeBoolean(result.passwordChanged());
                            loginSender.sendPacket(NetIds.AUTH_RESULT, authResult);
                        }));
                    }
                    NONCE_MAP.remove(handler);
                }
                case MISMATCH -> {
                    AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Incorrect password!"));
                    NONCE_MAP.remove(handler);
                }
                case NEW_PLAYER -> {
                    PENDING_PASSWORD_MAP.put(handler, result.passwordToStore());
                    if (TrueauthConfig.debug()) System.out.println("[TrueAuth] SERVER: new player pending registration: " + playerName);
                    if (responseSender instanceof LoginPacketSender loginSender) {
                        synchronizer.waitFor(server.submit(() -> {
                            FriendlyByteBuf authResult = new FriendlyByteBuf(Unpooled.buffer());
                            authResult.writeUtf(result.passwordToStore());
                            authResult.writeBoolean(false);
                            loginSender.sendPacket(NetIds.AUTH_RESULT, authResult);
                        }));
                    }
                    NONCE_MAP.remove(handler);
                }
                case NO_PASSWORD -> {
                    AuthProcessor.sendDisconnectAsync(accessor.trueauth$getConnection(), Component.literal("Password retrieval failed. Please restart your game and try again."));
                    NONCE_MAP.remove(handler);
                }
            }
        }
    }
    
    /**
     * Handle auth result acknowledgment from client.
     * Client sends back the password hash for verification.
     * If it matches, save to NameRegistry.
     */
    private static void handleAuthResultResponse(
            MinecraftServer server,
            ServerLoginPacketListenerImpl handler,
            boolean understood,
            FriendlyByteBuf buf,
            ServerLoginNetworking.LoginSynchronizer synchronizer,
            PacketSender responseSender
    ) {
        ServerLoginAccessor accessor = (ServerLoginAccessor) handler;
        GameProfile profile = accessor.trueauth$getAuthenticatedProfile();
        
        if (PENDING_PASSWORD_MAP.get(handler) != null) {
            AuthProcessor.passwordConfirmationCheck(accessor.trueauth$getConnection(), profile, PENDING_PASSWORD_MAP.get(handler), buf.readUtf());
        }
        
        PENDING_PASSWORD_MAP.remove(handler);
    }
}
