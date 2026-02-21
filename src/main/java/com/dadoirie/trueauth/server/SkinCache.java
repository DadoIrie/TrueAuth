package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for player skin properties (textures).
 * Stores PropertyMap by UUID when premium players authenticate,
 * so that offline players treated as premium via IP grace can receive skins.
 * Persists to disk for survival across server restarts.
 */
public final class SkinCache {
    private final Map<UUID, PropertyMap> cache = new ConcurrentHashMap<>();
    private final Path storageDir;
    private static final Gson GSON = new Gson();
    private static final Type PROPERTY_LIST_TYPE = new TypeToken<List<PropertyData>>(){}.getType();
    
    public SkinCache() {
        this.storageDir = FMLPaths.GAMEDIR.get().resolve(".private/trueauth/skins");
        try {
            Files.createDirectories(storageDir);
        } catch (Exception e) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] SkinCache: failed to create storage dir: " + e.getMessage());
            }
        }
    }
    
    /**
     * Store skin properties for a premium player.
     *
     * @param uuid The player's premium UUID
     * @param propMap The property map containing skin textures
     */
    public void setPropMap(UUID uuid, PropertyMap propMap) {
        cache.put(uuid, propMap);
        saveToDisk(uuid, propMap);
        if (TrueauthConfig.debug()) {
            System.out.println("[TrueAuth] SkinCache: stored properties for uuid=" + uuid + ", cache size=" + cache.size());
        }
    }
    
    /**
     * Retrieve skin properties for a premium player.
     *
     * @param uuid The player's premium UUID
     * @return The property map containing skin textures, or null if not cached
     */
    public PropertyMap getPropMap(UUID uuid) {
        if (uuid == null) return null;
        
        PropertyMap result = cache.get(uuid);
        if (result == null) {
            result = loadFromDisk(uuid);
            if (result != null) {
                cache.put(uuid, result);
            }
        }
        
        if (TrueauthConfig.debug()) {
            if (result != null) {
                System.out.println("[TrueAuth] SkinCache: found properties for uuid=" + uuid);
            } else {
                System.out.println("[TrueAuth] SkinCache: no properties found for uuid=" + uuid);
            }
        }
        return result;
    }
    
    private void saveToDisk(UUID uuid, PropertyMap propMap) {
        try {
            Path file = storageDir.resolve(uuid.toString() + ".json");
            List<PropertyData> properties = new ArrayList<>();
            for (Map.Entry<String, Collection<Property>> entry : propMap.asMap().entrySet()) {
                for (Property prop : entry.getValue()) {
                    PropertyData data = new PropertyData();
                    data.name = entry.getKey();
                    data.value = prop.value();
                    data.signature = prop.signature();
                    properties.add(data);
                }
            }
            Files.writeString(file, GSON.toJson(properties));
        } catch (Exception e) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] SkinCache: failed to save skin for uuid=" + uuid + ": " + e.getMessage());
            }
        }
    }
    
    private PropertyMap loadFromDisk(UUID uuid) {
        try {
            Path file = storageDir.resolve(uuid.toString() + ".json");
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] SkinCache: trying to load from " + file);
                System.out.println("[TrueAuth] SkinCache: file exists=" + Files.exists(file));
            }
            if (Files.exists(file)) {
                String json = Files.readString(file);
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] SkinCache: loaded json: " + json);
                }
                List<PropertyData> properties = GSON.fromJson(json, PROPERTY_LIST_TYPE);
                if (properties == null) {
                    if (TrueauthConfig.debug()) {
                        System.out.println("[TrueAuth] SkinCache: parsed properties is null");
                    }
                    return null;
                }
                
                PropertyMap propMap = new PropertyMap();
                for (PropertyData data : properties) {
                    Property prop = data.signature != null 
                        ? new Property(data.name, data.value, data.signature)
                        : new Property(data.name, data.value);
                    propMap.put(data.name, prop);
                }
                if (TrueauthConfig.debug()) {
                    System.out.println("[TrueAuth] SkinCache: loaded " + properties.size() + " properties for uuid=" + uuid);
                }
                return propMap;
            }
        } catch (Exception e) {
            if (TrueauthConfig.debug()) {
                System.out.println("[TrueAuth] SkinCache: failed to load skin for uuid=" + uuid + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        return null;
    }
    
    private static class PropertyData {
        String name;
        String value;
        String signature;
    }
}

