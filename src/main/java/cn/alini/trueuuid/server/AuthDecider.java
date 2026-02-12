package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;

import java.util.Optional;
import java.util.UUID;

public final class AuthDecider {

    public static class Decision {
        public enum Kind { PREMIUM_GRACE, OFFLINE, DENY }
        public Kind kind;
        public UUID premiumUuid; // PREMIUM_GRACE 时填
        public String denyMessage;
    }

    public static Decision onFailure(String name, String ip) {
        Decision d = new Decision();

        boolean known = TrueauthRuntime.NAME_REGISTRY.isKnownPremiumName(name);

        // 1) Known premium name: deny offline fallback
        if (known && TrueauthConfig.knownPremiumDenyOffline()) {
            d.kind = Decision.Kind.DENY;
            d.denyMessage = "This name is bound to a premium UUID. Offline mode is not allowed when auth fails. Please check your network and try again.";
            return d;
        }

        // 2) Recent same IP success grace: temporarily treat as premium
        if (TrueauthConfig.recentIpGraceEnabled()) {
            Optional<UUID> p = TrueauthRuntime.IP_GRACE.tryGrace(name, ip, TrueauthConfig.recentIpGraceTtlSeconds());
            if (p.isPresent()) {
                d.kind = Decision.Kind.PREMIUM_GRACE;
                d.premiumUuid = p.get();
                return d;
            }
        }

        // 3) Unknown name: allow offline fallback
        if (TrueauthConfig.allowOfflineForUnknownOnly() && !known) {
            d.kind = Decision.Kind.OFFLINE;
            return d;
        }

        // 4) Otherwise deny
        d.kind = Decision.Kind.DENY;
        d.denyMessage = "Auth failed, offline entry denied to protect your premium save data. Please try again later.";
        return d;
    }

    private AuthDecider() {}

}