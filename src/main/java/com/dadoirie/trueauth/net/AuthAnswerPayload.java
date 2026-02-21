package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/**
 * Payload sent from client to server during LOGIN phase.
 * Contains authentication data including password and optional newPassword.
 */
public record AuthAnswerPayload(
    boolean ok,
    String passwordHash,
    String newPasswordHash
) implements CustomQueryAnswerPayload {
    
    public AuthAnswerPayload(boolean ok) {
        this(ok, null, null);
    }
    
    public AuthAnswerPayload(boolean ok, String passwordHash) {
        this(ok, passwordHash, null);
    }
    
    public AuthAnswerPayload(FriendlyByteBuf buf) {
        this(
            buf.readBoolean(),
            buf.readableBytes() > 0 ? buf.readUtf(32767) : null,
            buf.readableBytes() > 0 ? buf.readUtf(32767) : null
        );
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(ok);
        if (passwordHash != null) {
            buf.writeUtf(passwordHash);
        }
        if (newPasswordHash != null) {
            buf.writeUtf(newPasswordHash);
        }
    }
}
