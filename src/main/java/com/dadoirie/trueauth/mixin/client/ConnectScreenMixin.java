package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.client.ServerAddressStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture the server hostname when connecting to a server.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen")
public class ConnectScreenMixin {
    
    @Inject(
        method = "connect(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Lnet/minecraft/client/multiplayer/TransferState;)V",
        at = @At("HEAD")
    )
    private void trueauth$onConnect(Minecraft mc, ServerAddress serverAddress, ServerData serverData, net.minecraft.client.multiplayer.TransferState transferState, CallbackInfo ci) {
        // Store the server hostname for later use during CONFIGURATION phase
        if (serverData != null && serverData.ip != null) {
            // Use a simple key - we only support one connection at a time on client
            ServerAddressStorage.store("current", serverData.ip);
        }
    }
}
