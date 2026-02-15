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

    public synchronized Optional<UUID> getPremiumUuid(String name) {
        Entry e = map.get(name.toLowerCase(Locale.ROOT));
        return e == null ? Optional.empty() : Optional.ofNullable(e.uuid);
    }

    public synchronized boolean isRegistered(String name) {
        return map.containsKey(name.toLowerCase(Locale.ROOT));
    }
    
    public synchronized boolean isPremium(String name) {
        return map.get(name.toLowerCase(Locale.ROOT)).premium;
    }

    public synchronized void recordPremiumPlayer(String name, UUID uuid, String ip) {
        String k = name.toLowerCase(Locale.ROOT);
        Entry e = map.getOrDefault(k, new Entry());
        e.uuid = uuid;
        e.premium = true;
        long now = Instant.now().toEpochMilli();
        if (e.firstVerifiedAt == 0) e.firstVerifiedAt = now;
        e.lastVerifiedAt = now;
        e.lastSuccessIp = ip;
        if (e.registeredAt == 0) e.registeredAt = now;
        map.put(k, e);
        saveAsync();
    }

    public synchronized void recordOfflinePlayer(String name, UUID uuid, String ip, String passwordHash) {
        String k = name.toLowerCase(Locale.ROOT);
        Entry e = map.getOrDefault(k, new Entry());
        e.uuid = uuid;
        e.premium = false;
        e.password = passwordHash;
        e.lastSuccessIp = ip;
        if (e.registeredAt == 0) e.registeredAt = Instant.now().toEpochMilli();
        map.put(k, e);
        saveAsync();
    }

    public synchronized String getPassword(String name) {
        Entry e = map.get(name.toLowerCase(Locale.ROOT));
        return e != null ? e.password : null;
    }

    public synchronized void removeEntry(String name) {
        map.remove(name.toLowerCase(Locale.ROOT));
        saveAsync();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                    for (String k : o.keySet()) {
                        JsonObject e = o.getAsJsonObject(k);
                        Entry en = new Entry();
                        en.uuid = UUID.fromString(e.get("uuid").getAsString());
                        en.premium = e.get("premium").getAsBoolean();
                        if (e.has("password")) en.password = e.get("password").getAsString();
                        en.registeredAt = e.get("registeredAt").getAsLong();
                        if (e.has("firstVerifiedAt")) en.firstVerifiedAt = e.get("firstVerifiedAt").getAsLong();
                        if (e.has("lastVerifiedAt")) en.lastVerifiedAt = e.get("lastVerifiedAt").getAsLong();
                        en.lastSuccessIp = e.get("lastSuccessIp").getAsString();
                        map.put(k, en);
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
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Entry> me : map.entrySet()) {
                JsonObject e = new JsonObject();
                e.addProperty("uuid", me.getValue().uuid.toString());
                e.addProperty("premium", me.getValue().premium);
                e.addProperty("registeredAt", me.getValue().registeredAt);
                if (me.getValue().password != null) e.addProperty("password", me.getValue().password);
                if (me.getValue().premium) {
                    e.addProperty("firstVerifiedAt", me.getValue().firstVerifiedAt);
                    e.addProperty("lastVerifiedAt", me.getValue().lastVerifiedAt);
                }
                e.addProperty("lastSuccessIp", me.getValue().lastSuccessIp);
                o.add(me.getKey(), e);
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(o, w);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}