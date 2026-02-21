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
            String realValue = this.getValue();
            
            String obfuscatedValue = "*".repeat(realValue.length());
            this.setValue(obfuscatedValue);
            
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            
            this.setValue(realValue);
        } else {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        }
    }
}
