package com.dadoirie.trueauth.mixin;

import com.dadoirie.trueauth.Trueauth;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConditionalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(Trueauth.MODID);
    
    private static final Map<String, String> MIXIN_MOD_REQUIREMENTS = Map.of(
        "com.dadoirie.trueauth.mixin.client.AuthMethodScreenMixin", "authme"
    );
    
    private static final Set<String> SKIP_WHEN_FFAPI_PRESENT = Set.of(
        "com.dadoirie.trueauth.mixin.server.ServerLoginMixin",
        "com.dadoirie.trueauth.mixin.server.ServerboundCustomQueryAnswerMixin",
        "com.dadoirie.trueauth.mixin.client.ClientboundCustomQueryMixin",
        "com.dadoirie.trueauth.mixin.client.ClientHandshakeMixin",
        "com.dadoirie.trueauth.mixin.client.AuthResultHandlerMixin"
    );
    
    private static final Set<String> REQUIRE_FFAPI = Set.of(
        "com.dadoirie.trueauth.mixin.server.ServerLoginAccessor"
    );
    
    private static final Map<String, String> MIXIN_CONFIG_REQUIREMENTS = Map.of(
        "com.dadoirie.trueauth.mixin.server.OpCommandMixin", "enabledTrueauthOpChanges"
    );
    
    private static final Map<String, String> MIXIN_APPLY_MESSAGES = Map.of(
        "com.dadoirie.trueauth.mixin.server.OpCommandMixin", "Applying TrueAuth vanilla op command changes"
    );
    
    private static Boolean ffapiPresent = null;
    
    private static boolean isModLoaded(String modId) {
        if (LoadingModList.get() != null) {
            return LoadingModList.get().getModFileById(modId) != null;
        }
        return false;
    }

    public static boolean isFabricApiPresent() {
        if (ffapiPresent == null) {
            ffapiPresent = isModLoaded("fabric_networking_api_v1");
            if (ffapiPresent) {
                LOGGER.info("[TrueAuth] ForgifiedFabricAPI detected - using Fabric API networking");
            }
        }
        return ffapiPresent;
    }

    private static boolean readConfigBoolean(String configKey, boolean defaultValue) {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve("trueauth-common.toml");
            if (Files.exists(configPath)) {
                try (BufferedReader reader = Files.newBufferedReader(configPath)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains(configKey)) {
                            if (line.contains("true")) {
                                return true;
                            } else if (line.contains("false")) {
                                return false;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TrueAuth] Failed to read {} from config: {}", configKey, e.getMessage());
        }
        return defaultValue;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String requiredMod = MIXIN_MOD_REQUIREMENTS.get(mixinClassName);
        if (requiredMod != null) {
            boolean loaded = isModLoaded(requiredMod);
            if (!loaded) {
                LOGGER.info("[TrueAuth] Skipping {} (requires mod '{}' which is not loaded)", mixinClassName, requiredMod);
            }
            return loaded;
        }
        
        if (SKIP_WHEN_FFAPI_PRESENT.contains(mixinClassName) && ffapiPresent) {
            LOGGER.info("[TrueAuth] Skipping {} (FFAPI present, using Fabric API networking instead)", mixinClassName);
            return false;
        }
        
        if (REQUIRE_FFAPI.contains(mixinClassName) && !ffapiPresent) {
            LOGGER.info("[TrueAuth] Skipping {} (requires FFAPI which is not present)", mixinClassName);
            return false;
        }
        
        String requiredConfigKey = MIXIN_CONFIG_REQUIREMENTS.get(mixinClassName);
        if (requiredConfigKey != null) {
            boolean configValue = readConfigBoolean(requiredConfigKey, false);
            if (!configValue) {
                LOGGER.info("[TrueAuth] Skipping {} ({} is false in config)", mixinClassName, requiredConfigKey);
                return false;
            } else {
                LOGGER.info("[TrueAuth] {}", MIXIN_APPLY_MESSAGES.get(mixinClassName));
            }
        }
        
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
