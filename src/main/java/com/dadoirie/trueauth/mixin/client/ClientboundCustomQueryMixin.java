package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.net.AuthPayload;
import com.dadoirie.trueauth.net.NetIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientboundCustomQueryPacket.class)
public abstract class ClientboundCustomQueryMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void trueauth$decodeAuth(ResourceLocation id,
                                            FriendlyByteBuf buf,
                                            CallbackInfoReturnable<CustomQueryPayload> cir) {
        if (NetIds.AUTH.equals(id)) {
            cir.setReturnValue(new AuthPayload(buf));
        }
    }
}