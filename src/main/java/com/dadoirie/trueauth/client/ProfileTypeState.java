package com.dadoirie.trueauth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Holds the premium/offline state for the current profile.
 */
@OnlyIn(Dist.CLIENT)
public final class ProfileTypeState {
    private static boolean isPremium = false;
    
    /**
     * Check if current profile is premium and update state.
     */
    public static void evaluate() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        String token = user.getAccessToken();
        
        if (token == null || token.isEmpty()) {
            isPremium = false;
            return;
        }
        
        try {
            var profile = user.getProfileId();
            String serverId = java.util.UUID.randomUUID().toString();
            mc.getMinecraftSessionService().joinServer(profile, token, serverId);
            var result = mc.getMinecraftSessionService().hasJoinedServer(user.getName(), serverId, null);
            isPremium = result != null;
        } catch (Throwable t) {
            isPremium = false;
        }
    }
    
    public static boolean isPremium() {
        return isPremium;
    }
    
    private ProfileTypeState() {}
}
