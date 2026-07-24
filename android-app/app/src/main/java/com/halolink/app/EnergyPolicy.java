package com.halolink.app;

import java.util.Locale;

final class EnergyPolicy {
    private EnergyPolicy() { }

    static boolean shouldPoll(
            boolean foreground,
            boolean webSocketConnected,
            boolean bridgeSelected
    ) {
        return foreground && !webSocketConnected && bridgeSelected;
    }

    static boolean isAnimatedState(String state) {
        switch (normalize(state)) {
            case "SEARCHING":
            case "CONNECTING":
            case "THINKING":
            case "WORKING":
            case "STREAMING":
            case "LISTENING":
            case "ERROR":
                return true;
            default:
                return false;
        }
    }

    static boolean isIdleState(String state) {
        String normalized = normalize(state);
        return "READY".equals(normalized) || "COMPLETED".equals(normalized);
    }

    private static String normalize(String state) {
        return state == null ? "READY" : state.toUpperCase(Locale.ROOT);
    }
}
