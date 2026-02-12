package com.dadoirie.trueauth.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public final class PasswordPrompter extends Screen {
    private static PasswordPrompter instance;
    private Screen parentScreen;
    private PasswordField passwordField;
    private PasswordField confirmPasswordField;
    private Button okButton;
    private Button toggleObfuscationButton;
    private boolean obfuscated = true;
    private String errorMessage = "";
    
    public static PasswordPrompter getInstance() {
        if (instance == null) {
            instance = new PasswordPrompter();
        }
        return instance;
    }
    
    public static void register() {
        NeoForge.EVENT_BUS.register(getInstance());
    }
    
    private PasswordPrompter() {
        super(Component.literal("Password Required"));
    }
    
    public void setParentScreen(Screen parent) {
        this.parentScreen = parent;
    }
    
    @SubscribeEvent
    public void onScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen)) return;
        
        if (isPremiumPlayer()) return;
        
        // Only show the password screen if the password file doesn't exist
        if (!PasswordStorage.passwordExists()) {
            clearFields();
            setParentScreen(event.getScreen());
            event.setNewScreen(this);
        }
    }
    
    private static boolean isPremiumPlayer() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        String token = user.getAccessToken();
        
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        try {
            var profile = user.getProfileId();
            String serverId = java.util.UUID.randomUUID().toString();
            mc.getMinecraftSessionService().joinServer(profile, token, serverId);
            var result = mc.getMinecraftSessionService().hasJoinedServer(user.getName(), serverId, null);
            return result != null;
        } catch (Throwable t) {
            return false;
        }
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
        
        // OK button
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
        
        // Toggle obfuscation button (eye icon)
        this.toggleObfuscationButton = Button.builder(
            Component.literal(this.obfuscated ? "§a👁" : "§c👁"),
            button -> this.toggleObfuscation()
        ).bounds(
            this.width / 2 + 100 - 20, // Right-aligned with the input fields
            this.height / 2 + 40,
            20,
            20
        ).build(b -> new Button(b) {
            @Override
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
                
                // Show tooltip when hovered
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
            this.title,
            this.width / 2,
            this.height / 2 - 80,
            0xFFFFFF
        );
        
        // Draw password field label
        guiGraphics.drawString(
            this.font,
            "Enter password:",
            this.width / 2 - 100,
            this.height / 2 - 55,
            0xA0A0A0
        );
        
        // Draw confirm password field label
        guiGraphics.drawString(
            this.font,
            "Confirm password:",
            this.width / 2 - 100,
            this.height / 2 - 5,
            0xA0A0A0
        );
        
        // Draw error message if any
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
    
    private void clearFields() {
        if (this.passwordField != null) {
            this.passwordField.setValue("");
        }
        if (this.confirmPasswordField != null) {
            this.confirmPasswordField.setValue("");
        }
        this.errorMessage = "";
    }
    
    private void onOkPressed() {
        String password = this.passwordField.getValue();
        String confirmPassword = this.confirmPasswordField.getValue();
        
        // Check if passwords are empty
        if (password.isEmpty() || confirmPassword.isEmpty()) {
            this.errorMessage = "Password cannot be empty!";
            return;
        }
        
        // Check minimum password length
        if (password.length() < 6) {
            this.errorMessage = "Password must be at least 6 characters!";
            return;
        }
        
        // Check if passwords match
        if (!password.equals(confirmPassword)) {
            this.errorMessage = "Passwords do not match!";
            return;
        }
        
        // Save the password
        PasswordStorage.savePassword(password);
        
        // Clear error message if passwords match
        this.errorMessage = "";
        
        // Close the screen
        this.minecraft.setScreen(this.parentScreen);
    }
    
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key: trigger OK action
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.onOkPressed();
            return true;
        }
        // ESC key: go to main menu (TitleScreen)
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.minecraft.setScreen(new TitleScreen());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
