package com.dadoirie.trueauth.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;

import com.dadoirie.trueauth.config.TrueauthConfig;

/**
 * Event handler that automatically prompts for password when needed.
 * 
 * Only handles the automatic prompt case (user has no password).
 * Manual password changes calls PasswordScreen directly.
 */
@OnlyIn(Dist.CLIENT)
public final class PasswordPrompter {
    private static PasswordPrompter instance;
    
    public static PasswordPrompter getInstance() {
        if (instance == null) {
            instance = new PasswordPrompter();
        }
        return instance;
    }
    
    public static void register() {
        NeoForge.EVENT_BUS.register(getInstance());
    }
    
    private PasswordPrompter() {}
    
    @SubscribeEvent
    public void onScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen)) return;
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] JoinMultiplayerScreen opening");
            System.out.println("[TrueAuth] getCurrentScreen: " + (event.getCurrentScreen() != null ? event.getCurrentScreen().getClass().getSimpleName() : "null"));
        }
        
        String lastScreenClass = event.getCurrentScreen() != null ? event.getCurrentScreen().getClass().getName() : "";
        boolean isAuthMeScreen = lastScreenClass.equals("me.axieum.mcmod.authme.api.gui.screen.AuthMethodScreen") ||
                                  lastScreenClass.equals("me.axieum.mcmod.authme.api.gui.screen.MicrosoftAuthScreen") ||
                                  lastScreenClass.equals("me.axieum.mcmod.authme.api.gui.screen.OfflineAuthScreen");
        
        if (!(event.getCurrentScreen() instanceof SafetyScreen) && 
            !(event.getCurrentScreen() instanceof TitleScreen) && 
            !isAuthMeScreen) {
            return;
        }
        
        String currentUser = Minecraft.getInstance().getUser().getName();
        
        // Check if user exists in storage - if not, prompt for initial setup
        if (!PasswordStorage.userExists(currentUser)) {
            // ! CRITICAL - not for release builds - Debug: print entire password storage
            System.out.println("[TrueAuth] === PASSWORD PROMPTER - AUTO PROMPT ===");
            PasswordStorage.debugPrintAll();
            
            event.setNewScreen(PasswordScreen.getInstance());
            PasswordScreen.getInstance().openForInitialSetup(currentUser);
            return;
        }
        
        String userPassword = PasswordStorage.getUserPassword(currentUser);
        if (!userPassword.isEmpty() && !UserPasswordVerifyScreen.isVerified(currentUser)) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] User has userPassword set - showing verify screen");
            }
            
            event.setNewScreen(UserPasswordVerifyScreen.getInstance());
            UserPasswordVerifyScreen.getInstance().open(currentUser, (JoinMultiplayerScreen) event.getScreen());
        }
    }
}
