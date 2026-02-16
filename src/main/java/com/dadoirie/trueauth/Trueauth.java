package com.dadoirie.trueauth;

import com.mojang.logging.LogUtils;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.FabricNetworkHandler;
import com.dadoirie.trueauth.server.TrueauthRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Mod(Trueauth.MODID)
public class Trueauth {
    public static final String MODID = "trueauth";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Trueauth(IEventBus modBus) {
        // 注册并生成 config/trueauth-common.toml
        TrueauthConfig.register();

        // 初始化运行时单例（注册表、最近 IP 容错缓存等）
        TrueauthRuntime.init();

        // Initialize Fabric API server networking
        FabricNetworkHandler.initServer();

        modBus.addListener(this::onConfigLoad);

        LOGGER.info("TrueAuth loaded");
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        boolean onlineMode = true;
        Path serverPropsPath = FMLPaths.GAMEDIR.get().resolve("server.properties");
        if (Files.exists(serverPropsPath)) {
            try (Reader r = Files.newBufferedReader(serverPropsPath)) {
                Properties props = new Properties();
                props.load(r);
                onlineMode = Boolean.parseBoolean(props.getProperty("online-mode", "true"));
            } catch (Exception e) {
                LOGGER.debug("Could not read server.properties: {}", e.getMessage());
            }
        }
        
        if (onlineMode) {
            LOGGER.info("Server is in online-mode=true. TrueAuth is inactive.");
            return;
        }
        
        if (TrueauthConfig.nomojangEnabled()) {
            LOGGER.info("nomojang enabled, skipping Mojang session server connectivity check");
            return;
        }
        
        try {
            String testUrl = TrueauthConfig.COMMON.mojangReverseProxy.get() + "/session/minecraft/hasJoined?username=Mojang&serverId=test";
            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 204 || responseCode == 403) {
                LOGGER.info("Successfully connected to session server " + TrueauthConfig.COMMON.mojangReverseProxy.get() + ", response code: {}", responseCode);
            } else {
                LOGGER.warn("Mojang session server returned unexpected response code: {}", responseCode);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to connect to Mojang session server " + TrueauthConfig.COMMON.mojangReverseProxy.get() + ", please check network connection or firewall settings.", e);
        }
    }
}