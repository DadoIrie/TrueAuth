package com.dadoirie.trueauth.server;

import net.minecraft.network.Connection;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks authentication state during login phase for each connection.
 */
public final class AuthState {
    public enum FallbackReason { TIMEOUT, FAILURE }

    private static final ConcurrentHashMap<Connection, FallbackReason> OFFLINE_FALLBACK = new ConcurrentHashMap<>();

    public static void markOfflineFallback(Connection conn, FallbackReason reason) {
        if (conn != null && reason != null) {
            OFFLINE_FALLBACK.put(conn, reason);
        }
    }

    public static Optional<FallbackReason> consume(Connection conn) {
        if (conn == null) return Optional.empty();
        FallbackReason r = OFFLINE_FALLBACK.remove(conn);
        return Optional.ofNullable(r);
    }

    private AuthState() {}
}
