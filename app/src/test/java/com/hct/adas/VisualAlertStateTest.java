package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

public final class VisualAlertStateTest {
    private static final Set<AdasDecisionEngine.Alert> FCW = Set.of(AdasDecisionEngine.Alert.FCW);

    @Test
    public void holdsEventOnlyForItsTargetAndOriginalDuration() {
        VisualAlertState state = new VisualAlertState();
        state.observe(7L, true, FCW, 0L);
        state.observe(7L, true, Set.of(), 1_000_000_000L);
        assertEquals(FCW, state.eventsFor(7L, 1_499_999_999L));
        assertTrue(state.eventsFor(8L, 1_000_000_000L).isEmpty());
        assertTrue(state.eventsFor(7L, 1_500_000_000L).isEmpty());
    }

    @Test
    public void lostThenSameIdRecoveryCannotReviveOldRedEvent() {
        VisualAlertState state = new VisualAlertState();
        state.observe(7L, true, FCW, 0L);
        long previousContinuity = state.continuity();
        state.observe(7L, false, Set.of(), 200_000_000L);
        state.observe(7L, true, Set.of(), 400_000_000L);
        // Even a UI which skipped the LOST frame must discard its red-to-green fade history.
        assertTrue(state.continuity() != previousContinuity);
        assertTrue(state.eventsFor(7L, 400_000_000L).isEmpty());
        state.observe(7L, true, FCW, 600_000_000L);
        assertEquals(FCW, state.eventsFor(7L, 600_000_000L));
    }

    @Test
    public void invalidInputAndTargetSwitchDiscardVisualHistory() {
        VisualAlertState state = new VisualAlertState();
        state.observe(7L, true, FCW, 0L);
        state.clear();
        assertTrue(state.eventsFor(7L, 100_000_000L).isEmpty());
        state.observe(7L, true, FCW, 200_000_000L);
        state.observe(8L, true, Set.of(), 400_000_000L);
        assertTrue(state.eventsFor(8L, 400_000_000L).isEmpty());
    }

    @Test
    public void visualInvalidationDoesNotResetDecisionCooldown() {
        AdasDecisionEngine engine = new AdasDecisionEngine();
        VisualAlertState state = new VisualAlertState();
        AdasDecisionEngine.Decision first = engine.update(
                new AdasDecisionEngine.Observation(0L, 10.0, 3.0, 0.0, 1000.0, true));
        assertTrue(first.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL));
        state.observe(7L, true, first.events(), 0L);
        state.clear();
        AdasDecisionEngine.Decision second = engine.update(
                new AdasDecisionEngine.Observation(500L, 10.0, 3.0, 0.0, 1000.0, true));
        assertTrue(second.events().isEmpty());
        assertTrue(second.headwayCritical());
    }
}
