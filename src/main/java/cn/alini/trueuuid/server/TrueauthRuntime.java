package com.dadoirie.trueauth.server;

import net.neoforged.fml.loading.FMLEnvironment;

public final class TrueauthRuntime {
    private static volatile boolean INIT = false;
    public static NameRegistry NAME_REGISTRY;
    public static RecentIpGraceCache IP_GRACE;
    public static PlayerPasswordStorage PASSWORD_STORAGE;

    public static void init() {
        if (INIT) return;
        synchronized (TrueauthRuntime.class) {
            if (INIT) return;
            NAME_REGISTRY = new NameRegistry();
            IP_GRACE = new RecentIpGraceCache();
            if (FMLEnvironment.dist.isDedicatedServer()) {
                PASSWORD_STORAGE = new PlayerPasswordStorage();
            }
            INIT = true;
        }
    }

    private TrueauthRuntime() {}

}