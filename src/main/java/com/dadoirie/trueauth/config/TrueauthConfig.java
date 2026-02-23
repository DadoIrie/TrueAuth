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

    // 旧开关：保留兼容，但新策略将更细化
    public static String offlineFallbackMessage() { return COMMON.offlineFallbackMessage.get(); }
    public static boolean showOfflineLongMessage() { return COMMON.showOfflineLongMessage.get(); }

    // 新增：短副标题（用于屏幕 Title 区域）
    public static String offlineShortSubtitle() { return COMMON.offlineShortSubtitle.get(); }
    public static String offlineShortSubtitleNoMojang() { return COMMON.offlineShortSubtitleNoMojang.get(); }
    public static String offlineShortSubtitleIpGrace() { return COMMON.offlineShortSubtitleIpGrace.get(); }
    public static String onlineShortSubtitle() { return COMMON.onlineShortSubtitle.get(); }
    
    // 新增：标题文本（用于屏幕 Title 区域）
    public static String offlineTitle() { return COMMON.offlineTitle.get(); }
    public static String onlineTitle() { return COMMON.onlineTitle.get(); }
    public static String semiPremiumTitle() { return COMMON.semiPremiumTitle.get(); }
    public static String authStateReport() { return COMMON.authStateReport.get(); }

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
    // Op feature
    public static boolean enabledTrueauthOpChanges() { return COMMON.enabledTrueauthOpChanges.get(); }
    public static boolean opPremiumOnly() { return COMMON.opPremiumOnly.get(); }

    public static final class Common {
        public final ModConfigSpec.ConfigValue<String> offlineFallbackMessage;
        public final ModConfigSpec.BooleanValue showOfflineLongMessage;

        // 新增
        public final ModConfigSpec.ConfigValue<String> offlineShortSubtitle;
        public final ModConfigSpec.ConfigValue<String> offlineShortSubtitleNoMojang;
        public final ModConfigSpec.ConfigValue<String> offlineShortSubtitleIpGrace;
        public final ModConfigSpec.ConfigValue<String> onlineShortSubtitle;
        
        // 新增：标题文本
        public final ModConfigSpec.ConfigValue<String> offlineTitle;
        public final ModConfigSpec.ConfigValue<String> onlineTitle;
        public final ModConfigSpec.ConfigValue<String> semiPremiumTitle;
        public final ModConfigSpec.ConfigValue<String> authStateReport;

        // 新增 nomojang 配置
        public final ModConfigSpec.BooleanValue nomojangEnabled;
        public final ModConfigSpec.ConfigValue<String> mojangReverseProxy;

        // 新增：策略相关
        public final ModConfigSpec.BooleanValue allowOfflineForUnknownOnly;
        public final ModConfigSpec.BooleanValue recentIpGraceEnabled;
        public final ModConfigSpec.IntValue recentIpGraceTtlSeconds;
        public final ModConfigSpec.BooleanValue debug;
        public final ModConfigSpec.BooleanValue whitelistEnabled;
        // Op feature
        public final ModConfigSpec.BooleanValue enabledTrueauthOpChanges;
        public final ModConfigSpec.BooleanValue opPremiumOnly;

        Common(ModConfigSpec.Builder b) {
            b.push("auth");
            offlineFallbackMessage = b.define(
                    "offlineFallbackMessage",
                    "Note: You are entering the server in offline mode. If you have a premium account, network issues may have prevented authentication. Please try reconnecting. Continuing to play may result in data loss if authentication succeeds later."
            );
            showOfflineLongMessage = b.define("showOfflineLongMessage", false);

            // Short subtitles for title area
            offlineShortSubtitle = b.define("offlineShortSubtitle", "Auth failed");
            offlineShortSubtitleNoMojang = b.define("offlineShortSubtitleNoMojang", "Disabled Mojang auth");
            offlineShortSubtitleIpGrace = b.define("offlineShortSubtitleIpGrace", "IP Grace verified");
            onlineShortSubtitle  = b.define("onlineShortSubtitle",  "Premium verified");
            
            // Title text
            offlineTitle = b.define("offlineTitle", "Offline Mode");
            onlineTitle  = b.define("onlineTitle",  "Premium Mode");
            semiPremiumTitle = b.define("semiPremiumTitle", "Semi-Premium");
            
            authStateReport = b.comment("How to report auth state to player: 'chat' or 'screen'").define("authStateReport", "chat");

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
            // Op feature
            enabledTrueauthOpChanges = b.comment("Enable TrueAuth op command replacement (uses NameRegistry for UUID lookup, adds optional level and bypassesPlayerLimit arguments).")
                    .define("op.enabledTrueauthOpChanges", false);
            opPremiumOnly = b.comment("Require players to be registered as premium to use /op command.")
                    .define("op.premiumOnly", true);
            b.pop();
        }
    }

    private TrueauthConfig() {}

}
