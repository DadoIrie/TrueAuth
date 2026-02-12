package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.client.PasswordStorage;
import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.NetIds;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.network.protocol.login.custom.DiscardedQueryAnswerPayload;
import net.neoforged.fml.loading.LoadingModList;
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

        // Case 1: Direct AuthPayload (ForgifiedFabricAPI not loaded or TrueAuth ran first)
        if (payload instanceof AuthPayload(String serverId)) {
            processAuth(serverId, packet.transactionId());
            ci.cancel();
            return;
        }

        // Case 2: ForgifiedFabricAPI wrapped it
        if (forgifiedFabricApiLoaded() && isFabricWrapper(payload)) {
            String serverId = extractServerId(payload);
            if (serverId != null) {
                processAuth(serverId, packet.transactionId());
                ci.cancel();
            }
        }
    }

    private void processAuth(String serverId, int txId) {
        boolean ok;
        try {
            Minecraft mc = Minecraft.getInstance();
            User user = mc.getUser();
            mc.getMinecraftSessionService().joinServer(
                    user.getProfileId(), user.getAccessToken(), serverId);
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }

        String passwordHash = PasswordStorage.loadPassword();
        boolean hasPassword = !passwordHash.isEmpty();
        AuthAnswerPayload answer = new AuthAnswerPayload(ok, hasPassword, hasPassword ? passwordHash : null);
        this.connection.send(new ServerboundCustomQueryAnswerPacket(txId, answer));
    }

    private static boolean forgifiedFabricApiLoaded() {
        return LoadingModList.get().getModFileById("forgifiedfabricapi") != null;
    }

    private static boolean isFabricWrapper(Object payload) {
        return payload.getClass().getName().contains("PacketByteBufLoginQueryRequestPayload");
    }

    private static String extractServerId(Object payload) {
        // Fabric's wrapper has the buffer as a field
        // We need to access it via accessor since we can't have direct dependency
        try {
            Object buf = payload.getClass().getMethod("data").invoke(payload);
            return (String) buf.getClass().getMethod("readUtf").invoke(buf);
        } catch (Exception e) {
            return null;
        }
    }
}
