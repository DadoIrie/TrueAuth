package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MinecraftWhitelistChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(Trueauth.MODID);
    private static final String WHITELIST_FILE = "whitelist.json";
    private static final String OPS_FILE = "ops.json";
    private static MinecraftWhitelistChecker instance;
    
    private final Path file;
    private final Path opsFile;
    private final Gson gson = new Gson();
    private final Set<String> whitelistCache = ConcurrentHashMap.newKeySet();
    private long lastModified = -1;
    private long opsLastModified = -1;
    
    private MinecraftWhitelistChecker() {
        // Use the game directory for whitelist.json and ops.json
        this.file = FMLPaths.GAMEDIR.get().resolve(WHITELIST_FILE);
        this.opsFile = FMLPaths.GAMEDIR.get().resolve(OPS_FILE);
        loadCache();
    }
    
    /**
     * Get the singleton instance
     * @return The MinecraftWhitelistChecker instance
     */
    public static synchronized MinecraftWhitelistChecker getInstance() {
        if (instance == null) {
            instance = new MinecraftWhitelistChecker();
        }
        return instance;
    }
    
    private void debug(String message, Object... args) {
        if (TrueauthConfig.debug()) {
            LOGGER.debug("[TrueAuth] " + message, args);
        }
    }
    
    /**
     * Load the whitelist cache from file
     */
    private void loadCache() {
        try {
            boolean shouldReload = false;
            
            // Check if whitelist.json has changed or needs initial load
            if (Files.exists(file)) {
                long currentModified = Files.getLastModifiedTime(file).toMillis();
                if (lastModified == -1 || currentModified != lastModified) {
                    shouldReload = true;
                }
            } else if (lastModified != -1) {
                shouldReload = true; // File was deleted
            }
            
            // Check if ops.json has changed or needs initial load
            if (Files.exists(opsFile)) {
                long currentModified = Files.getLastModifiedTime(opsFile).toMillis();
                if (opsLastModified == -1 || currentModified != opsLastModified) {
                    shouldReload = true;
                }
            } else if (opsLastModified != -1) {
                shouldReload = true; // File was deleted
            }
            
            // If either file changed, reload both
            if (shouldReload) {
                whitelistCache.clear();
                
                // Load whitelist.json
                if (Files.exists(file)) {
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        JsonArray whitelistArray = JsonParser.parseReader(r).getAsJsonArray();
                        for (JsonElement element : whitelistArray) {
                            if (element.isJsonObject()) {
                                JsonObject entry = element.getAsJsonObject();
                                if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
                                    whitelistCache.add(entry.get("name").getAsString());
                                }
                            }
                        }
                        lastModified = Files.getLastModifiedTime(file).toMillis();
                        debug("Loaded Minecraft whitelist cache with {} entries", whitelistCache.size());
                    }
                }
                
                // Load ops.json and add operators to whitelist
                if (Files.exists(opsFile)) {
                    try (Reader r = Files.newBufferedReader(opsFile, StandardCharsets.UTF_8)) {
                        JsonArray opsArray = JsonParser.parseReader(r).getAsJsonArray();
                        for (JsonElement element : opsArray) {
                            if (element.isJsonObject()) {
                                JsonObject entry = element.getAsJsonObject();
                                if (entry.has("name") && entry.get("name").isJsonPrimitive()) {
                                    whitelistCache.add(entry.get("name").getAsString());
                                }
                            }
                        }
                        opsLastModified = Files.getLastModifiedTime(opsFile).toMillis();
                        debug("Loaded Minecraft ops cache, total whitelisted players: {}", whitelistCache.size());
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[TrueAuth] Failed to load Minecraft whitelist/ops cache: {}", ex.getMessage());
        }
    }
    
    /**
     * Check if a player is whitelisted
     * @param playerName The player name to check
     * @return true if the player is whitelisted, false otherwise
     */
    public boolean isWhitelisted(String playerName) {
        loadCache(); // Refresh cache if file changed
        return whitelistCache.contains(playerName);
    }
}
