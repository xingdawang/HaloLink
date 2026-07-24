package com.halolink.app;

import java.util.Locale;

final class EnergyPolicy {
    private static final long[] RECONNECT_DELAYS_MS = {
            2_000L, 4_000L, 8_000L, 15_000L, 30_000L
    };
    private static final long[] DISCOVERY_RETRY_DELAYS_MS = {
            5_000L, 10_000L, 20_000L, 30_000L
    };
    private static final float NORMAL_IDLE_BRIGHTNESS = 0.12f;
    private static final float MINIMAL_IDLE_BRIGHTNESS = 0.05f;

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

    static boolean supportsMinimalDisplay(String state) {
        return isIdleState(state) || "DISCONNECTED".equals(normalize(state));
    }

    static int targetFps(String state) {
        switch (normalize(state)) {
            case "WORKING":
            case "STREAMING":
                return 30;
            case "SEARCHING":
            case "CONNECTING":
                return 24;
            case "THINKING":
            case "LISTENING":
                return 20;
            case "ERROR":
                return 10;
            default:
                return 0;
        }
    }

    static long animationFrameDelayMs(String state) {
        int fps = targetFps(state);
        return fps == 0 ? 0L : (long) Math.ceil(1_000d / fps);
    }

    static long reconnectDelayMs(int attempt) {
        return cappedDelay(RECONNECT_DELAYS_MS, attempt);
    }

    static long discoveryRetryDelayMs(int attempt) {
        return cappedDelay(DISCOVERY_RETRY_DELAYS_MS, attempt);
    }

    static long pollingIntervalMs(long disconnectedDurationMs) {
        if (disconnectedDurationMs < 10_000L) return 1_500L;
        if (disconnectedDurationMs < 30_000L) return 3_000L;
        return 6_000L;
    }

    static float idleBrightness(boolean minimalIdleMode) {
        return minimalIdleMode ? MINIMAL_IDLE_BRIGHTNESS : NORMAL_IDLE_BRIGHTNESS;
    }

    private static long cappedDelay(long[] delays, int attempt) {
        int index = Math.max(0, Math.min(attempt, delays.length - 1));
        return delays[index];
    }

    private static String normalize(String state) {
        return state == null ? "READY" : state.toUpperCase(Locale.ROOT);
    }
}
