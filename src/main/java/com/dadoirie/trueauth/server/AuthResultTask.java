package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.net.AuthResultPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;

import java.util.function.Consumer;

/**
 * Configuration task that sends the auth result to the client.
 * This runs during the CONFIGURATION phase (after LOGIN, before player spawn).
 */
public record AuthResultTask(ServerConfigurationPacketListenerImpl listener) implements ICustomConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(
        ResourceLocation.fromNamespaceAndPath(Trueauth.MODID, "auth_result")
    );
    
    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        // Check if this connection has a pending auth result
        String passwordHash = AuthState.getAndClearAuthResult(listener.getConnection());
        if (passwordHash != null) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] Sending auth result packet with password hash");
            }
            sender.accept(new AuthResultPayload(passwordHash));
        }
        listener.finishCurrentTask(TYPE);
    }
    
    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
