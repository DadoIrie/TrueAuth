package com.dadoirie.trueauth.client;

import java.util.Locale;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Screen for verifying userPassword before allowing access to multiplayer.
 * 
 * Shown when:
 * - User has a userPassword set
 * - User is trying to access JoinMultiplayerScreen
 * 
 * Has:
 * - 1 password input field
 * - Obfuscate/deobfuscate button on the right
 * - Cancel button (returns to TitleScreen)
 * - OK button (verifies password)
 * 
 * ESC also returns to TitleScreen.
 * 
 * Singleton to avoid memory leaks.
 */
@OnlyIn(Dist.CLIENT)
public final class UserPasswordVerifyScreen extends Screen {
    private static UserPasswordVerifyScreen instance;
    private static String verifiedUsername = null;
    
    private String username;
    private JoinMultiplayerScreen targetScreen;
    
    /**
     * Check if the given username has been verified for this session.
     */
    public static boolean isVerified(String username) {
        return username != null && username.toLowerCase(Locale.ROOT).equals(verifiedUsername);
    }
    
    private PasswordField passwordField;
    private Button okButton;
    private Button cancelButton;
    private Button toggleObfuscationButton;
    
    private boolean obfuscated;
    private String errorMessage;
    
    public static UserPasswordVerifyScreen getInstance() {
        if (instance == null) {
            instance = new UserPasswordVerifyScreen();
        }
        return instance;
    }
    
    private UserPasswordVerifyScreen() {
        super(Component.literal("User Password Required"));
        reset();
    }
    
    /**
     * Reset all state to defaults.
     */
    private void reset() {
        this.username = "";
        this.targetScreen = null;
        this.obfuscated = true;
        this.errorMessage = "";
    }
    
    /**
     * Open the screen for userPassword verification.
     * 
     * @param username The username to verify
     * @param targetScreen The JoinMultiplayerScreen to open on success
     */
    public void open(String username, JoinMultiplayerScreen targetScreen) {
        reset();
        this.username = username;
        this.targetScreen = targetScreen;
        
        Minecraft.getInstance().setScreen(this);
    }
    
    @Override
    protected void init() {
        super.init();
        
        // Password field
        this.passwordField = new PasswordField(
            this.font,
            this.width / 2 - 100,
            this.height / 2 - 20,
            200,
            20,
            Component.literal("Password")
        );
        this.passwordField.setMaxLength(32);
        this.addRenderableWidget(this.passwordField);
        
        // Cancel button
        this.cancelButton = Button.builder(
            Component.literal("Cancel"),
            button -> this.onCancel()
        ).bounds(
            this.width / 2 - 100,
            this.height / 2 + 20,
            95,
            20
        ).build();
        this.addRenderableWidget(this.cancelButton);
        
        // OK button
        this.okButton = Button.builder(
            Component.literal("OK"),
            button -> this.onOkPressed()
        ).bounds(
            this.width / 2 + 5,
            this.height / 2 + 20,
            95,
            20
        ).build();
        this.addRenderableWidget(this.okButton);
        
        // Toggle obfuscation button (eye icon)
        this.toggleObfuscationButton = Button.builder(
            Component.literal(this.obfuscated ? "§a👁" : "§c👁"),
            button -> this.toggleObfuscation()
        ).bounds(
            this.width / 2 + 100 + 5,
            this.height / 2 - 20,
            20,
            20
        ).build(b -> new Button(b) {
            @Override
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
                if (this.isHovered) {
                    guiGraphics.renderTooltip(
                        font,
                        Component.literal(obfuscated ? "Show Password" : "Hide Password"),
                        mouseX,
                        mouseY
                    );
                }
            }
        });
        this.addRenderableWidget(this.toggleObfuscationButton);
    }
    
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        // Draw title
        guiGraphics.drawCenteredString(
            this.font,
            "Enter User Password for " + this.username,
            this.width / 2,
            this.height / 2 - 60,
            0xFFFFFF
        );
        
        // Draw label
        guiGraphics.drawString(
            this.font,
            "Password:",
            this.width / 2 - 100,
            this.height / 2 - 35,
            0xA0A0A0
        );
        
        // Draw error message
        if (!this.errorMessage.isEmpty()) {
            guiGraphics.drawString(
                this.font,
                this.errorMessage,
                this.width / 2 - 100,
                this.height / 2 - 50,
                0xFF0000
            );
        }
    }
    
    private void toggleObfuscation() {
        this.obfuscated = !this.obfuscated;
        this.passwordField.setObfuscated(this.obfuscated);
        this.toggleObfuscationButton.setMessage(Component.literal(this.obfuscated ? "§a👁" : "§c👁"));
    }
    
    private void onOkPressed() {
        String password = this.passwordField.getValue();
        
        if (password.isEmpty()) {
            this.errorMessage = "Password cannot be empty!";
            return;
        }
        
        // Hash the entered password and compare with stored userPassword
        String hashedPassword = PasswordStorage.hashPassword(password);
        String storedPassword = PasswordStorage.getUserPassword(this.username);
        
        if (hashedPassword.equals(storedPassword)) {
            // Password matches - mark this user as verified and proceed to multiplayer screen
            verifiedUsername = this.username.toLowerCase(Locale.ROOT);
            this.minecraft.setScreen(this.targetScreen);
        } else {
            // Password doesn't match
            this.errorMessage = "Incorrect password!";
            this.passwordField.setValue("");
        }
    }
    
    private void onCancel() {
        this.minecraft.setScreen(new TitleScreen());
    }
    
    @Override
    public void onClose() {
        // ESC returns to TitleScreen
        this.minecraft.setScreen(new TitleScreen());
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.onOkPressed();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
