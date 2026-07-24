package com.halolink.app;

import static org.junit.Assert.assertFalse;
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
    }
}
