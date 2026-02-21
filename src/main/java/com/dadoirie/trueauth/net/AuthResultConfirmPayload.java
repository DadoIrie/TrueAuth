package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/**
 * Payload sent from client to server as confirmation of password storage.
 * Contains the password hash that the client stored.
 */
public record AuthResultConfirmPayload(String passwordHash) implements CustomQueryAnswerPayload {
    
    public AuthResultConfirmPayload(FriendlyByteBuf buf) {
        this(buf.readUtf());
    }
    
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(passwordHash != null ? passwordHash : "");
    }
}
