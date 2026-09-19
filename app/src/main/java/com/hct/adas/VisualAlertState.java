package com.hct.adas;

import java.util.Set;

/** Target-bound display history only; never resets or replays decision/audio state. */
public final class VisualAlertState {
    private static final long HOLD_NANOS = 1_500_000_000L;
    private long targetId;
    private long eventAtNanos;
    private long continuity;
    private Set<AdasDecisionEngine.Alert> events = Set.of();

    /** Called once per analyzed observation, including LOST and invalid measurements. */
    public void observe(long id, boolean validTarget, Set<AdasDecisionEngine.Alert> current,
                        long nowNanos) {
        if (!validTarget || id != targetId) {
            clear();
        }
        if (!validTarget || id <= 0L) {
            return;
        }
        targetId = id;
        if (!current.isEmpty()) {
            events = Set.copyOf(current);
            eventAtNanos = nowNanos;
        }
    }

    public Set<AdasDecisionEngine.Alert> eventsFor(long id, long nowNanos) {
        long age = nowNanos - eventAtNanos;
        return id == targetId && age >= 0L && age < HOLD_NANOS ? events : Set.of();
    }

    public void clear() {
        continuity++;
        targetId = 0L;
        eventAtNanos = 0L;
        events = Set.of();
    }

    /** Lets the UI detect a LOST/invalid observation even when it skipped that frame. */
    public long continuity() {
        return continuity;
    }
}
