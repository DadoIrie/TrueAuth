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
        Decision decision = new Decision();

        // 1) Known premium name: deny offline fallback
        // TODO: might not have usecase
        /* if (TrueauthRuntime.NAME_REGISTRY.isRegistered(name)) {
            d.kind = Decision.Kind.DENY;
            d.denyMessage = "This name is already used. Please use another Playername or contact the serves mods if you are using a online account";
            return d;
        } */

        // 2) Recent same IP success grace: temporarily treat as premium
        if (TrueauthConfig.recentIpGraceEnabled()) {
            Optional<UUID> premium = TrueauthRuntime.IP_GRACE.tryGrace(name, ip, TrueauthConfig.recentIpGraceTtlSeconds());
            if (premium.isPresent()) {
                decision.kind = Decision.Kind.PREMIUM_GRACE;
                decision.premiumUuid = premium.get();
                return decision;
            }
        }

        // TODO most likely not needed anymore
        /* if (TrueauthConfig.allowOfflineForUnknownOnly() && !known) {
            d.kind = Decision.Kind.OFFLINE;
            return d;
        } */

        // 4) Otherwise deny
        decision.kind = Decision.Kind.DENY;
        decision.denyMessage = "Auth failed, offline entry denied to protect your premium save data. Please try again later.";
        return decision;
    }

    private AuthDecider() {}

}