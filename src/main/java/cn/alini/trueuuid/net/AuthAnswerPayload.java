package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

public record AuthAnswerPayload(boolean ok, boolean hasPassword, String passwordHash) implements CustomQueryAnswerPayload {
    private static final int CURRENT_VERSION = 1;
    
    public AuthAnswerPayload(boolean ok) {
        this(ok, false, null);
    }
    
    public AuthAnswerPayload(boolean ok, String passwordHash) {
        this(ok, passwordHash != null && !passwordHash.isEmpty(), passwordHash);
    }
    
    public AuthAnswerPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readBoolean(), buf.readableBytes() > 0 ? buf.readUtf(32767) : null);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(ok);
        buf.writeBoolean(hasPassword);
        if (hasPassword && passwordHash != null) {
            buf.writeUtf(passwordHash);
        }
    }
}
