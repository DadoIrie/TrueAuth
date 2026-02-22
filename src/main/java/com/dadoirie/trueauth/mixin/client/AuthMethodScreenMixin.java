package com.dadoirie.trueauth.mixin.client;

import com.dadoirie.trueauth.client.PasswordScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ImageButton;
// import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.axieum.mcmod.authme.api.gui.screen.AuthMethodScreen;

@Mixin(AuthMethodScreen.class)
public abstract class AuthMethodScreenMixin extends Screen {
    @Shadow(remap = false) @Final public static WidgetSprites MICROSOFT_BUTTON_TEXTURES;
    @Shadow(remap = false) @Final public static WidgetSprites MOJANG_BUTTON_TEXTURES;
    
    @Unique private static final WidgetSprites TRUEAUTH_BUTTON_TEXTURES = new WidgetSprites(
        ResourceLocation.parse("widget/locked_button"),
        ResourceLocation.parse("widget/locked_button_disabled"),
        ResourceLocation.parse("widget/locked_button_highlighted")
    );
    
    @Shadow(remap = false) @Final private Screen parentScreen;
    
    protected AuthMethodScreenMixin(Component title) {
        super(title);
    }
    
    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void trueauth$onInit(CallbackInfo ci) {
        AuthMethodScreen self = (AuthMethodScreen) (Object) this;
        
        var children = this.children();
        ImageButton mojangButtonToRemove = null;
        ImageButton offlineButtonToMove = null;
        
        for (var child : children) {
            if (child instanceof ImageButton button) {
                if (button.getX() == this.width / 2 - 10) {
                    mojangButtonToRemove = button;
                }
                if (button.getX() == this.width / 2 + 14) {
                    offlineButtonToMove = button;
                }
            }
        }
        
        if (mojangButtonToRemove != null) {
            this.removeWidget(mojangButtonToRemove);
        }
        
        if (offlineButtonToMove != null) {
            offlineButtonToMove.setPosition(this.width / 2 - 10, this.height / 2 - 5);
        }
        
        ImageButton trueauthButton = new ImageButton(
            this.width / 2 + 14, this.height / 2 - 5, 20, 20,
            TRUEAUTH_BUTTON_TEXTURES,
            button -> {
                String username = Minecraft.getInstance().getUser().getName();
                PasswordScreen.getInstance().open(username, self);
            },
            Component.literal("TrueAuth")
        );
        trueauthButton.setTooltip(Tooltip.create(
            Component.literal("Set TrueAuth Password")
                .append("\n")
                .append(
                    Component.literal("Configure server and user passwords")
                        .withStyle(ChatFormatting.GRAY)
                )
        ));
        this.addRenderableWidget(trueauthButton);
        
        // TODO account switching
        /* SpriteIconButton switchProfileButton = SpriteIconButton.builder(
            Component.literal("Accessibility"),
            button -> {
                System.out.println("[TrueAuth] Accessibility button clicked!");
            },
            true
        )
            .width(20)
            .sprite(ResourceLocation.withDefaultNamespace("icon/accessibility"), 15, 15)
            .build();
        switchProfileButton.setPosition(this.width / 2 + 38, this.height / 2 - 5);
        switchProfileButton.setTooltip(Tooltip.create(Component.literal("Change Profile")));
        this.addRenderableWidget(switchProfileButton); */
    }
}
