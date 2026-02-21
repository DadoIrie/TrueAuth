package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.client.PasswordStorage;
import com.dadoirie.trueauth.client.ServerAddressStorage;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.NetIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakeMixin {
    @Shadow private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void trueauth$onCustomQuery(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        CustomQueryPayload payload = packet.payload();
        if (!NetIds.AUTH.equals(payload.id())) return;
        if(!(payload instanceof AuthPayload(String serverId, boolean skipMojang))) return;

        boolean ok;
        if (skipMojang) {
            // Server signaled nomojang mode - skip joinServer call
            ok = false;
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] server signaled nomojang mode, skipping Mojang auth");
            }
        } else {
            try {
                Minecraft mc = Minecraft.getInstance();
                User user = mc.getUser();
                var profile = user.getProfileId();
                String token = user.getAccessToken();

                // Token is only used locally
                mc.getMinecraftSessionService().joinServer(profile, token, serverId);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        }
        
        // Get the current server hostname
        String serverHostname = ServerAddressStorage.get("current");
        
        // ! CRITICAL - remove or comment out for release builds - Debug: print entire password storage before handshake
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] === BEFORE HANDSHAKE ===");
            System.out.println("[TrueAuth] Server: " + serverHostname);
            PasswordStorage.debugPrintAll();
        }
        
        // Get current username for password lookup
        String username = Minecraft.getInstance().getUser().getName();
        
        String passwordHash = PasswordStorage.getPasswordForServer(username, serverHostname);
        String newPasswordHash = PasswordStorage.getNewPasswordForServer(username, serverHostname);
        
        AuthAnswerPayload answer = newPasswordHash.isEmpty() 
            ? new AuthAnswerPayload(ok, passwordHash)
            : new AuthAnswerPayload(ok, passwordHash, newPasswordHash);

        
        this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(), answer));
        ci.cancel();
    }
}
