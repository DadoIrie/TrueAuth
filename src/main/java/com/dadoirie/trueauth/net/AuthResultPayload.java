package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload sent from server to client during LOGIN phase.
 * Contains the password hash for the client to store and a flag
 * indicating if the password was changed during this login.
 */
public record AuthResultPayload(String passwordHash, boolean passwordChanged) implements CustomQueryPayload {
    public static final ResourceLocation ID = NetIds.AUTH_RESULT;
    
    public AuthResultPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32767), buf.readBoolean());
    }
    
    @Override
    public ResourceLocation id() {
        return ID;
    }
    
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(passwordHash);
        buf.writeBoolean(passwordChanged);
    }
}
