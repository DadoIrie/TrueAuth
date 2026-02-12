package com.dadoirie.trueauth;

import com.mojang.logging.LogUtils;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

@Mod(Trueauth.MODID)
public class Trueauth {
    public static final String MODID = "trueauth";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Trueauth(IEventBus modBus) {
        // 注册并生成 config/trueauth-common.toml
        TrueauthConfig.register();

        // 初始化运行时单例（注册表、最近 IP 容错缓存等）
        TrueauthRuntime.init();

        modBus.addListener(this::onConfigLoad);

        LOGGER.info("TrueAuth loaded");
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        // =====Mojang network connectivity test=====
        // If nomojang is enabled, skip Mojang session server connectivity check at startup
        if (TrueauthConfig.nomojangEnabled()) {
            LOGGER.info("nomojang enabled, skipping Mojang session server connectivity check");
        } else {
            // =====Mojang network connectivity test=====
            try {
                String testUrl = TrueauthConfig.COMMON.mojangReverseProxy.get()+"/session/minecraft/hasJoined?username=Mojang&serverId=test";
                java.net.URL url = new java.net.URL(testUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000); // 3 second timeout
                conn.setReadTimeout(3000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                    LOGGER.info("Successfully connected to Mojang session server (sessionserver.mojang.com), response code: {}", responseCode);
                } else {
                    LOGGER.warn("Mojang session server returned unexpected response code: {}", responseCode);
                }
            } catch (Exception e) {
                LOGGER.error("Unable to connect to Mojang session server (sessionserver.mojang.com), please check network connection or firewall settings.", e);
            }
        }
    }
}