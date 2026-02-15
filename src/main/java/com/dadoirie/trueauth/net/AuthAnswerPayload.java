package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

/**
 * Payload sent from client to server during LOGIN phase.
 * Contains authentication data including password and optional newPassword.
 */
public record AuthAnswerPayload(
    boolean ok,
    boolean hasPassword,
    String passwordHash,
    boolean hasNewPassword,
    String newPasswordHash
) implements CustomQueryAnswerPayload {
    
    public AuthAnswerPayload(boolean ok) {
        this(ok, false, null, false, null);
    }
    
    public AuthAnswerPayload(boolean ok, String passwordHash) {
        this(ok, passwordHash != null && !passwordHash.isEmpty(), passwordHash, false, null);
    }
    
    public AuthAnswerPayload(boolean ok, String passwordHash, String newPasswordHash) {
        this(
            ok,
            passwordHash != null && !passwordHash.isEmpty(),
            passwordHash,
            newPasswordHash != null && !newPasswordHash.isEmpty(),
            newPasswordHash
        );
    }
    
    public AuthAnswerPayload(FriendlyByteBuf buf) {
        this(readFromBuf(buf));
    }
    
    private AuthAnswerPayload(Object[] data) {
        this((boolean) data[0], (boolean) data[1], (String) data[2], (boolean) data[3], (String) data[4]);
    }
    
    private static Object[] readFromBuf(FriendlyByteBuf buf) {
        boolean ok = buf.readBoolean();
        boolean hasPassword = buf.readBoolean();
        String passwordHash = hasPassword ? buf.readUtf(32767) : null;
        boolean hasNewPassword = buf.readBoolean();
        String newPasswordHash = hasNewPassword ? buf.readUtf(32767) : null;
        return new Object[] { ok, hasPassword, passwordHash, hasNewPassword, newPasswordHash };
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(ok);
        buf.writeBoolean(hasPassword);
        if (hasPassword) {
            buf.writeUtf(passwordHash);
        }
        buf.writeBoolean(hasNewPassword);
        if (hasNewPassword) {
            buf.writeUtf(newPasswordHash);
        }
    }
}
