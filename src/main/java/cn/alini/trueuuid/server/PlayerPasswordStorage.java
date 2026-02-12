package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPasswordStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(Trueauth.MODID);
    private static final String PASSWORD_FILE = ".private/offlineplayers/auth_hash.json";
    private static PlayerPasswordStorage instance;
    
    private final Path file;
    private final Gson gson = new Gson();
    private final Map<String, String> passwordCache = new ConcurrentHashMap<>();
    private long lastModified = -1;
    
    private PlayerPasswordStorage() {
        // Use the game directory for .private folder
        this.file = FMLPaths.GAMEDIR.get().resolve(PASSWORD_FILE);
        initFile();
        loadCache();
    }
    
    /**
     * Get the singleton instance
     * @return The PlayerPasswordStorage instance
     */
    public static synchronized PlayerPasswordStorage getInstance() {
        if (instance == null) {
            instance = new PlayerPasswordStorage();
        }
        return instance;
    }
    
    private void debug(String message, Object... args) {
        if (TrueauthConfig.debug()) {
            LOGGER.debug("[TrueAuth] " + message, args);
        }
    }
    
    /**
     * Initialize the password file with empty JSON object if it doesn't exist
     */
    private void initFile() {
        try {
            // Create parent directories if they don't exist
            Files.createDirectories(file.getParent());
            
            // Create empty JSON file if it doesn't exist
            if (!Files.exists(file)) {
                JsonObject empty = new JsonObject();
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    gson.toJson(empty, w);
                }
                LOGGER.info("[TrueAuth] Created password storage file: {}", file);
            }
        } catch (Exception ex) {
            LOGGER.warn("[TrueAuth] Failed to initialize password storage file: {} - {}", file, ex.getMessage());
        }
    }
    
    /**
     * Load the password cache from file
     */
    private void loadCache() {
        try {
            if (Files.exists(file)) {
                long currentModified = Files.getLastModifiedTime(file).toMillis();
                if (currentModified != lastModified) {
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                        passwordCache.clear();
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : o.entrySet()) {
                            passwordCache.put(entry.getKey(), entry.getValue().getAsString());
                        }
                        lastModified = currentModified;
                        debug("Loaded password cache with {} entries", passwordCache.size());
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[TrueAuth] Failed to load password cache from file: {} - {}", file, ex.getMessage());
        }
    }
    
    /**
     * Check if a player has a stored password
     * @param playerName The player name to check
     * @return true if the player has a stored password, false otherwise
     */
    public boolean hasStoredPassword(String playerName) {
        loadCache(); // Refresh cache if file changed
        return passwordCache.containsKey(playerName);
    }
    
    /**
     * Store a player's password hash
     * @param playerName The player name
     * @param passwordHash The password hash to store
     */
    public void storePassword(String playerName, String passwordHash) {
        try {
            // Update in-memory cache
            passwordCache.put(playerName, passwordHash);
            
            // Load existing data if file exists
            JsonObject o = new JsonObject();
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    o = JsonParser.parseReader(r).getAsJsonObject();
                }
            }
            
            // Store the password hash
            o.addProperty(playerName, passwordHash);
            
            // Save the updated data
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(o, w);
            }
            
            // Update last modified time
            if (Files.exists(file)) {
                lastModified = Files.getLastModifiedTime(file).toMillis();
            }
            
            debug("Stored password hash for player: {}", playerName);
        } catch (Exception ex) {
            LOGGER.warn("[TrueAuth] Failed to store password for player: {} - {}", playerName, ex.getMessage());
        }
    }
    
    /**
     * Get a player's stored password hash
     * @param playerName The player name
     * @return The stored password hash, or null if not found
     */
    public String getPasswordHash(String playerName) {
        loadCache(); // Refresh cache if file changed
        return passwordCache.get(playerName);
    }
    
    /**
     * Remove a player completely from the password storage
     * @param playerName The player name
     */
    public void removePlayer(String playerName) {
        try {
            // Remove from in-memory cache
            passwordCache.remove(playerName);
            
            // Load existing data if file exists
            JsonObject o = new JsonObject();
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    o = JsonParser.parseReader(r).getAsJsonObject();
                }
            }
            
            // Remove the player entry
            o.remove(playerName);
            
            // Save the updated data
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(o, w);
            }
            
            // Update last modified time
            if (Files.exists(file)) {
                lastModified = Files.getLastModifiedTime(file).toMillis();
            }
            
            debug("Removed player from password storage: {}", playerName);
        } catch (Exception ex) {
            LOGGER.warn("[TrueAuth] Failed to remove player from password storage: {} - {}", playerName, ex.getMessage());
        }
    }
}
