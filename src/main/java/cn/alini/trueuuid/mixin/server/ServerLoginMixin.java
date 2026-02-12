// java
package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.AuthQueryTracker;
import com.dadoirie.trueauth.net.NetIds;
import com.dadoirie.trueauth.server.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
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

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
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
                        TrueauthRuntime.NAME_REGISTRY.recordSuccess(name, premium, ip);
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
        ci.cancel(); // 阻止原版 tick 推进登录状
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
            String msg = TrueauthConfig.timeoutKickMessage();
            Component reason = Component.literal(msg != null ? msg : "Login timeout, account verification incomplete");
            sendDisconnectWithReason(reason);
            reset();
        }
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
            reset(); ci.cancel(); return;
        }

        boolean ackOk = payload.ok();
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
                if (TrueauthConfig.knownPremiumDenyOffline() && TrueauthRuntime.NAME_REGISTRY.isKnownPremiumName(name)) {
                    String msg = "This name is bound to a premium UUID. Offline mode is not allowed. Please check your network and try again.";
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] denying offline entry for known premium name: " + name);
                    }
                    sendDisconnectWithReason(Component.literal(msg));
                    reset(); ci.cancel(); return;
                }
                
                PlayerPasswordStorage storage = TrueauthRuntime.PASSWORD_STORAGE;
                
                if (hasPassword && clientPasswordHash != null) {
                    // Check if player already has a stored password
                    if (storage.hasStoredPassword(name)) {
                        // Existing player - verify password
                        String serverPasswordHash = storage.getPasswordHash(name);
                        if (serverPasswordHash != null && serverPasswordHash.equals(clientPasswordHash)) {
                            // Password matches, allow access
                            if (TrueauthConfig.debug()) {
                                System.out.println("[TrueAuth] password auth succeeded, player: " + name + ", ip: " + ip);
                            }
                            AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                            reset(); ci.cancel(); return;
                        } else {
                            // Password doesn't match
                            String msg = "Incorrect password! If this is a mistake, contact the server administrator.";
                            if (TrueauthConfig.debug()) {
                                System.out.println("[TrueAuth] password auth failed, player: " + name + ", ip: " + ip + ", message: " + msg);
                            }
                            sendDisconnectWithReason(Component.literal(msg));
                            reset(); ci.cancel(); return;
                        }
                    } else {
                        // New player - store their password and allow access
                        storage.storePassword(name, clientPasswordHash);
                        if (TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth] new player registered, player: " + name + ", ip: " + ip);
                        }
                        AuthState.markOfflineFallback(this.connection, AuthState.FallbackReason.FAILURE);
                        reset(); ci.cancel(); return;
                    }
                } else {
                    // No password provided
                    if (storage.hasStoredPassword(name)) {
                        // Existing player without password - require password
                        String msg = "Incorrect password! If this is a mistake, contact the server administrator.";
                        if (TrueauthConfig.debug()) {
                            System.out.println("[TrueAuth] password auth failed, player: " + name + ", ip: " + ip + ", message: " + msg);
                        }
                        sendDisconnectWithReason(Component.literal(msg));
                        reset(); ci.cancel(); return;
                    }
                    // New player without password - fall through to existing logic
                }
            }
        }
        
        // If we get here and ackOk is false, use existing failure logic
        if (!ackOk) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] auth failed, player: " + (this.authenticatedProfile != null ? this.authenticatedProfile.getName() : "<unknown>") + ", ip: " + ip + ", reason: client rejected");
            }
            handleAuthFailure(ip, "client rejected");
            reset(); ci.cancel(); return;
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

        // 关键：使用异步 API，不在主线程阻塞
        try {
            // 立即取消原始调用（以免继续执行原有逻辑），但不要 reset()，保留状态直到回调完成
            ci.cancel();

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

                                // 成功：记录注册表/近期 IP；替换为正版 UUID + 名称大小写矫正 + 注入皮肤
                                TrueauthRuntime.NAME_REGISTRY.recordSuccess(res.name(), res.uuid(), ip);
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
                                    trueauth$startClientVerification(newProfile);
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
        // During LOGIN phase, only ClientboundLoginDisconnectPacket can be sent
        // ClientboundDisconnectPacket is for PLAY phase and will cause encoder error
        this.connection.send(new ClientboundLoginDisconnectPacket(reason));
        this.connection.disconnect(reason);
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
