package com.dadoirie.trueauth.config;


import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class TrueauthConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        COMMON = new Common(b);
        COMMON_SPEC = b.build();
    }

    public static void register() {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static long timeoutMs() { return COMMON.timeoutMs.get(); }
    public static boolean allowOfflineOnTimeout() { return COMMON.allowOfflineOnTimeout.get(); }

    // 旧开关：保留兼容，但新策略将更细化
    public static boolean allowOfflineOnFailure() { return COMMON.allowOfflineOnFailure.get(); }

    public static String timeoutKickMessage() { return COMMON.timeoutKickMessage.get(); }
    public static String offlineFallbackMessage() { return COMMON.offlineFallbackMessage.get(); }

    // 新增：短副标题（用于屏幕 Title 区域）
    public static String offlineShortSubtitle() { return COMMON.offlineShortSubtitle.get(); }
    public static String onlineShortSubtitle() { return COMMON.onlineShortSubtitle.get(); }
    
    // 新增：标题文本（用于屏幕 Title 区域）
    public static String offlineTitle() { return COMMON.offlineTitle.get(); }
    public static String onlineTitle() { return COMMON.onlineTitle.get(); }

    // 新增：策略相关
    public static boolean allowOfflineForUnknownOnly() { return COMMON.allowOfflineForUnknownOnly.get(); }
    public static boolean recentIpGraceEnabled() { return COMMON.recentIpGraceEnabled.get(); }
    public static int recentIpGraceTtlSeconds() { return COMMON.recentIpGraceTtlSeconds.get(); }
    public static boolean debug() { return COMMON.debug.get(); }
    // 新增 nomojang 开关访问器
    public static boolean nomojangEnabled() { return COMMON.nomojangEnabled.get(); }
    public static String mojangReverseProxy() { return COMMON.mojangReverseProxy.get(); }
    // Whitelist feature
    public static boolean whitelistEnabled() { return COMMON.whitelistEnabled.get(); }

    public static final class Common {
        public final ModConfigSpec.LongValue timeoutMs;
        public final ModConfigSpec.BooleanValue allowOfflineOnTimeout;
        public final ModConfigSpec.BooleanValue allowOfflineOnFailure;
        public final ModConfigSpec.ConfigValue<String> timeoutKickMessage;
        public final ModConfigSpec.ConfigValue<String> offlineFallbackMessage;

        // 新增
        public final ModConfigSpec.ConfigValue<String> offlineShortSubtitle;
        public final ModConfigSpec.ConfigValue<String> onlineShortSubtitle;
        
        // 新增：标题文本
        public final ModConfigSpec.ConfigValue<String> offlineTitle;
        public final ModConfigSpec.ConfigValue<String> onlineTitle;

        // 新增 nomojang 配置
        public final ModConfigSpec.BooleanValue nomojangEnabled;
        public final ModConfigSpec.ConfigValue<String> mojangReverseProxy;

        // 新增：策略相关
        public final ModConfigSpec.BooleanValue allowOfflineForUnknownOnly;
        public final ModConfigSpec.BooleanValue recentIpGraceEnabled;
        public final ModConfigSpec.IntValue recentIpGraceTtlSeconds;
        public final ModConfigSpec.BooleanValue debug;
        public final ModConfigSpec.BooleanValue whitelistEnabled;

        Common(ModConfigSpec.Builder b) {
            b.push("auth");

            timeoutMs = b.defineInRange("timeoutMs", 10_000L, 1_000L, 600_000L);
            allowOfflineOnTimeout = b.comment("false: kick on timeout (default), true: allow offline on timeout").define("allowOfflineOnTimeout", false);
            allowOfflineOnFailure = b.comment("false: kick on failure, true: allow offline on any auth failure (default)").define("allowOfflineOnFailure", true);

            timeoutKickMessage = b.define("timeoutKickMessage", "Login timeout, account verification incomplete");
            offlineFallbackMessage = b.define(
                    "offlineFallbackMessage",
                    "Note: You are entering the server in offline mode. If you have a premium account, network issues may have prevented authentication. Please try reconnecting. Continuing to play may result in data loss if authentication succeeds later."
            );

            // Short subtitles for title area
            offlineShortSubtitle = b.define("offlineShortSubtitle", "Auth failed: Offline mode");
            onlineShortSubtitle  = b.define("onlineShortSubtitle",  "Premium verified");
            
            // Title text
            offlineTitle = b.define("offlineTitle", "Offline Mode");
            onlineTitle  = b.define("onlineTitle",  "Premium Mode");

            // Policy options
            allowOfflineForUnknownOnly = b.comment("Only allow offline fallback for names that have never been verified as premium.")
                    .define("allowOfflineForUnknownOnly", true);
            recentIpGraceEnabled      = b.comment("Enable 'recent same IP success' grace period, temporarily treat as premium on failure within TTL.")
                    .define("recentIpGrace.enabled", true);
            recentIpGraceTtlSeconds   = b.comment("TTL in seconds for 'recent same IP success' grace. Recommended 60~600.")
                    .defineInRange("recentIpGrace.ttlSeconds", 300, 30, 3600);
            debug = b.comment("Enable debug logging").define("debug", false);
            // Skip Mojang session auth
            nomojangEnabled = b.comment("Disable Mojang session server online verification; same IP with recent premium success uses premium UUID, others use offline.")
                    .define("nomojang.enabled", false);
            mojangReverseProxy = b.comment("Mojang reverse proxy address for those who don't want to use a proxy on their server, defaults to Mojang address").define("mojangReverseProxy", "https://sessionserver.mojang.com");
            // Whitelist feature
            whitelistEnabled = b.comment("Enable whitelist feature.")
                    .define("whitelist.enabled", false);
            b.pop();
        }
    }

    private TrueauthConfig() {}

}