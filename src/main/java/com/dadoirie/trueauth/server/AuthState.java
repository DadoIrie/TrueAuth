package com.dadoirie.trueauth.server;

import net.minecraft.network.Connection;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks authentication state during login phase for each connection.
 * Used to communicate auth results to the CONFIGURATION phase.
 */
public final class AuthState {
    public enum FallbackReason { TIMEOUT, FAILURE }

    private static final ConcurrentHashMap<Connection, FallbackReason> OFFLINE_FALLBACK = new ConcurrentHashMap<>();
    
    // Store auth result (password hash) for sending during CONFIGURATION phase
    // Null = no auth result, empty string = auth failed, non-empty = success with password hash
    private static final ConcurrentHashMap<Connection, String> AUTH_RESULT = new ConcurrentHashMap<>();

    public static void markOfflineFallback(Connection conn, FallbackReason reason) {
        if (conn != null && reason != null) {
            OFFLINE_FALLBACK.put(conn, reason);
        }
    }
    
    /**
     * Mark that password authentication succeeded for this connection.
     * @param conn The connection
     * @param passwordHash The password hash (empty string for failure, non-empty for success)
     */
    public static void markAuthSuccess(Connection conn, String passwordHash) {
        if (conn != null) {
            AUTH_RESULT.put(conn, passwordHash);
        }
    }
    
    /**
     * Get and remove the auth result for this connection.
     * Called during CONFIGURATION phase to send the result to client.
     * @return null if no result, empty string for failure, password hash for success
     */
    public static String getAndClearAuthResult(Connection conn) {
        if (conn == null) return null;
        return AUTH_RESULT.remove(conn);
    }

    public static Optional<FallbackReason> consume(Connection conn) {
        if (conn == null) return Optional.empty();
        FallbackReason r = OFFLINE_FALLBACK.remove(conn);
        return Optional.ofNullable(r);
    }

    private AuthState() {}
}
