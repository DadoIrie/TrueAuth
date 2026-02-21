package com.dadoirie.trueauth.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Stores password data in NBT format.
 * 
 * Structure:
 * - users: CompoundTag
 *   - <username>: CompoundTag
 *     - serverPassword: String (default password for servers)
 *     - userPassword: String (user's password for shared computer scenario)
 *     - servers: CompoundTag (per-server passwords)
 *       - Each server hostname contains:
 *         - password: String
 *         - newPassword: String (nullable, only when changing password)
 */
@OnlyIn(Dist.CLIENT)
public class PasswordStorage {
    private static final String PASSWORD_FILE = ".private/trueauth/passwords.dat";
    
    /**
     * Save both server and user password for a specific user.
     * 
     * When saving serverPassword, also updates all server entries with newPassword.
     */
    public static void savePasswords(String username, String serverPassword, String userPassword) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        CompoundTag userData;
        if (users.contains(username)) {
            userData = users.getCompound(username);
        } else {
            userData = new CompoundTag();
            userData.putString("serverPassword", "");
            userData.putString("userPassword", "");
            userData.put("servers", new CompoundTag());
        }
        
        if (serverPassword != null && !serverPassword.isEmpty()) {
            String hashedPassword = hashPassword(serverPassword);
            userData.putString("serverPassword", hashedPassword);
            
            // Update all server entries with newPassword
            CompoundTag servers = userData.getCompound("servers");
            for (String serverHostname : servers.getAllKeys()) {
                CompoundTag serverData = servers.getCompound(serverHostname);
                serverData.putString("newPassword", hashedPassword);
                servers.put(serverHostname, serverData);
            }
            userData.put("servers", servers);
        }
        if (userPassword != null && !userPassword.isEmpty()) {
            userData.putString("userPassword", hashPassword(userPassword));
        }
        
        users.put(username, userData);
        root.put("users", users);
        save(root);
    }
    
    /**
     * Load the server password for a specific user.
     */
    public static String getServerPassword(String username) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            return userData.getString("serverPassword");
        }
        
        return "";
    }
    
    /**
     * Load the user password for a specific user.
     */
    public static String getUserPassword(String username) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            return userData.getString("userPassword");
        }
        
        return "";
    }
    
    /**
     * Check if a user exists in storage.
     */
    public static boolean userExists(String username) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        return users.contains(username);
    }
    
    /**
     * Get the password for a specific server for a specific user.
     * Falls back to serverPassword if no server-specific password exists.
     */
    public static String getPasswordForServer(String username, String serverHostname) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            CompoundTag servers = userData.getCompound("servers");
            
            if (servers.contains(serverHostname)) {
                CompoundTag serverData = servers.getCompound(serverHostname);
                return serverData.getString("password");
            }
            
            // Fall back to user's default server password
            return userData.getString("serverPassword");
        }
        
        return "";
    }
    
    /**
     * Set the password hash for a specific server for a specific user (already hashed).
     * Used when receiving password from server.
     */
    public static void setPasswordHashForServer(String username, String serverHostname, String passwordHash) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        CompoundTag userData;
        if (users.contains(username)) {
            userData = users.getCompound(username);
        } else {
            userData = new CompoundTag();
            userData.putString("serverPassword", "");
            userData.putString("userPassword", "");
            userData.put("servers", new CompoundTag());
        }
        
        CompoundTag servers = userData.getCompound("servers");
        CompoundTag serverData;
        if (servers.contains(serverHostname)) {
            serverData = servers.getCompound(serverHostname);
        } else {
            serverData = new CompoundTag();
        }
        
        serverData.putString("password", passwordHash);
        servers.put(serverHostname, serverData);
        userData.put("servers", servers);
        users.put(username, userData);
        root.put("users", users);
        save(root);
    }
    
    /**
     * Get the new password for a server for a specific user (if any).
     */
    public static String getNewPasswordForServer(String username, String serverHostname) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            CompoundTag servers = userData.getCompound("servers");
            
            if (servers.contains(serverHostname)) {
                CompoundTag serverData = servers.getCompound(serverHostname);
                return serverData.getString("newPassword");
            }
        }
        
        return "";
    }
    
    /**
     * Check if a server entry exists for a specific user.
     * TODO might be redundant
     */
    public static boolean hasServerEntry(String username, String serverHostname) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            CompoundTag servers = userData.getCompound("servers");
            return servers.contains(serverHostname);
        }
        
        return false;
    }
    
    /**
     * Confirm password change for a server for a specific user - remove newPassword.
     * The password field is already set by the server via AuthResultPayload.
     */
    public static void confirmPasswordChange(String username, String serverHostname) {
        CompoundTag root = loadOrCreate();
        CompoundTag users = root.getCompound("users");
        
        if (users.contains(username)) {
            CompoundTag userData = users.getCompound(username);
            CompoundTag servers = userData.getCompound("servers");
            
            if (servers.contains(serverHostname)) {
                CompoundTag serverData = servers.getCompound(serverHostname);
                serverData.remove("newPassword");
                servers.put(serverHostname, serverData);
                userData.put("servers", servers);
                users.put(username, userData);
                root.put("users", users);
                save(root);
            }
        }
    }
    
    // ! CRITICAL
    // ! This is for debugging only - prints the entire NBT structure unobfuscated
    // TODO Remove or comment this method before release build!
    public static void debugPrintAll() {
        CompoundTag root = loadOrCreate();
        System.out.println("[TrueAuth DEBUG] === Password Storage Contents ===");
        CompoundTag users = root.getCompound("users");
        for (String username : users.getAllKeys()) {
            System.out.println("[TrueAuth DEBUG] User: " + username);
            CompoundTag userData = users.getCompound(username);
            System.out.println("[TrueAuth DEBUG]   serverPassword: " + userData.getString("serverPassword"));
            System.out.println("[TrueAuth DEBUG]   userPassword: " + userData.getString("userPassword"));
            CompoundTag servers = userData.getCompound("servers");
            System.out.println("[TrueAuth DEBUG]   servers:");
            for (String hostname : servers.getAllKeys()) {
                System.out.println("[TrueAuth DEBUG]     " + hostname + ":");
                CompoundTag serverData = servers.getCompound(hostname);
                System.out.println("[TrueAuth DEBUG]       password: " + serverData.getString("password"));
                System.out.println("[TrueAuth DEBUG]       newPassword: " + serverData.getString("newPassword"));
            }
        }
        System.out.println("[TrueAuth DEBUG] === End Password Storage ===");
    }
    
    // === Internal methods ===
    
    private static CompoundTag loadOrCreate() {
        Path filePath = getFilePath();
        
        if (Files.exists(filePath)) {
            try {
                return NbtIo.readCompressed(filePath, NbtAccounter.unlimitedHeap());
            } catch (IOException e) {
                // If reading fails, create new
                System.err.println("[TrueAuth] Failed to read password file, creating new: " + e.getMessage());
            }
        }
        
        // Create new empty structure
        CompoundTag root = new CompoundTag();
        root.put("users", new CompoundTag());
        return root;
    }
    
    private static void save(CompoundTag root) {
        try {
            Path filePath = getFilePath();
            
            // Create directories if they don't exist
            Files.createDirectories(filePath.getParent());
            
            NbtIo.writeCompressed(root, filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static Path getFilePath() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        return Path.of(gameDir.getAbsolutePath(), PASSWORD_FILE);
    }
    
    static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes());
            
            // Convert byte array to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
