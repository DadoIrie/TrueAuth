package com.dadoirie.trueauth;

import com.dadoirie.trueauth.client.PasswordPrompter;
import com.dadoirie.trueauth.net.FabricNetworkHandlerClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = Trueauth.MODID, dist = Dist.CLIENT)
public class TrueauthClient {
    public TrueauthClient(IEventBus modBus) {
        // Register the password prompter screen handler
        PasswordPrompter.register();
        
        // Initialize Fabric API client networking
        FabricNetworkHandlerClient.init();
    }
}
