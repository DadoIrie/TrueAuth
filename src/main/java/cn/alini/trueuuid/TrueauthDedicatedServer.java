package com.dadoirie.trueauth;

import com.dadoirie.trueauth.server.PlayerPasswordStorage;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = Trueauth.MODID, dist = Dist.DEDICATED_SERVER)
public class TrueauthDedicatedServer {
    public TrueauthDedicatedServer(IEventBus modBus) {
        // Initialize password storage for dedicated server
        PlayerPasswordStorage.getInstance();
    }
}
