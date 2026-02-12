package com.dadoirie.trueauth.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PasswordField extends EditBox {
    private boolean obfuscated = true;
    
    public PasswordField(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
    }
    
    public void setObfuscated(boolean obfuscated) {
        this.obfuscated = obfuscated;
    }
    
    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.obfuscated) {
            // Store the original value
            String realValue = this.getValue();
            
            // Temporarily replace the value with asterisks for rendering
            String obfuscatedValue = "*".repeat(realValue.length());
            this.setValue(obfuscatedValue);
            
            // Render the obfuscated text
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            
            // Restore the original value
            this.setValue(realValue);
        } else {
            // Render normally without obfuscation
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }
    }
}
