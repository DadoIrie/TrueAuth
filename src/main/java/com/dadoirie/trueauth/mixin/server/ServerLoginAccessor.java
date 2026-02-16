package com.dadoirie.trueauth.mixin.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface ServerLoginAccessor {
    @Accessor("authenticatedProfile")
    GameProfile trueauth$getAuthenticatedProfile();
    
    @Accessor("authenticatedProfile")
    void trueauth$setAuthenticatedProfile(GameProfile profile);
    
    @Accessor("connection")
    Connection trueauth$getConnection();
    
    @Invoker("startClientVerification")
    void trueauth$startClientVerification(GameProfile profile);
}
