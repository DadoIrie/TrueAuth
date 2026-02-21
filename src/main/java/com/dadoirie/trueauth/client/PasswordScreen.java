package com.dadoirie.trueauth.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * Pure UI screen for password input.
 * 
 * Has two modes:
 * - SERVER_PASSWORD: Shows lock button to set userPassword
 * - USER_PASSWORD: No lock button, for setting userPassword only
 * 
 * ESC/OK behavior:
 * - If parentScreen is null: goes to TitleScreen (automatic prompt case)
 * - If parentScreen is set: goes to parentScreen (manual click case)
 * 
 * Singleton to avoid memory leaks.
 */
@OnlyIn(Dist.CLIENT)
public final class PasswordScreen extends Screen {
    private static PasswordScreen instance;
    
    /** Mode for the password screen */
    public enum Mode {
        SERVER_PASSWORD,  // Setting serverPassword (shows lock button)
        USER_PASSWORD     // Setting userPassword (no lock button)
    }
    
    // Vanilla widget sprites for the lock button
    private static final WidgetSprites LOCK_BUTTON_TEXTURES = new WidgetSprites(
        ResourceLocation.parse("widget/locked_button"),
        ResourceLocation.parse("widget/locked_button_disabled"),
        ResourceLocation.parse("widget/locked_button_highlighted")
    );
    
    // State
    private Mode mode;
    private String username;
    private Screen parentScreen;
    private String tempUserPassword;
    
    // UI components
    private PasswordField passwordField;
    private PasswordField confirmPasswordField;
    private Button okButton;
    private Button toggleObfuscationButton;
    private ImageButton lockButton;
    private boolean obfuscated;
    private String errorMessage;
    
    public static PasswordScreen getInstance() {
        if (instance == null) {
            instance = new PasswordScreen();
        }
        return instance;
    }
    
    private PasswordScreen() {
        super(Component.literal("Password Required"));
        reset();
    }
    
    /**
     * Reset all state to defaults.
     */
    private void reset() {
        this.mode = Mode.SERVER_PASSWORD;
        this.username = "";
        this.parentScreen = null;
        this.tempUserPassword = "";
        this.obfuscated = true;
        this.errorMessage = "";
    }
    
    /**
     * Open the screen for SERVER_PASSWORD mode.
     * 
     * @param username The username to set passwords for
     * @param parentScreen The parent screen to return to on ESC/OK (null → TitleScreen)
     */
    public void open(String username, Screen parentScreen) {
        reset();
        this.mode = Mode.SERVER_PASSWORD;
        this.username = username;
        this.parentScreen = parentScreen;
        
        // ! CRITICAL - not for release builds - Debug output
        System.out.println("[TrueAuth] === PASSWORD SCREEN OPENED ===");
        PasswordStorage.debugPrintAll();
        
        Minecraft.getInstance().setScreen(this);
    }
    
    /**
     * Open the screen for initial setup (automatic prompt, no password exists).
     * ESC/OK will go to TitleScreen.
     */
    public void openForInitialSetup(String username) {
        open(username, null);
    }
    
    /**
     * Open the screen for USER_PASSWORD mode (called from lock button).
     * Does NOT reset - preserves parent reference.
     */
    private void openForUserPassword(String tempUserPassword) {
        // Don't call reset() - we need to preserve the current state
        this.mode = Mode.USER_PASSWORD;
        this.tempUserPassword = tempUserPassword != null ? tempUserPassword : "";
        this.errorMessage = "";
        this.passwordField = null;
        this.confirmPasswordField = null;
        
        // Re-initialize the screen with new mode
        this.clearWidgets();
        this.init();
    }
    
    @Override
    protected void init() {
        super.init();
        
        // Password field
        this.passwordField = new PasswordField(
            this.font,
            this.width / 2 - 100,
            this.height / 2 - 40,
            200,
            20,
            Component.literal("Password")
        );
        this.passwordField.setMaxLength(32);
        this.addRenderableWidget(this.passwordField);
        
        // Confirm password field
        this.confirmPasswordField = new PasswordField(
            this.font,
            this.width / 2 - 100,
            this.height / 2 + 10,
            200,
            20,
            Component.literal("Confirm Password")
        );
        this.confirmPasswordField.setMaxLength(32);
        this.addRenderableWidget(this.confirmPasswordField);
        
        if (this.mode == Mode.SERVER_PASSWORD) {
            this.lockButton = new ImageButton(
                this.width / 2 - 100,
                this.height / 2 + 40,
                20,
                20,
                LOCK_BUTTON_TEXTURES,
                button -> this.onLockPressed(),
                Component.literal("Set User Password")
            );
            this.lockButton.setTooltip(Tooltip.create(
                Component.literal("Set User Password")
            ));
            this.addRenderableWidget(this.lockButton);
        }
        
        this.okButton = Button.builder(
            Component.literal("OK"),
            button -> this.onOkPressed()
        ).bounds(
            this.width / 2 - 50,
            this.height / 2 + 40,
            100,
            20
        ).build();
        this.addRenderableWidget(this.okButton);
        
        this.toggleObfuscationButton = Button.builder(
            Component.literal(this.obfuscated ? "§a👁" : "§c👁"),
            button -> this.toggleObfuscation()
        ).bounds(
            this.width / 2 + 80,
            this.height / 2 + 40,
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
        
        // Draw title based on mode
        String titleText = this.mode == Mode.SERVER_PASSWORD 
            ? "Server Password for " + this.username
            : "User Password for " + this.username;
        guiGraphics.drawCenteredString(
            this.font,
            titleText,
            this.width / 2,
            this.height / 2 - 80,
            0xFFFFFF
        );
        
        // Draw labels
        guiGraphics.drawString(
            this.font,
            "Enter password:",
            this.width / 2 - 100,
            this.height / 2 - 55,
            0xA0A0A0
        );
        guiGraphics.drawString(
            this.font,
            "Confirm password:",
            this.width / 2 - 100,
            this.height / 2 - 5,
            0xA0A0A0
        );
        
        // Draw error message
        if (!this.errorMessage.isEmpty()) {
            guiGraphics.drawString(
                this.font,
                this.errorMessage,
                this.width / 2 - 100,
                this.height / 2 - 70,
                0xFF0000
            );
        }
    }
    
    private void toggleObfuscation() {
        this.obfuscated = !this.obfuscated;
        this.passwordField.setObfuscated(this.obfuscated);
        this.confirmPasswordField.setObfuscated(this.obfuscated);
        this.toggleObfuscationButton.setMessage(Component.literal(this.obfuscated ? "§a👁" : "§c👁"));
    }
    
    /**
     * Called when lock button is pressed - switches to USER_PASSWORD mode.
     */
    private void onLockPressed() {
        openForUserPassword(this.tempUserPassword);
    }
    
    private void onOkPressed() {
        String password = this.passwordField.getValue();
        String confirmPassword = this.confirmPasswordField.getValue();
        
        // In SERVER_PASSWORD mode, if tempUserPassword is set and fields are empty,
        // only update userPassword without validating server password
        if (this.mode == Mode.SERVER_PASSWORD && !this.tempUserPassword.isEmpty() 
            && password.isEmpty() && confirmPassword.isEmpty()) {
            PasswordStorage.savePasswords(this.username, null, this.tempUserPassword);
            closeScreen();
            return;
        }
        
        // Validate password fields
        if (password.isEmpty() || confirmPassword.isEmpty()) {
            this.errorMessage = "Password cannot be empty!";
            return;
        }
        
        if (password.length() < 6) {
            this.errorMessage = "Password must be at least 6 characters!";
            return;
        }
        
        if (!password.equals(confirmPassword)) {
            this.errorMessage = "Passwords do not match!";
            return;
        }
        
        this.errorMessage = "";
        
        if (this.mode == Mode.USER_PASSWORD) {
            // Store userPassword and switch back to SERVER_PASSWORD mode
            this.tempUserPassword = password;
            switchToServerPasswordMode();
        } else {
            PasswordStorage.savePasswords(this.username, password, this.tempUserPassword);
            Minecraft.getInstance().setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }
    }
    
    /**
     * Switch back to SERVER_PASSWORD mode after setting userPassword.
     */
    private void switchToServerPasswordMode() {
        this.mode = Mode.SERVER_PASSWORD;
        this.errorMessage = "";
        this.passwordField = null;
        this.confirmPasswordField = null;
        
        // Re-initialize the screen with SERVER_PASSWORD mode
        this.clearWidgets();
        this.init();
    }
    
    @Override
    public void onClose() {
        if (this.mode == Mode.USER_PASSWORD) {
            // Clear temp password and switch back to SERVER_PASSWORD mode
            this.tempUserPassword = "";
            switchToServerPasswordMode();
        } else {
            closeScreen();
        }
    }
    
    /**
     * Close the screen - go to parent or TitleScreen.
     */
    private void closeScreen() {
        if (this.parentScreen != null) {
            Minecraft.getInstance().setScreen(this.parentScreen);
        } else {
            Minecraft.getInstance().setScreen(new TitleScreen());
        }
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
