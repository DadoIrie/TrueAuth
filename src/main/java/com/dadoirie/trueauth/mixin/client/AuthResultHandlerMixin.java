package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.client.PasswordStorage;
import com.dadoirie.trueauth.client.ServerAddressStorage;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthResultPayload;
import com.dadoirie.trueauth.net.NetIds;
import net.minecraft.client.Minecraft;
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
public abstract class AuthResultHandlerMixin {
    @Shadow private Connection connection;

    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void trueauth$onAuthResult(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        CustomQueryPayload payload = packet.payload();
        if (!NetIds.AUTH_RESULT.equals(payload.id())) return;
        if (!(payload instanceof AuthResultPayload(String passwordHash, boolean passwordChanged))) return;

        String serverAddress = ServerAddressStorage.get("current");
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] Received auth result packet from " + serverAddress + " with password hash");
        }
        
        // Store the password hash for this server and confirm password change
        if (serverAddress != null && passwordHash != null && !passwordHash.isEmpty()) {
            String username = Minecraft.getInstance().getUser().getName();
            PasswordStorage.setPasswordHashForServer(username, serverAddress, passwordHash);
            
            if (passwordChanged) {
                PasswordStorage.confirmPasswordChange(username, serverAddress);
            }
            
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] === AFTER SUCCESS PACKET ===");
                System.out.println("[TrueAuth] Server: " + serverAddress);
                PasswordStorage.debugPrintAll();
            }
            
            this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(), buf -> {
                buf.writeUtf(passwordHash);
            }));
        } else {
            System.err.println("[TrueAuth] ERROR: Cannot store password - serverAddress=" + serverAddress + ", passwordHash=" + passwordHash);
        }
        ci.cancel();
    }
}
