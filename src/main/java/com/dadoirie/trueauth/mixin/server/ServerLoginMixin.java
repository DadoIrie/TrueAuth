// java
package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.AuthQueryTracker;
import com.dadoirie.trueauth.net.AuthResultConfirmPayload;
import com.dadoirie.trueauth.net.AuthResultPayload;
import com.dadoirie.trueauth.server.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginMixin {
    @Shadow private GameProfile authenticatedProfile;
    @Shadow private MinecraftServer server;
    @Shadow private Connection connection;

    @Shadow public abstract void disconnect(Component reason);

    // Handshake state
    @Unique private static final AtomicInteger TRUEUUID$NEXT_TX_ID = new AtomicInteger(1);
    @Unique private int trueauth$txId = 0;
    @Unique private String trueauth$nonce = null;
    @Unique private long trueauth$sentAt = 0L;
    @Unique private int trueauth$resultTxId = 0;
    @Unique private String trueauth$pendingPasswordHash = null;


    // Added: prevent duplicate processing of client authentication packets (handle only once per handshake)
    @Unique private volatile boolean trueauth$ackHandled = false;

    @Inject(method = "handleHello", at = @At("TAIL"))
    private void trueauth$afterHello(ServerboundHelloPacket pkt, CallbackInfo ci) {
        if (!this.server.isDedicatedServer()) return;
        if (this.server.usesAuthentication() || this.authenticatedProfile == null) return;

        // If `nomojang` is enabled, use the local strategy directly and do not send a session authentication packet to the client
        if (TrueauthConfig.nomojangEnabled()) {
            String name = this.authenticatedProfile.getName();
            String ip = AuthProcessor.getIpAddress(this.connection);
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] nomojang mode: skipping Mojang session auth, player: " + (name != null ? name : "<unknown>") + ", ip: " + ip);
            }
            
            if (AuthProcessor.checkNomojangGrace(name, ip) || (TrueauthRuntime.NAME_REGISTRY.isRegistered(name) && TrueauthRuntime.NAME_REGISTRY.isPremium(name))) {
                this.authenticatedProfile = AuthProcessor.restoreUuid(name);
                if (AuthProcessor.checkNomojangGrace(name, ip) && TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] nomojang same ip GRACE: restored profile for " + name + ", uuid=" + this.authenticatedProfile.getId());
                } else {
                    System.out.println("[TrueAuth] nomojang: restored profile for " + name + ", uuid=" + this.authenticatedProfile.getId());
                }
                // Don't return - let the auth query flow continue for password verification
                // This ensures premium players still verify with password and skin is properly applied
            }
        }

        // Clear ack handling flag (new handshake can be processed)
        this.trueauth$ackHandled = false;

        this.trueauth$nonce = UUID.randomUUID().toString().replace("-", "");
        this.trueauth$txId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
        this.trueauth$sentAt = System.currentTimeMillis();

        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] handleHello: starting handshake, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", nomojang: " + TrueauthConfig.nomojangEnabled());
            System.out.println("[TrueAuth] handshake nonce: " + this.trueauth$nonce + ", txId: " + this.trueauth$txId);
        }

        AuthQueryTracker.mark(this.trueauth$txId);
        AuthPayload auth = new AuthPayload(this.trueauth$nonce, TrueauthConfig.nomojangEnabled());
        this.connection.send(new ClientboundCustomQueryPacket(this.trueauth$txId, auth));
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void trueauth$onTick(CallbackInfo ci) {
        if (!this.server.isDedicatedServer()) return;
        if (this.trueauth$txId == 0 || this.trueauth$sentAt == 0L) return;
        ci.cancel(); // Prevent the vanilla tick from advancing the login state
        long timeoutMs = TrueauthConfig.timeoutMs();
        if (timeoutMs <= 0) return;

        long now = System.currentTimeMillis();
        if (now - this.trueauth$sentAt < timeoutMs) return;

        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] handshake timeout (in tick), txId: " + this.trueauth$txId);
        }

        if (TrueauthConfig.allowOfflineOnTimeout()) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] timeout allows offline entry");
            }
            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.TIMEOUT);
            reset();
        } else {
            String msg = TrueauthConfig.timeoutKickMessage();
            Component reason = Component.literal(msg != null ? msg : "Login timeout, account verification incomplete");
            AuthProcessor.sendDisconnectAsync(this.connection, reason);
            reset();
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void trueauth$onLoginCustom(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (!this.server.isDedicatedServer()) return;
        int txId = packet.transactionId();
        if (txId != this.trueauth$txId && txId != this.trueauth$resultTxId) return;
        
        String playerName = this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>";
        
        if (txId == this.trueauth$resultTxId && this.trueauth$resultTxId != 0) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] received AuthResult confirmation, player: " + playerName);
            }
            
            AuthResultConfirmPayload confirmPayload = (AuthResultConfirmPayload) packet.payload();
            
            if (this.trueauth$pendingPasswordHash != null) {
                AuthProcessor.passwordConfirmationCheck(this.connection, this.authenticatedProfile, this.trueauth$pendingPasswordHash, confirmPayload.passwordHash());
            }
            
            this.trueauth$resultTxId = 0;
            this.trueauth$pendingPasswordHash = null;
            reset(); ci.cancel(); return;
        }
        
        if (this.trueauth$txId == 0) return;

        String ip = AuthProcessor.getIpAddress(this.connection);
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] received client auth packet, player: " + playerName + ", ip: " + ip);
        }

        AuthAnswerPayload payload = (AuthAnswerPayload) packet.payload();
        if (payload == null) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] auth failed, player: " + playerName + ", ip: " + ip + ", reason: missing data");
            }
            handleAuthFailure(ip, "missing data");
            reset(); ci.cancel(); return;
        }

        boolean ackOk = payload.ok();
        
        if (!AuthProcessor.whitelistCheck(this.connection, playerName)) {
            reset(); ci.cancel(); return;
        }

        /* if (!AuthProcessor.nameCollisionCheck(this.connection, playerName, ackOk)) {
            reset(); ci.cancel(); return;
        } */

        String clientPasswordHash = payload.passwordHash();
        String clientNewPasswordHash = payload.newPasswordHash();
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] client auth packet ackOk: " + ackOk + ", hasPassword: " + (clientPasswordHash != null && !clientPasswordHash.isEmpty()) + ", hasNewPassword: " + (clientNewPasswordHash != null && !clientNewPasswordHash.isEmpty()));
        }
        
        // If Mojang auth succeeded, proceed with normal premium flow
        if (ackOk) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] Mojang auth succeeded, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip);
            }
            // Continue with normal Mojang auth flow below...
        } else {
            AuthProcessor.PasswordVerifyResult result = AuthProcessor.verifyPassword(playerName, clientPasswordHash, clientNewPasswordHash);
        
            switch (result.outcome()) {
                case MATCH -> {
                    if (result.passwordChanged() && TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] password change requested for player: " + playerName);
                    }
                    this.trueauth$pendingPasswordHash = result.passwordToStore();
                    this.trueauth$resultTxId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
                    AuthQueryTracker.markResult(this.trueauth$resultTxId);
                    this.connection.send(new ClientboundCustomQueryPacket(this.trueauth$resultTxId, 
                        new AuthResultPayload(result.passwordToStore(), result.passwordChanged())));
                    ci.cancel(); return;
                }
                case MISMATCH -> {
                    String msg = "Incorrect password! If this is a mistake, contact the server administrator.";
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] password auth failed, player: " + playerName + ", ip: " + ip);
                    }
                    AuthProcessor.sendDisconnectAsync(this.connection, Component.literal(msg));
                    reset(); ci.cancel(); return;
                }
                case NEW_PLAYER -> {
                    this.trueauth$pendingPasswordHash = result.passwordToStore();
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] new player pending registration: " + playerName + ", ip: " + ip);
                    }
                    this.trueauth$resultTxId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
                    AuthQueryTracker.markResult(this.trueauth$resultTxId);
                    this.connection.send(new ClientboundCustomQueryPacket(this.trueauth$resultTxId, 
                        new AuthResultPayload(result.passwordToStore(), false)));
                    ci.cancel(); return;
                }
                case NO_PASSWORD -> {
                    String msg = "Password retrieval failed. Please restart your game and try again.";
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] no password provided, player: " + playerName + ", ip: " + ip);
                    }
                    AuthProcessor.sendDisconnectAsync(this.connection, Component.literal(msg));
                    reset(); ci.cancel(); return;
                }
            }
        }

        // Idempotency protection: if already handled this handshake's ack, ignore duplicate packet
        if (this.trueauth$ackHandled) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] duplicate auth packet ignored, txId: " + this.trueauth$txId);
            }
            ci.cancel();
            return;
        }
        this.trueauth$ackHandled = true;

        // Key point: use the asynchronous API and do not block the main thread
        try {
            // Immediately cancel the original invocation (to prevent the original logic from continuing), but do not call `reset()`; keep the state until the callback completes
            ci.cancel();
            
            SessionCheck.hasJoinedAsync(this.authenticatedProfile.getName(), this.trueauth$nonce, ip)
                    .whenComplete((resOpt, throwable) -> {
                        // Always handle subsequent logic on the main thread
                        server.execute(() -> {
                            try {
                                if (throwable != null) {
                                    if (TrueauthConfig.debug()) {
                                        System.out.println("[TrueAuth] auth async callback exception: " + throwable);
                                    }
                                    handleAuthFailure(ip, "server error");
                                    return;
                                }

                                if (resOpt.isEmpty()) {
                                    if (TrueauthConfig.debug()) {
                                        System.out.println("[TrueAuth] auth failed, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip + ", reason: invalid session");
                                    }
                                    handleAuthFailure(ip, "invalid session");
                                    return;
                                }

                                var res = resOpt.get();

                                TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(res.name(), res.uuid(), ip, payload.passwordHash(), false);
                                TrueauthRuntime.IP_GRACE.record(res.name(), ip, res.uuid());

                                GameProfile newProfile = AuthProcessor.buildProfileFromSession(res);
                                try {
                                    if (TrueauthConfig.debug()) {
                                        System.out.println("[TrueAuth] HANDOFF: calling startClientVerification for " + newProfile.getName());
                                    }
                                    trueauth$startClientVerification(newProfile);
                                    if (TrueauthConfig.debug()) {
                                        System.out.println("[TrueAuth] HANDOFF: startClientVerification completed for " + newProfile.getName());
                                    }
                                } catch (Exception e) {
                                    if (TrueauthConfig.debug()) {
                                        System.out.println("[TrueAuth] call failed: " + e);
                                    }
                                    disconnect(Component.literal("Server error, please try again later"));
                                }
                            } catch (Throwable t) {
                                if (TrueauthConfig.debug()) {
                                    System.out.println("[TrueAuth] auth async processing exception: " + t);
                                }
                                handleAuthFailure(ip, "server error");
                            } finally {
                                reset();
                            }
                        });
                    });

        } catch (Throwable t) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] error starting async auth: " + t);
            }
            handleAuthFailure(ip, "server error");
            reset();
            this.trueauth$ackHandled = false;
        }
    }

    @Unique
    private void handleAuthFailure(String ip, String why) {
        String name = this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>";
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] session invalid, player: " + name + ", ip: " + ip + ", reason: " + why);
        }
        AuthDecider.Decision decision = AuthDecider.onFailure(name, ip);
        switch (decision.kind) {
            case PREMIUM_GRACE -> {
                if (TrueauthRuntime.NAME_REGISTRY.isRegistered(name)) {
                    if (TrueauthRuntime.NAME_REGISTRY.isPremium(name)) {
                        this.authenticatedProfile = new GameProfile(TrueauthRuntime.NAME_REGISTRY.getUuid(name), name);
                    }
                } else {
                    AuthState.markOfflineFallback(this.connection, TrueauthConfig.nomojangEnabled() ? AuthState.FallbackReason.NOMOJANG : AuthState.FallbackReason.FAILURE);
                }
            }
            case OFFLINE -> {
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] offline entry");
                }
                AuthState.markOfflineFallback(this.connection, TrueauthConfig.nomojangEnabled() ? AuthState.FallbackReason.NOMOJANG : AuthState.FallbackReason.FAILURE);
            }
            case DENY -> {
                String msg = decision.denyMessage != null ? decision.denyMessage
                        : "Auth failed, offline entry denied to protect your premium save data. Please try again later.";
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] auth denied, player: " + name + ", ip: " + ip + ", message: " + msg);
                }
                AuthProcessor.sendDisconnectAsync(this.connection, Component.literal(msg));
            }
        }
    }

    @Unique
    private void reset() {
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] state reset, txId: " + this.trueauth$txId);
        }
        this.trueauth$txId = 0;
        this.trueauth$nonce = null;
        this.trueauth$sentAt = 0L;
    }

    @Invoker("startClientVerification")
    protected abstract void trueauth$startClientVerification(GameProfile profile);
}
