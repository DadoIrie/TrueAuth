package com.dadoirie.trueauth.server;

import com.google.gson.*;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class NameRegistry {
    public static class Entry {
        public UUID uuid;
        public boolean premium;
        public boolean semiPremium;
        public String password;
        public long registeredAt;
        public long firstVerifiedAt;
        public long lastVerifiedAt;
        public String lastSuccessIp;
    }

    private final Path file;
    private final Map<String, Entry> map = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public NameRegistry() {
        this.file = FMLPaths.GAMEDIR.get().resolve(".private/trueauth/trueauth-registry.json");
        load();
    }

    public synchronized UUID getUuid(String name) {
        return map.get(name).uuid;
    }

    public synchronized boolean isRegistered(String name) {
        return map.containsKey(name);
    }
    
    public synchronized boolean isPremium(String name) {
        return map.get(name).premium;
    }
    
    public synchronized boolean isSemiPremium(String name) {
        return map.get(name).semiPremium;
    }

    public synchronized void recordPremiumPlayer(String name, UUID uuid, String ip, String passwordHash, boolean semiPremium) {
        Entry entry = map.getOrDefault(name, new Entry());
        entry.uuid = uuid;
        entry.premium = true;
        entry.semiPremium = semiPremium;
        entry.password = passwordHash;
        long now = Instant.now().toEpochMilli();
        if (entry.firstVerifiedAt == 0) entry.firstVerifiedAt = now;
        entry.lastVerifiedAt = now;
        entry.lastSuccessIp = ip;
        if (entry.registeredAt == 0) entry.registeredAt = now;
        map.put(name, entry);
        saveAsync();
    }

    public synchronized void recordOfflinePlayer(String name, UUID uuid, String ip, String passwordHash) {
        Entry entry = map.getOrDefault(name, new Entry());
        entry.uuid = uuid;
        entry.premium = false;
        entry.password = passwordHash;
        entry.lastSuccessIp = ip;
        if (entry.registeredAt == 0) entry.registeredAt = Instant.now().toEpochMilli();
        map.put(name, entry);
        saveAsync();
    }

    public synchronized String getPassword(String name) {
        return map.get(name).password;
    }

    public synchronized void removeEntry(String name) {
        map.remove(name);
        saveAsync();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (String name : root.keySet()) {
                        JsonObject entryObj = root.getAsJsonObject(name);
                        Entry entry = new Entry();
                        entry.uuid = UUID.fromString(entryObj.get("uuid").getAsString());
                        entry.premium = entryObj.get("premium").getAsBoolean();
                        if (entryObj.has("semiPremium")) entry.semiPremium = entryObj.get("semiPremium").getAsBoolean();
                        entry.password = entryObj.get("password").getAsString();
                        entry.registeredAt = entryObj.get("registeredAt").getAsLong();
                        if (entryObj.has("firstVerifiedAt")) entry.firstVerifiedAt = entryObj.get("firstVerifiedAt").getAsLong();
                        if (entryObj.has("lastVerifiedAt")) entry.lastVerifiedAt = entryObj.get("lastVerifiedAt").getAsLong();
                        entry.lastSuccessIp = entryObj.get("lastSuccessIp").getAsString();
                        map.put(name, entry);
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void saveAsync() {
        new Thread(this::save, "TrueAuth-RegistrySave").start();
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Entry> mapEntry : map.entrySet()) {
                JsonObject entryObj = new JsonObject();
                Entry entry = mapEntry.getValue();
                entryObj.addProperty("uuid", entry.uuid.toString());
                entryObj.addProperty("premium", entry.premium);
                if (entry.premium) {
                    entryObj.addProperty("semiPremium", entry.semiPremium);
                }
                entryObj.addProperty("password", entry.password);
                entryObj.addProperty("registeredAt", entry.registeredAt);
                if (entry.premium) {
                    entryObj.addProperty("firstVerifiedAt", entry.firstVerifiedAt);
                    entryObj.addProperty("lastVerifiedAt", entry.lastVerifiedAt);
                }
                entryObj.addProperty("lastSuccessIp", entry.lastSuccessIp);
                root.add(mapEntry.getKey(), entryObj);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(root, writer);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}