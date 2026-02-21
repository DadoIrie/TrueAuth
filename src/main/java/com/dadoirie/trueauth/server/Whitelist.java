package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.Trueauth;
import com.dadoirie.trueauth.config.TrueauthConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;

public class Whitelist {
    private static final Logger LOGGER = LoggerFactory.getLogger(Trueauth.MODID);
    private static final String WHITELIST_FILE = "trueauth_whitelist.json";
    private static Whitelist instance;
    
    public static class Entry {
        public final String name;
        public final boolean premiumOnly;
        
        public Entry(String name, boolean premiumOnly) {
            this.name = name;
            this.premiumOnly = premiumOnly;
        }
    }
    
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Entry> whitelistCache = new ArrayList<>();
    private long lastModified = -1;
    
    private Whitelist() {
        this.file = FMLPaths.GAMEDIR.get().resolve(".private/trueauth/" + WHITELIST_FILE);
        load();
    }
    
    public static synchronized Whitelist getInstance() {
        if (instance == null) {
            instance = new Whitelist();
        }
        return instance;
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                long currentModified = Files.getLastModifiedTime(file).toMillis();
                if (lastModified == -1 || currentModified != lastModified) {
                    whitelistCache.clear();
                    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        JsonArray array = JsonParser.parseReader(r).getAsJsonArray();
                        for (int i = 0; i < array.size(); i++) {
                            JsonObject obj = array.get(i).getAsJsonObject();
                            String name = obj.get("name").getAsString();
                            boolean premiumOnly = obj.has("premiumOnly") && obj.get("premiumOnly").getAsBoolean();
                            whitelistCache.add(new Entry(name, premiumOnly));
                        }
                    }
                    lastModified = currentModified;
                    if (TrueauthConfig.debug()) System.out.println("[TrueAuth] Whitelist loaded from disk, entries: " + whitelistCache.size());
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[TrueAuth] Failed to load whitelist", ex);
        }
    }

    private void saveAsync() {
        new Thread(this::save, "TrueAuth-WhitelistSave").start();
    }
    
    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonArray array = new JsonArray();
            for (Entry entry : whitelistCache) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", entry.name);
                obj.addProperty("premiumOnly", entry.premiumOnly);
                array.add(obj);
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(array, w);
            }
            lastModified = Files.getLastModifiedTime(file).toMillis();
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] Whitelist saved to disk, entries: " + whitelistCache.size());
        } catch (Exception ex) {
            LOGGER.error("[TrueAuth] Failed to save whitelist", ex);
        }
    }

    public synchronized boolean add(String name, boolean premiumOnly) {
        load();
        for (Entry entry : whitelistCache) {
            if (entry.name.equals(name)) {
                return false;
            }
        }
        whitelistCache.add(new Entry(name, premiumOnly));
        if (TrueauthConfig.debug()) System.out.println("[TrueAuth] Whitelist add: " + name + ", premiumOnly: " + premiumOnly);
        saveAsync();
        return true;
    }

    public synchronized boolean remove(String name) {
        load();
        boolean removed = whitelistCache.removeIf(entry -> entry.name.equals(name));
        if (removed) {
            if (TrueauthConfig.debug()) System.out.println("[TrueAuth] Whitelist remove: " + name);
            saveAsync();
        }
        return removed;
    }
    
    public Entry getWhitelistEntry(String name) {
        load();
        for (Entry entry : whitelistCache) {
            if (entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    public List<Entry> getWhitelistedEntries() {
        load();
        return List.copyOf(whitelistCache);
    }
}
