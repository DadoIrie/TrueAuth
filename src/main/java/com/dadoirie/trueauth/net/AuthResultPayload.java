package com.dadoirie.trueauth.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Payload sent from server to client during CONFIGURATION phase
 * to notify the client of the password authentication result.
 * Contains the password hash for the client to store.
 */
public record AuthResultPayload(String passwordHash) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AuthResultPayload> TYPE = 
        new CustomPacketPayload.Type<>(NetIds.AUTH_RESULT);
    
    // Configuration phase uses FriendlyByteBuf, not RegistryFriendlyByteBuf
    public static final StreamCodec<FriendlyByteBuf, AuthResultPayload> STREAM_CODEC = 
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            AuthResultPayload::passwordHash,
            AuthResultPayload::new
        );
    
    @Override
    public CustomPacketPayload.Type<AuthResultPayload> type() {
        return TYPE;
    }
}
