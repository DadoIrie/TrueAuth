package com.dadoirie.trueauth.mixin.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.WhitelistCommand;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WhitelistCommand.class)
public class WhitelistCommandMixin {
    
    private static final SimpleCommandExceptionType ERROR_ALREADY_ENABLED = new SimpleCommandExceptionType(Component.translatable("commands.whitelist.alreadyOn"));
    private static final SimpleCommandExceptionType ERROR_ALREADY_DISABLED = new SimpleCommandExceptionType(Component.translatable("commands.whitelist.alreadyOff"));
    
    /**
     * Hook into enableWhitelist to enable our TrueAuth whitelist feature instead of Minecraft's
     */
    @Inject(method = "enableWhitelist", at = @At("HEAD"), cancellable = true)
    private static void trueauth$onEnableWhitelist(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) throws Exception {
        // Check if already enabled
        if (TrueauthConfig.whitelistEnabled()) {
            throw ERROR_ALREADY_ENABLED.create();
        } else {
            TrueauthConfig.COMMON.whitelistEnabled.set(true);
            TrueauthConfig.COMMON_SPEC.save();
            source.sendSuccess(() -> Component.translatable("commands.whitelist.enabled"), true);
        }
        cir.setReturnValue(1);
    }
    
    /**
     * Hook into disableWhitelist to disable our TrueAuth whitelist feature instead of Minecraft's
     */
    @Inject(method = "disableWhitelist", at = @At("HEAD"), cancellable = true)
    private static void trueauth$onDisableWhitelist(CommandSourceStack source, CallbackInfoReturnable<Integer> cir) throws Exception {
        // Check if already disabled
        if (!TrueauthConfig.whitelistEnabled()) {
            throw ERROR_ALREADY_DISABLED.create();
        } else {
            TrueauthConfig.COMMON.whitelistEnabled.set(false);
            TrueauthConfig.COMMON_SPEC.save();
            source.sendSuccess(() -> Component.translatable("commands.whitelist.disabled"), true);
        }
        cir.setReturnValue(1);
    }
}
