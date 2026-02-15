package com.dadoirie.trueauth.net;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.client.PasswordStorage;
import com.dadoirie.trueauth.client.ServerAddressStorage;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.dadoirie.trueauth.server.AuthResultTask;
import com.dadoirie.trueauth.server.AuthState;
import net.minecraft.client.Minecraft;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Handles network registration for TrueAuth.
 */
@EventBusSubscriber(modid = Trueauth.MODID)
public final class NetworkHandler {
    
    @SubscribeEvent
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        
        // Register AuthResultPayload for CONFIGURATION phase (server -> client)
        registrar.configurationToClient(
            AuthResultPayload.TYPE,
            AuthResultPayload.STREAM_CODEC,
            (payload, context) -> {
                // Client-side handler - store the password hash for this server
                String serverAddress = ServerAddressStorage.get("current");
                String passwordHash = payload.passwordHash();
                
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] Received auth result packet from " + serverAddress + " with password hash");
                }
                
                // Store the password hash for this server and confirm password change
                if (serverAddress != null && passwordHash != null && !passwordHash.isEmpty()) {
                    String username = Minecraft.getInstance().getUser().getName();
                    PasswordStorage.setPasswordHashForServer(username, serverAddress, passwordHash);
                    PasswordStorage.confirmPasswordChange(username, serverAddress);
                    
                    // ! CRITICAL - remove or comment out for release builds - Debug: print entire password storage after success
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] === AFTER SUCCESS PACKET ===");
                        System.out.println("[TrueAuth] Server: " + serverAddress);
                        PasswordStorage.debugPrintAll();
                    }
                }
            }
        );
    }
    
    @SubscribeEvent
    private static void registerConfigurationTasks(RegisterConfigurationTasksEvent event) {
        var listener = event.getListener();
        
        // Check if this connection has a pending auth result
        String passwordHash = AuthState.getAndClearAuthResult(listener.getConnection());
        if (passwordHash != null) {
            // Put it back so the task can retrieve it
            AuthState.markAuthSuccess(listener.getConnection(), passwordHash);
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] Registering AuthResultTask with password hash");
            }
            event.register(new AuthResultTask((ServerConfigurationPacketListenerImpl) listener));
        }
    }
}
