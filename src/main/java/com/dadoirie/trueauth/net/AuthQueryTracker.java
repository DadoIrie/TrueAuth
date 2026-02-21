package com.dadoirie.trueauth.net;

import java.util.HashSet;
import java.util.Set;

public final class AuthQueryTracker {
    private static final Set<Integer> IN_FLIGHT = new HashSet<>();
    private static final Set<Integer> RESULT_IN_FLIGHT = new HashSet<>();

    public static void mark(int txId) {
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.add(txId);
        }
    }

    public static boolean contains(int txId) {
        synchronized (IN_FLIGHT) {
            return IN_FLIGHT.contains(txId);
        }
    }

    public static boolean consume(int txId) {
        synchronized (IN_FLIGHT) {
            return IN_FLIGHT.remove(txId);
        }
    }

    public static void markResult(int txId) {
        synchronized (RESULT_IN_FLIGHT) {
            RESULT_IN_FLIGHT.add(txId);
        }
    }

    public static boolean containsResult(int txId) {
        synchronized (RESULT_IN_FLIGHT) {
            return RESULT_IN_FLIGHT.contains(txId);
        }
    }

    public static boolean consumeResult(int txId) {
        synchronized (RESULT_IN_FLIGHT) {
            return RESULT_IN_FLIGHT.remove(txId);
        }
    }

    private AuthQueryTracker() {}
}