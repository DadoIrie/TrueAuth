package com.dadoirie.trueauth.mixin;

import com.dadoirie.trueauth.Trueauth;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConditionalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(Trueauth.MODID);
    
    private static final Map<String, String> MIXIN_MOD_REQUIREMENTS = Map.of(
        "client.AuthMethodScreenMixin", "authme"
    );

    private static boolean isModLoaded(String modId) {
        if (LoadingModList.get() != null) {
            return LoadingModList.get().getModFileById(modId) != null;
        }
        return false;
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
        String mixinName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        
        String requiredMod = MIXIN_MOD_REQUIREMENTS.get(mixinName);
        if (requiredMod == null) {
            return true;
        }
        
        boolean loaded = isModLoaded(requiredMod);
        if (!loaded) {
            LOGGER.info("[TrueAuth] Skipping {} (requires mod '{}' which is not loaded)", mixinName, requiredMod);
        }
        return loaded;
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
