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
        ClientLoginNetworking.registerGlobalReceiver(NetIds.PASSWORD, FabricNetworkHandlerClient::handlePasswordQuery);
        ClientLoginNetworking.registerGlobalReceiver(NetIds.AUTH_RESULT, FabricNetworkHandlerClient::handleAuthResult);
        LOGGER.info("[TrueAuth] Registered Fabric API client login networking");
    }
    
    /**
     * First query: Handle session auth (joinServer).
     */
    private static CompletableFuture<FriendlyByteBuf> handleAuthQuery(
            Minecraft client,
            ClientHandshakePacketListenerImpl handler,
            FriendlyByteBuf buf,
            Consumer<PacketSendListener> callbacksConsumer
    ) {
        String nonce = buf.readUtf();
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: received auth query with nonce=" + nonce);
        
        User user = client.getUser();
        UUID profileId = user.getProfileId();
        String token = user.getAccessToken();
        String profileName = user.getName();
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: initiating joinServer for profile=" + profileName);
        
        return CompletableFuture.supplyAsync(() -> {
            boolean ok = false;
            try {
                client.getMinecraftSessionService().joinServer(profileId, token, nonce);
                ok = true;
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: joinServer SUCCESS for profile=" + profileName);
            } catch (Exception e) {
                if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: joinServer FAILED for profile=" + profileName + " " + e.getMessage());
            }
            
            FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
            response.writeBoolean(ok);
            return response;
        });
    }
    
    /**
     * Second query: Handle password request (only for offline players).
     * Server requests password, client responds with stored password hash.
     */
    private static CompletableFuture<FriendlyByteBuf> handlePasswordQuery(
            Minecraft client,
            ClientHandshakePacketListenerImpl handler,
            FriendlyByteBuf buf,
            Consumer<PacketSendListener> callbacksConsumer
    ) {
        User user = client.getUser();
        String profileName = user.getName();
        
        // Get hostname from ConnectScreenMixin storage
        String hostname = ServerAddressStorage.get("current");
        
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] CLIENT: received password query, hostname=" + hostname);
        
        // ! CRITICAL - Debug: print password storage before sending password
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] CLIENT: === BEFORE SENDING PASSWORD ===");
            PasswordStorage.debugPrintAll();
        }
        
        return CompletableFuture.supplyAsync(() -> {
            String passwordHash = "";
            String newPasswordHash = "";
            
            if (hostname != null) {
                // Get stored password for this server
                passwordHash = PasswordStorage.getPasswordForServer(profileName, hostname);
                
                // Check if there's a pending password change
                newPasswordHash = PasswordStorage.getNewPasswordForServer(profileName, hostname);
                
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] CLIENT: password found=" + !passwordHash.isEmpty() + ", hasNewPassword=" + !newPasswordHash.isEmpty());
                }
            }
            
            FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
            response.writeBoolean(!passwordHash.isEmpty()); // hasPassword
            response.writeUtf(passwordHash.isEmpty() ? "" : passwordHash); // passwordHash
            response.writeBoolean(!newPasswordHash.isEmpty()); // hasNewPassword
            response.writeUtf(newPasswordHash.isEmpty() ? "" : newPasswordHash); // newPasswordHash
            return response;
        });
    }
    
    /**
     * Third query: Handle auth result from server.
     * Server sends password hash and passwordChanged flag.
     * Client stores password and confirms change if needed.
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
            
            // Send empty response to acknowledge receipt
            return new FriendlyByteBuf(Unpooled.buffer());
        });
    }
}
