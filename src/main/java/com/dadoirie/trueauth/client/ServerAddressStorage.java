package com.dadoirie.trueauth.client;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the server address (hostname) for the current connection.
 * This is captured during connection setup and used during CONFIGURATION phase.
 */
public final class ServerAddressStorage {
    private static final ConcurrentHashMap<String, String> TA_CONNECTION_ADDRESSES = new ConcurrentHashMap<>();
    
    /**
     * Store the server hostname for a connection.
     */
    public static void store(String connectionKey, String hostname) {
        if (connectionKey != null && hostname != null) {
            TA_CONNECTION_ADDRESSES.put(connectionKey, hostname);
        }
    }
    
    /**
     * Get the server hostname for a connection (without removing).
     */
    public static String get(String connectionKey) {
        if (connectionKey == null) return null;
        return TA_CONNECTION_ADDRESSES.get(connectionKey);
    }
    
    private ServerAddressStorage() {}
}
