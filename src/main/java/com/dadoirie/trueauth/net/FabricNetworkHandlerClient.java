package com.dadoirie.trueauth.net;

import com.dadoirie.trueauth.client.PasswordStorage;
import com.dadoirie.trueauth.client.ServerAddressStorage;
import com.dadoirie.trueauth.config.TrueauthConfig;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Client-side Fabric API networking handler for TrueAuth.
 */
public final class FabricNetworkHandlerClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("TrueAuth");
    
    private FabricNetworkHandlerClient() {}
    
    public static void init() {
        ClientLoginNetworking.registerGlobalReceiver(NetIds.AUTH, FabricNetworkHandlerClient::handleAuthQuery);
        ClientLoginNetworking.registerGlobalReceiver(NetIds.AUTH_RESULT, FabricNetworkHandlerClient::handleAuthResult);
        LOGGER.info("[TrueAuth] Registered Fabric API client login networking");
    }
    
    /**
     * First query: Handle session auth (joinServer) and send password.
     */
    private static CompletableFuture<FriendlyByteBuf> handleAuthQuery(
            Minecraft client,
            ClientHandshakePacketListenerImpl handler,
            FriendlyByteBuf buf,
            Consumer<PacketSendListener> callbacksConsumer
    ) {
        String nonce = buf.readUtf();
        boolean skipMojang = buf.readBoolean();
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: received auth query with nonce=" + nonce + ", skipMojang=" + skipMojang);
        
        User user = client.getUser();
        UUID profileId = user.getProfileId();
        String token = user.getAccessToken();
        String profileName = user.getName();
        
        String hostname = ServerAddressStorage.get("current");
        
        return CompletableFuture.supplyAsync(() -> {
            boolean ok;
            if (skipMojang) {
                // Server signaled nomojang mode - skip joinServer call
                ok = false;
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: server signaled nomojang mode, skipping Mojang auth");
            } else {
                ok = false;
                try {
                    client.getMinecraftSessionService().joinServer(profileId, token, nonce);
                    ok = true;
                    if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: joinServer SUCCESS for profile=" + profileName);
                } catch (Exception e) {
                    if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: joinServer FAILED for profile=" + profileName + " " + e.getMessage());
                }
            }
            
            String passwordHash = PasswordStorage.getPasswordForServer(profileName, hostname);
            String newPasswordHash = PasswordStorage.getNewPasswordForServer(profileName, hostname);
            
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] CLIENT: hasNewPassword=" + !newPasswordHash.isEmpty());
            }
                    
            // ! CRITICAL - Debug: print password storage before sending password
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] CLIENT: === BEFORE SENDING PASSWORD ===");
                PasswordStorage.debugPrintAll();
            }

            FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
            response.writeBoolean(ok);
            response.writeUtf(passwordHash);
            response.writeUtf(newPasswordHash);
            return response;
        });
    }

    
    /**
     * Second query: Handle auth result from server.
     * Server sends password hash and passwordChanged flag.
     * Client stores password and sends back hash for verification.
     */
    private static CompletableFuture<FriendlyByteBuf> handleAuthResult(
            Minecraft client,
            ClientHandshakePacketListenerImpl handler,
            FriendlyByteBuf buf,
            Consumer<PacketSendListener> callbacksConsumer
    ) {
        String passwordHash = buf.readUtf();
        boolean passwordChanged = buf.readBoolean();
        
        String hostname = ServerAddressStorage.get("current");
        String profileName = client.getUser().getName();
        
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] CLIENT: received auth result, passwordChanged=" + passwordChanged + ", hostname=" + hostname);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            if (hostname != null && passwordHash != null && !passwordHash.isEmpty()) {
                // Store the password hash for this server
                PasswordStorage.setPasswordHashForServer(profileName, hostname, passwordHash);
                
                // If password was changed, confirm the change
                if (passwordChanged) {
                    PasswordStorage.confirmPasswordChange(profileName, hostname);
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] CLIENT: password change confirmed for " + profileName + " on " + hostname);
                    }
                }
            }
            
            // ! CRITICAL - Debug: print password storage after processing auth result
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] CLIENT: === AFTER PROCESSING AUTH RESULT ===");
                PasswordStorage.debugPrintAll();
            }
            
            // Send back the password hash for server verification
            FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
            if (!passwordHash.isEmpty()) response.writeUtf(passwordHash);
            return response;
        });
    }
}
