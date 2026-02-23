package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import net.minecraft.network.Connection;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthState {
    public enum FallbackReason { FAILURE, NOMOJANG, IP_GRACE }

    private static final ConcurrentHashMap<Connection, FallbackReason> OFFLINE_FALLBACK = new ConcurrentHashMap<>();

    public static void markOfflineFallback(Connection conn, FallbackReason reason) {
        if (conn != null && reason != null) {
            OFFLINE_FALLBACK.put(conn, reason);
        }
    }

    public static Optional<String> consume(Connection conn) {
        if (conn == null) return Optional.empty();
        FallbackReason reason = OFFLINE_FALLBACK.remove(conn);
        if (reason == null) return Optional.empty();
        String message = switch (reason) {
            case FAILURE -> TrueauthConfig.offlineShortSubtitle();
            case NOMOJANG -> TrueauthConfig.offlineShortSubtitleNoMojang();
            case IP_GRACE -> TrueauthConfig.offlineShortSubtitleIpGrace();
        };
        return Optional.of(message);
    }

    private AuthState() {}
}
