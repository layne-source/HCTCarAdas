package com.hct.adas;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MainActivitySpeedValidationTest {
    @Test
    public void rejectsLargeJumpAtOneHertz() {
        assertFalse(MainActivity.isSpeedTransitionPlausible(
                100.0, 1_000_000_000L, 0.0, 2_000_000_000L));
    }

    @Test
    public void rejectsLargeJumpBetweenNearSimultaneousProviders() {
        assertFalse(MainActivity.isSpeedTransitionPlausible(
                0.0, 1_000_000_000L, 100.0, 1_040_000_000L));
    }

    @Test
    public void acceptsReasonableAcceleration() {
        assertTrue(MainActivity.isSpeedTransitionPlausible(
                36.0, 1_000_000_000L, 54.0, 2_000_000_000L));
    }
}
