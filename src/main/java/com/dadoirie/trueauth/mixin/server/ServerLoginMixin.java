// java
package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.AuthQueryTracker;
import com.dadoirie.trueauth.server.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.neoforged.fml.loading.LoadingModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginMixin {
    @Shadow private GameProfile authenticatedProfile;
    @Shadow private MinecraftServer server;
    @Shadow private Connection connection;

    @Shadow public abstract void disconnect(Component reason);

    // 握手状态
    @Unique private static final AtomicInteger TRUEUUID$NEXT_TX_ID = new AtomicInteger(1);
    @Unique private int trueauth$txId = 0;
    @Unique private String trueauth$nonce = null;
    @Unique private long trueauth$sentAt = 0L;


    // 新增：防止重复处理客户端认证包（同次握手只处理一次）
    @Unique private volatile boolean trueauth$ackHandled = false;

    @Inject(method = "handleHello", at = @At("TAIL"))
    private void trueauth$afterHello(ServerboundHelloPacket pkt, CallbackInfo ci) {
        if (this.server.usesAuthentication() || this.authenticatedProfile == null) return;

        // 若开启 nomojang，则直接使用本地策略，不向客户端发送会话认证包
        if (TrueauthConfig.nomojangEnabled()) {
            String name = this.authenticatedProfile.getName();
            String ip;
            if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
                ip = isa.getAddress().getHostAddress();
            } else {
                ip = null;
            }
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] nomojang mode: skipping Mojang session auth, player: " + (name != null ? name : "<unknown>") + ", ip: " + ip);
            }

            // Try same IP recent grace hit -> treat as premium
            if (TrueauthConfig.recentIpGraceEnabled() && ip != null) {
                var pOpt = TrueauthRuntime.IP_GRACE.tryGrace(name, ip, TrueauthConfig.recentIpGraceTtlSeconds());
                if (pOpt.isPresent()) {
                    UUID premium = pOpt.get();
                    if (premium != null) {
                        if (TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth] nomojang: found same IP premium record, treating as premium, uuid=" + premium);
                        }
                        GameProfile newProfile = new GameProfile(premium, name);
                        this.authenticatedProfile = newProfile;
                        // Record success (keep registry/cache consistent)
                        TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(name, premium, ip);
                        TrueauthRuntime.IP_GRACE.record(name, ip, premium);
                        return; // Done, treated as premium
                    }
                }
            }

            // Otherwise: treat as offline (don't block entry)
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] nomojang: no same IP premium record found, allowing offline entry");
            }
            // Don't send custom auth packet, keep default offline behavior
            return;
        }


        // Clear ack handling flag (new handshake can be processed)
        this.trueauth$ackHandled = false;

        this.trueauth$nonce = UUID.randomUUID().toString().replace("-", "");
        this.trueauth$txId = TRUEUUID$NEXT_TX_ID.getAndIncrement();
        this.trueauth$sentAt = System.currentTimeMillis();

        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] handleHello: starting handshake, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>"));
            System.out.println("[TrueAuth] handshake nonce: " + this.trueauth$nonce + ", txId: " + this.trueauth$txId);
        }

        AuthQueryTracker.mark(this.trueauth$txId);
        AuthPayload auth = new AuthPayload(this.trueauth$nonce);
        this.connection.send(new ClientboundCustomQueryPacket(this.trueauth$txId, auth));
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void trueauth$onTick(CallbackInfo ci) {
        if (this.trueauth$txId == 0 || this.trueauth$sentAt == 0L) return;
        
        // Check if ForgifiedFabricAPI is present and handle accordingly
        if (isForgifiedFabricAPIPresent()) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] ForgifiedFabricAPI detected - allowing tick to continue");
            }
            // Don't cancel the tick - let ForgifiedFabricAPI process but check timeout
            handleTimeoutCheck();
        } else {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] ForgifiedFabricAPI not present - blocking tick");
            }
            // Original behavior when ForgifiedFabricAPI not present
            ci.cancel();
            handleTimeoutCheck();
        }
    }
    
    @Unique
    private void handleTimeoutCheck() {
        long timeoutMs = TrueauthConfig.timeoutMs();
        if (timeoutMs <= 0) return;

        long now = System.currentTimeMillis();
        if (now - this.trueauth$sentAt < timeoutMs) return;

        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] handshake timeout, txId: " + this.trueauth$txId);
        }

        if (TrueauthConfig.allowOfflineOnTimeout()) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] timeout allows offline entry");
            }
            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.TIMEOUT);
            reset();
        } else {
            Component reason = Component.literal(TrueauthConfig.timeoutKickMessage());
            sendDisconnectWithReason(reason);
            reset();
        }
    }
    
    @Unique
    private static boolean isForgifiedFabricAPIPresent() {
        return isModLoaded("fabric_networking_api_v1");
    }
    
    @Unique
    private static boolean isModLoaded(String modId) {
        if (LoadingModList.get() != null) {
            return LoadingModList.get().getModFileById(modId) != null;
        }
        return false;
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void trueauth$onLoginCustom(ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (this.trueauth$txId == 0) return;
        if (packet.transactionId() != this.trueauth$txId) return;

        String ip;
        if (this.connection.getRemoteAddress() instanceof InetSocketAddress isa) {
            ip = isa.getAddress().getHostAddress();
        } else {
            ip = null;
        }
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] received client auth packet, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip);
        }

        AuthAnswerPayload payload = (AuthAnswerPayload) packet.payload();
        if (payload == null) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] auth failed, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip + ", reason: missing data");
            }
            handleAuthFailure(ip, "missing data");
            clearFabricApiQueryChannel(this.trueauth$txId);
            reset(); ci.cancel(); return;
        }

        // Check whitelist before processing any authentication
        String playerName = this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>";
        MinecraftWhitelistChecker whitelistChecker = MinecraftWhitelistChecker.getInstance();

        if (this.server.isDedicatedServer() && TrueauthConfig.whitelistEnabled() && !whitelistChecker.isWhitelisted(playerName)) {
            String msg = "You are not whitelisted to join this server.";
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] player not in whitelist, player: " + playerName + ", ip: " + ip + ", message: " + msg);
            }
            sendDisconnectWithReason(Component.literal(msg));
            clearFabricApiQueryChannel(this.trueauth$txId);
            reset(); ci.cancel(); return;
        }

        boolean ackOk = payload.ok();
        // Check if online player is trying to use a name already registered as offline
        if (this.server.isDedicatedServer() && ackOk) {
            if (TrueauthRuntime.NAME_REGISTRY.isRegistered(playerName) && !TrueauthRuntime.NAME_REGISTRY.isPremium(playerName)) {
                String msg = "This name is already registered as an offline player.";
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] online player blocked - name registered as offline, player: " + playerName + ", ip: " + ip);
                }
                sendDisconnectWithReason(Component.literal(msg));
                clearFabricApiQueryChannel(this.trueauth$txId);
                reset(); ci.cancel(); return;
            }
        }

        boolean hasPassword = payload.hasPassword();
        String clientPasswordHash = payload.passwordHash();
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] client auth packet ackOk: " + ackOk + ", hasPassword: " + hasPassword);
        }
        
        // If Mojang auth succeeded, proceed with normal premium flow
        if (ackOk) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] Mojang auth succeeded, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip);
            }
            // Continue with normal Mojang auth flow below...
        } else {
            // Mojang auth failed, check password authentication on dedicated server
            if (this.server.isDedicatedServer()) {
                String name = this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>";
                
                // Check if known premium name should be denied offline access (before any password logic)
                if (TrueauthConfig.knownPremiumDenyOffline() && TrueauthRuntime.NAME_REGISTRY.isRegistered(name) && TrueauthRuntime.NAME_REGISTRY.isPremium(name)) {
                    String msg = "This name is bound to a premium UUID.";
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] denying offline entry for known premium name: " + name);
                    }
                    sendDisconnectWithReason(Component.literal(msg));
                    clearFabricApiQueryChannel(this.trueauth$txId);
                    reset(); ci.cancel(); return;
                }
                
                if (hasPassword && clientPasswordHash != null) {
                    // Check if player already has a stored password
                    if (TrueauthRuntime.NAME_REGISTRY.isRegistered(name) && !TrueauthRuntime.NAME_REGISTRY.isPremium(name)) {
                        // Existing player - verify password
                        String serverPasswordHash = TrueauthRuntime.NAME_REGISTRY.getPassword(name);
                        if (serverPasswordHash != null && serverPasswordHash.equals(clientPasswordHash)) {
                            // Password matches, allow access
                            TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(name, this.authenticatedProfile.getId(), ip, clientPasswordHash);
                            if (TrueauthConfig.debug()) {
                                System.out.println("[TrueAuth] password auth succeeded, player: " + name + ", ip: " + ip);
                            }
                            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                            clearFabricApiQueryChannel(this.trueauth$txId);
                            reset(); ci.cancel(); return;
                        } else {
                            // Password doesn't match
                            String msg = "Incorrect password! If this is a mistake, contact the server administrator.";
                            if (TrueauthConfig.debug()) {
                                System.out.println("[TrueAuth] password auth failed, player: " + name + ", ip: " + ip + ", message: " + msg);
                            }
                            sendDisconnectWithReason(Component.literal(msg));
                            clearFabricApiQueryChannel(this.trueauth$txId);
                            reset(); ci.cancel(); return;
                        }
                    } else {
                        // New player - store their password and allow access
                        TrueauthRuntime.NAME_REGISTRY.recordOfflinePlayer(name, this.authenticatedProfile.getId(), ip, clientPasswordHash);
                        if (TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth] new player registered, player: " + name + ", ip: " + ip);
                        }
                        AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                        clearFabricApiQueryChannel(this.trueauth$txId);
                        reset(); ci.cancel(); return;
                    }
                } else {
                    // No password provided - technical error
                    String msg = "Password retrieval failed. Please restart your game and try again.";
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] no password provided, player: " + name + ", ip: " + ip);
                    }
                    sendDisconnectWithReason(Component.literal(msg));
                    clearFabricApiQueryChannel(this.trueauth$txId);
                    reset(); ci.cancel(); return;
                }
            }
        }
        
        // If we get here and ackOk is false, use existing failure logic
        if (!ackOk) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] auth failed, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip + ", reason: client rejected");
            }
            handleAuthFailure(ip, "client rejected");
            clearFabricApiQueryChannel(this.trueauth$txId);
            reset(); ci.cancel(); return;
        }

        // Idempotency protection: if already handled this handshake's ack, ignore duplicate packet
        if (this.trueauth$ackHandled) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] duplicate auth packet ignored, txId: " + this.trueauth$txId);
            }
            clearFabricApiQueryChannel(this.trueauth$txId);
            ci.cancel();
            return;
        }
        this.trueauth$ackHandled = true;

        // 关键：使用异步 API，不在主线程阻塞
        try {
            // 立即取消原始调用（以免继续执行原有逻辑），但不要 reset()，保留状态直到回调完成
            ci.cancel();
            
            // Clear TrueAuth's transaction ID from Fabric API's channels map to prevent blocking
            clearFabricApiQueryChannel(this.trueauth$txId);

            SessionCheck.hasJoinedAsync(this.authenticatedProfile.getName(), this.trueauth$nonce, ip)
                    .whenComplete((resOpt, throwable) -> {
                        // 始终在主线程处理后续逻辑
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

                                TrueauthRuntime.NAME_REGISTRY.recordPremiumPlayer(res.name(), res.uuid(), ip);
                                TrueauthRuntime.IP_GRACE.record(res.name(), ip, res.uuid());

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
        AuthDecider.Decision d = AuthDecider.onFailure(name, ip);

        switch (d.kind) {
            case PREMIUM_GRACE -> {
                UUID premium = d.premiumUuid != null ? d.premiumUuid
                        : TrueauthRuntime.NAME_REGISTRY.getPremiumUuid(name).orElse(null);
                if (premium != null) {
                    this.authenticatedProfile = new GameProfile(premium, name);
                } else {
                    AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                }
            }
            case OFFLINE -> {
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] offline entry");
                }
                AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
            }
            case DENY -> {
                String msg = d.denyMessage != null ? d.denyMessage
                        : "Auth failed, offline entry denied to protect your premium save data. Please try again later.";
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] auth denied, player: " + name + ", ip: " + ip + ", message: " + msg);
                }
                sendDisconnectWithReason(Component.literal(msg));
            }
        }
    }

    @Unique
    private void sendDisconnectWithReason(Component reason) {
        new Thread(() -> {
            try {
                this.connection.send(new ClientboundLoginDisconnectPacket(reason));
            } catch (Throwable ignored) {}
            this.connection.disconnect(reason);
        }, "TrueUUID-AsyncDisconnect").start();
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
    
    @Unique
    private void clearFabricApiQueryChannel(int txId) {
        if (!isForgifiedFabricAPIPresent()) return;
        
        try {
            // Get the addon from Fabric API's NetworkHandlerExtensions interface
            Object addon = null;
            for (Class<?> iface : this.getClass().getInterfaces()) {
                if (iface.getName().contains("NetworkHandlerExtensions")) {
                    Method getAddonMethod = iface.getMethod("getAddon");
                    addon = getAddonMethod.invoke(this);
                    break;
                }
            }
            
            if (addon == null) return;
            
            // Access the channels map from ServerLoginNetworkAddon
            Field channelsField = null;
            for (Field f : addon.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    channelsField = f;
                    break;
                }
            }
            
            if (channelsField != null) {
                channelsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Integer, ?> channels = (Map<Integer, ?>) channelsField.get(addon);
                if (channels != null && channels.containsKey(txId)) {
                    channels.remove(txId);
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] cleared Fabric API query channel for txId: " + txId);
                    }
                }
            }
        } catch (Throwable t) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] failed to clear Fabric API query channel: " + t.getMessage());
            }
        }
    }

    @Invoker("startClientVerification")
    protected abstract void trueauth$startClientVerification(GameProfile profile);
}
