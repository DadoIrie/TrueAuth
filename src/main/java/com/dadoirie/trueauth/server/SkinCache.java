package com.dadoirie.trueauth.server;

import com.dadoirie.trueauth.config.TrueauthConfig;
import com.mojang.authlib.properties.PropertyMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for player skin properties (textures).
 * Stores PropertyMap by UUID when premium players authenticate,
 * so that offline players treated as premium via IP grace can receive skins.
 */
public final class SkinCache {
    private final Map<UUID, PropertyMap> cache = new ConcurrentHashMap<>();
    
    /**
     * Store skin properties for a premium player.
     *
     * @param uuid The player's premium UUID
     * @param propMap The property map containing skin textures
     */
    public void setPropMap(UUID uuid, PropertyMap propMap) {
        cache.put(uuid, propMap);
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
        if (TrueauthConfig.debug()) {
            if (result != null) {
                System.out.println("[TrueAuth] SkinCache: found properties for uuid=" + uuid);
            } else {
                System.out.println("[TrueAuth] SkinCache: no properties found for uuid=" + uuid);
            }
        }
        return result;
    }
}
