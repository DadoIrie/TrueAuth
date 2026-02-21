package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.net.AuthAnswerPayload;
import com.dadoirie.trueauth.net.AuthQueryTracker;
import com.dadoirie.trueauth.net.AuthResultConfirmPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerboundCustomQueryAnswerPacket.class)
public abstract class ServerboundCustomQueryAnswerMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void trueauth$decodeAuthAnswer(
            int txId,
            FriendlyByteBuf buf,
            CallbackInfoReturnable<CustomQueryAnswerPayload> cir
    ) {
        // Check for AuthResult confirmation first
        if (AuthQueryTracker.consumeResult(txId)) {
            boolean hasPayload = buf.readBoolean();
            if (hasPayload) {
                cir.setReturnValue(new AuthResultConfirmPayload(buf));
            } else {
                cir.setReturnValue(new AuthResultConfirmPayload(""));
            }
            return;
        }
        
        // Check for AuthAnswer payload
        if (!AuthQueryTracker.consume(txId)) {
            return;
        }

        // 1. First read `hasPayload` according to the vanilla protocol
        boolean hasPayload = buf.readBoolean();
        if (!hasPayload) {
            cir.setReturnValue(new AuthAnswerPayload(false));
            return;
        }

        // 2. The remaining part is your actual payload content
        cir.setReturnValue(new AuthAnswerPayload(buf));
    }
}