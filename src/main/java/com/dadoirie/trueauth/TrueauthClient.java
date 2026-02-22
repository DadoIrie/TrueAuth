package com.dadoirie.trueauth;

import com.dadoirie.trueauth.client.PasswordPrompter;
import com.dadoirie.trueauth.net.FabricNetworkHandlerClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.LoadingModList;

@Mod(value = Trueauth.MODID, dist = Dist.CLIENT)
public class TrueauthClient {
    
    private static boolean isFabricApiPresent() {
        return LoadingModList.get() != null && 
            LoadingModList.get().getModFileById("fabric_networking_api_v1") != null;
    }
    
    public TrueauthClient(IEventBus modBus) {
        PasswordPrompter.register();
        
        if (isFabricApiPresent()) {
            FabricNetworkHandlerClient.init();
        }
    }
}
