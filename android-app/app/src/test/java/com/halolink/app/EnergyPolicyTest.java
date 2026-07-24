package com.halolink.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EnergyPolicyTest {
    @Test
    public void pollingRunsOnlyAsForegroundWebSocketFallback() {
        assertTrue(EnergyPolicy.shouldPoll(true, false, true));
        assertFalse(EnergyPolicy.shouldPoll(true, true, true));
        assertFalse(EnergyPolicy.shouldPoll(false, false, true));
        assertFalse(EnergyPolicy.shouldPoll(true, false, false));
    }

    @Test
    public void onlyDynamicStatesAnimate() {
        assertTrue(EnergyPolicy.isAnimatedState("THINKING"));
        assertTrue(EnergyPolicy.isAnimatedState("WORKING"));
        assertTrue(EnergyPolicy.isAnimatedState("STREAMING"));
        assertTrue(EnergyPolicy.isAnimatedState("ERROR"));
        assertFalse(EnergyPolicy.isAnimatedState("READY"));
        assertFalse(EnergyPolicy.isAnimatedState("COMPLETED"));
    }

    @Test
    public void readyAndCompletedUseIdleDisplayPolicy() {
        assertTrue(EnergyPolicy.isIdleState("READY"));
        assertTrue(EnergyPolicy.isIdleState("completed"));
        assertFalse(EnergyPolicy.isIdleState("WORKING"));
        assertTrue(EnergyPolicy.supportsMinimalDisplay("DISCONNECTED"));
        assertFalse(EnergyPolicy.supportsMinimalDisplay("SEARCHING"));
    }

    @Test
    public void everyDynamicStateUsesItsConfiguredFrameRate() {
        assertEquals(30, EnergyPolicy.targetFps("WORKING"));
        assertEquals(30, EnergyPolicy.targetFps("STREAMING"));
        assertEquals(24, EnergyPolicy.targetFps("SEARCHING"));
        assertEquals(24, EnergyPolicy.targetFps("CONNECTING"));
        assertEquals(20, EnergyPolicy.targetFps("THINKING"));
        assertEquals(20, EnergyPolicy.targetFps("LISTENING"));
        assertEquals(10, EnergyPolicy.targetFps("ERROR"));
        assertEquals(0, EnergyPolicy.targetFps("READY"));
        assertEquals(0, EnergyPolicy.targetFps("COMPLETED"));
        assertEquals(34L, EnergyPolicy.animationFrameDelayMs("WORKING"));
        assertEquals(42L, EnergyPolicy.animationFrameDelayMs("SEARCHING"));
    }

    @Test
    public void reconnectDelayUsesCappedExponentialBackoff() {
        assertEquals(2_000L, EnergyPolicy.reconnectDelayMs(0));
        assertEquals(4_000L, EnergyPolicy.reconnectDelayMs(1));
        assertEquals(8_000L, EnergyPolicy.reconnectDelayMs(2));
        assertEquals(15_000L, EnergyPolicy.reconnectDelayMs(3));
        assertEquals(30_000L, EnergyPolicy.reconnectDelayMs(4));
        assertEquals(30_000L, EnergyPolicy.reconnectDelayMs(20));
    }

    @Test
    public void discoveryDelayUsesCappedExponentialBackoff() {
        assertEquals(5_000L, EnergyPolicy.discoveryRetryDelayMs(0));
        assertEquals(10_000L, EnergyPolicy.discoveryRetryDelayMs(1));
        assertEquals(20_000L, EnergyPolicy.discoveryRetryDelayMs(2));
        assertEquals(30_000L, EnergyPolicy.discoveryRetryDelayMs(3));
        assertEquals(30_000L, EnergyPolicy.discoveryRetryDelayMs(20));
    }

    @Test
    public void pollingSlowsAsDisconnectionContinues() {
        assertEquals(1_500L, EnergyPolicy.pollingIntervalMs(0L));
        assertEquals(1_500L, EnergyPolicy.pollingIntervalMs(9_999L));
        assertEquals(3_000L, EnergyPolicy.pollingIntervalMs(10_000L));
        assertEquals(3_000L, EnergyPolicy.pollingIntervalMs(29_999L));
        assertEquals(6_000L, EnergyPolicy.pollingIntervalMs(30_000L));
    }

    @Test
    public void minimalIdleBrightnessNeverExceedsFivePercent() {
        assertEquals(0.05f, EnergyPolicy.idleBrightness(true), 0.0001f);
        assertTrue(EnergyPolicy.idleBrightness(true) <= 0.05f);
        assertEquals(0.12f, EnergyPolicy.idleBrightness(false), 0.0001f);
    }
}
