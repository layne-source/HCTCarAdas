package com.hct.adas;

import static com.hct.adas.FixedGuideController.Mode.DANGER;
import static com.hct.adas.FixedGuideController.Mode.HIDDEN;
import static com.hct.adas.FixedGuideController.Mode.MONITORING;
import static com.hct.adas.FixedGuideController.Mode.NORMAL;
import static com.hct.adas.FixedGuideController.Mode.WARNING;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FixedGuideControllerTest {
    @Test
    public void opensOnlyAfterOneContinuousSecondAtTwentyKmh() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, update(controller, 9_000L, 19.9));
        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_500L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_999L, 20.0));
        assertEquals(NORMAL, update(controller, 11_000L, 20.0));
    }

    @Test
    public void intermediateSpeedCancelsPendingOpening() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_500L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_900L, 19.9));
        assertEquals(HIDDEN, update(controller, 11_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_500L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_999L, 20.0));
        assertEquals(NORMAL, update(controller, 12_000L, 20.0));
    }

    @Test
    public void intermediateSpeedKeepsAnOpenGateAndFifteenKmhClosesAfterOneSecond() {
        FixedGuideController controller = openedController();

        assertEquals(NORMAL, update(controller, 11_100L, 19.9));
        assertEquals(NORMAL, update(controller, 11_200L, 15.1));
        assertEquals(NORMAL, update(controller, 11_300L, 15.0));
        assertEquals(NORMAL, update(controller, 11_800L, 15.0));
        assertEquals(NORMAL, update(controller, 12_299L, 15.0));
        assertEquals(HIDDEN, update(controller, 12_300L, 15.0));
        assertEquals(HIDDEN, update(controller, 12_400L, 19.9));
    }

    @Test
    public void risingAboveFifteenCancelsPendingClosing() {
        FixedGuideController controller = openedController();

        assertEquals(NORMAL, update(controller, 11_100L, 15.0));
        assertEquals(NORMAL, update(controller, 11_600L, 15.0));
        assertEquals(NORMAL, update(controller, 12_000L, 15.1));
        assertEquals(NORMAL, update(controller, 12_100L, 15.0));
        assertEquals(NORMAL, update(controller, 12_600L, 15.0));
        assertEquals(NORMAL, update(controller, 13_099L, 15.0));
        assertEquals(HIDDEN, update(controller, 13_100L, 15.0));
    }

    @Test
    public void repeatedSpeedObservationCountsOnlyWhileItIsFresh() {
        FixedGuideController controller = new FixedGuideController();
        long measuredAt = nanos(10_000L);

        assertEquals(HIDDEN, controller.update(timedInput(nanos(10_000L), measuredAt),
                nanos(10_000L), nanos(10_000L)));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(10_500L), measuredAt),
                nanos(10_500L), nanos(10_500L)));
        assertEquals(NORMAL, controller.update(timedInput(nanos(11_000L), measuredAt),
                nanos(11_000L), nanos(11_000L)));
        assertEquals(NORMAL, controller.update(timedInput(nanos(11_500L), measuredAt),
                nanos(11_500L), nanos(11_500L)));
        assertEquals(NORMAL, controller.update(timedInput(nanos(12_000L), measuredAt),
                nanos(12_000L), nanos(12_000L)));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(12_000L), measuredAt),
                nanos(12_000L) + 1L, nanos(12_000L) + 1L));
        assertEquals(HIDDEN, update(controller, 12_100L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_600L, 20.0));
        assertEquals(NORMAL, update(controller, 13_100L, 20.0));
    }

    @Test
    public void speedObservationGapCannotCompleteOpeningWhenUiSkipsTheExpiry() {
        FixedGuideController controller = new FixedGuideController();

        // The first accepted fix is already 1.5s old; frames stay continuous across its expiry.
        assertEquals(HIDDEN, controller.update(timedInput(nanos(11_500L), nanos(10_000L)),
                nanos(11_500L), nanos(11_500L)));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(12_000L), nanos(10_000L)),
                nanos(12_000L), nanos(12_000L)));
        assertEquals(HIDDEN, update(controller, 12_001L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_501L, 20.0));
        assertEquals(HIDDEN, update(controller, 13_000L, 20.0));
        assertEquals(NORMAL, update(controller, 13_001L, 20.0));
    }

    @Test
    public void speedObservationGapClearsAnAlreadyOpenGate() {
        FixedGuideController controller = openedController();

        for (long time = 11_500L; time <= 13_000L; time += 500L) {
            assertEquals(NORMAL, controller.update(timedInput(nanos(time), nanos(11_000L)),
                    nanos(time), nanos(time)));
        }
        assertEquals(HIDDEN, update(controller, 13_001L, 18.0));
        assertEquals(HIDDEN, update(controller, 13_100L, 20.0));
        assertEquals(HIDDEN, update(controller, 13_600L, 20.0));
        assertEquals(NORMAL, update(controller, 14_100L, 20.0));
    }

    @Test
    public void twoSecondSpeedObservationGapStillHasContinuousCoverage() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, controller.update(timedInput(nanos(11_000L), nanos(10_000L)),
                nanos(11_000L), nanos(11_000L)));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(11_500L), nanos(10_000L)),
                nanos(11_500L), nanos(11_500L)));
        assertEquals(NORMAL, update(controller, 12_000L, 20.0));
    }

    @Test
    public void frameExpiresImmediatelyAfterSevenHundredFiftyMilliseconds() {
        FixedGuideController controller = openedController();
        FixedGuideController.Input frame = timedInput(nanos(11_250L), nanos(12_000L));

        assertEquals(NORMAL, controller.update(frame, nanos(12_000L), nanos(12_000L)));
        assertEquals(HIDDEN, controller.update(frame, nanos(12_000L) + 1L,
                nanos(12_000L) + 1L));
        assertEquals(HIDDEN, update(controller, 12_100L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_600L, 20.0));
        assertEquals(NORMAL, update(controller, 13_100L, 20.0));
    }

    @Test
    public void futureFrameResetsTheGate() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, controller.update(
                timedInput(nanos(12_000L) + 1L, nanos(12_000L)),
                nanos(12_000L), nanos(12_000L)));
        assertEquals(HIDDEN, update(controller, 12_100L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_600L, 20.0));
        assertEquals(NORMAL, update(controller, 13_100L, 20.0));
    }

    @Test
    public void invalidAndFutureSpeedTimestampsResetTheGate() {
        long[] invalidTimestamps = {0L, -1L, nanos(12_000L) + 1L};
        for (long measuredAt : invalidTimestamps) {
            FixedGuideController controller = openedController();
            assertEquals(HIDDEN, controller.update(timedInput(nanos(12_000L), measuredAt),
                    nanos(12_000L), nanos(12_000L)));
            assertEquals(HIDDEN, update(controller, 12_100L, 20.0));
            assertEquals(HIDDEN, update(controller, 12_600L, 20.0));
            assertEquals(NORMAL, update(controller, 13_100L, 20.0));
        }
    }

    @Test
    public void invalidSpeedValuesResetTheGateInsteadOfCountingAsStopped() {
        double[] invalidSpeeds = {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.1};
        for (double speed : invalidSpeeds) {
            FixedGuideController controller = openedController();
            assertEquals(HIDDEN, update(controller, 11_100L, speed));
            assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
            assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
            assertEquals(NORMAL, update(controller, 12_200L, 20.0));
        }
    }

    @Test
    public void independentClockOriginsAndNegativeFrameTimesAreValid() {
        FixedGuideController controller = new FixedGuideController();
        long measuredAt = nanos(100_000L);

        assertEquals(HIDDEN, controller.update(timedInput(nanos(-1_000L), measuredAt),
                nanos(-1_000L), nanos(100_000L)));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(-500L), measuredAt),
                nanos(-500L), nanos(100_500L)));
        assertEquals(HIDDEN, controller.update(timedInput(-1L, measuredAt),
                -1L, nanos(101_000L) - 1L));
        assertEquals(NORMAL, controller.update(timedInput(0L, measuredAt),
                0L, nanos(101_000L)));
    }

    @Test
    public void unavailableRuntimeCalibrationOrForegroundResetsTheGate() {
        boolean[][] conditions = {
                {false, true, true, false},
                {true, false, true, false},
                {true, true, false, false},
                {true, true, true, true}
        };
        for (boolean[] condition : conditions) {
            FixedGuideController controller = openedController();
            FixedGuideController.Input unavailable = new FixedGuideController.Input(
                    1L, nanos(11_100L), condition[0], condition[1], condition[2],
                    condition[3], false, 20.0, nanos(11_100L),
                    LeadVehicleTracker.State.TRACKING, true, false, false);
            assertEquals(HIDDEN, controller.update(unavailable,
                    nanos(11_100L), nanos(11_100L)));
            assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
            assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
            assertEquals(NORMAL, update(controller, 12_200L, 20.0));
        }
    }

    @Test
    public void openingSettingsKeepsTheCurrentGuideMode() {
        FixedGuideController controller = openedController();
        FixedGuideController.Input settings = new FixedGuideController.Input(
                1L, nanos(11_100L), true, true, true, false, true,
                20.0, nanos(11_100L), LeadVehicleTracker.State.TRACKING, true, false, false);

        assertEquals(NORMAL, controller.update(settings, nanos(11_100L), nanos(11_100L)));
        assertEquals(HIDDEN, controller.update(settings, nanos(11_851L), nanos(11_851L)));
    }

    @Test
    public void sessionChangeRestartsPendingOpeningAndAnOpenGate() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, updateSession(controller, 2L, 10_500L));
        assertEquals(HIDDEN, updateSession(controller, 2L, 11_000L));
        assertEquals(HIDDEN, updateSession(controller, 2L, 11_499L));
        assertEquals(NORMAL, updateSession(controller, 2L, 11_500L));
        assertEquals(HIDDEN, updateSession(controller, 3L, 11_600L));
        assertEquals(HIDDEN, updateSession(controller, 3L, 12_100L));
        assertEquals(NORMAL, updateSession(controller, 3L, 12_600L));
    }

    @Test
    public void nullInputAndExplicitResetRequireTheOpeningDelayAgain() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, controller.update(null, nanos(11_100L), nanos(11_100L)));
        assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
        assertEquals(NORMAL, update(controller, 12_200L, 20.0));
        controller.reset();
        assertEquals(HIDDEN, update(controller, 12_300L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_800L, 20.0));
        assertEquals(NORMAL, update(controller, 13_300L, 20.0));
    }

    @Test
    public void freshEmptyAndCandidateResultsShowMonitoringOnlyAfterSpeedOpens() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, updateState(controller, 10_000L, 20.0,
                LeadVehicleTracker.State.NONE, false, true, true));
        assertEquals(HIDDEN, updateState(controller, 10_500L, 20.0,
                LeadVehicleTracker.State.NONE, false, true, true));
        assertEquals(MONITORING, updateState(controller, 11_000L, 20.0,
                LeadVehicleTracker.State.NONE, false, true, true));
        assertEquals(MONITORING, updateState(controller, 11_100L, 20.0,
                LeadVehicleTracker.State.CANDIDATE, false, true, true));
    }

    @Test
    public void targetLossOrInvalidDistanceHidesWithoutClearingTheSpeedLatch() {
        FixedGuideController controller = openedController();

        assertEquals(DANGER, updateState(controller, 11_100L, 18.0,
                LeadVehicleTracker.State.TRACKING, true, false, true));
        assertEquals(HIDDEN, updateState(controller, 11_200L, 18.0,
                LeadVehicleTracker.State.LOST, false, false, true));
        assertEquals(NORMAL, updateState(controller, 11_300L, 18.0,
                LeadVehicleTracker.State.TRACKING, true, false, false));
        assertEquals(HIDDEN, updateState(controller, 11_400L, 18.0,
                LeadVehicleTracker.State.TRACKING, false, true, true));
        assertEquals(NORMAL, updateState(controller, 11_500L, 18.0,
                LeadVehicleTracker.State.TRACKING, true, false, false));
    }

    @Test
    public void targetLossStillAllowsLowSpeedToCloseTheGate() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, updateState(controller, 11_100L, 15.0,
                LeadVehicleTracker.State.LOST, false, false, false));
        assertEquals(HIDDEN, updateState(controller, 11_600L, 15.0,
                LeadVehicleTracker.State.LOST, false, false, false));
        assertEquals(HIDDEN, updateState(controller, 12_100L, 15.0,
                LeadVehicleTracker.State.LOST, false, false, false));
        assertEquals(HIDDEN, update(controller, 12_200L, 18.0));
    }

    @Test
    public void dangerWinsOverWarningAndOnlyCurrentRiskFlagsAreUsed() {
        FixedGuideController controller = openedController();

        assertEquals(WARNING, updateState(controller, 11_100L, 20.0,
                LeadVehicleTracker.State.TRACKING, true, true, false));
        assertEquals(DANGER, updateState(controller, 11_200L, 20.0,
                LeadVehicleTracker.State.TRACKING, true, true, true));
        assertEquals(NORMAL, updateState(controller, 11_300L, 20.0,
                LeadVehicleTracker.State.TRACKING, true, false, false));
    }

    @Test
    public void missingTrackingStateIsInvalidRatherThanAnEmptyAnalysis() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, updateState(controller, 11_100L, 20.0,
                null, false, false, false));
        assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
        assertEquals(NORMAL, update(controller, 12_200L, 20.0));
    }

    @Test
    public void outOfOrderSpeedObservationCannotPreserveAnOpenGate() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, controller.update(timedInput(nanos(11_100L), nanos(10_900L)),
                nanos(11_100L), nanos(11_100L)));
        assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
        assertEquals(NORMAL, update(controller, 12_200L, 20.0));
    }

    @Test
    public void backwardsUiTimeClearsTheGateWithoutMixingClockDomains() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, controller.update(timedInput(nanos(10_900L), nanos(11_100L)),
                nanos(10_900L), nanos(11_100L)));
        assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
        assertEquals(NORMAL, update(controller, 12_200L, 20.0));
    }

    @Test
    public void frameGapCannotCompleteOpeningWhenUiSkipsTheExpiry() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, controller.update(timedInput(nanos(10_000L), nanos(10_750L)),
                nanos(10_750L), nanos(10_750L)));
        assertEquals(HIDDEN, update(controller, 11_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_500L, 20.0));
        assertEquals(NORMAL, update(controller, 12_000L, 20.0));
    }

    @Test
    public void frameGapClearsAnOpenGateEvenWhenBothInputsLookFreshOnRecovery() {
        FixedGuideController controller = openedController();

        assertEquals(NORMAL, controller.update(timedInput(nanos(11_000L), nanos(11_750L)),
                nanos(11_750L), nanos(11_750L)));
        assertEquals(HIDDEN, update(controller, 12_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 12_500L, 20.0));
        assertEquals(NORMAL, update(controller, 13_000L, 20.0));
    }

    @Test
    public void exactSevenHundredFiftyMillisecondFrameGapPreservesContinuity() {
        FixedGuideController controller = new FixedGuideController();

        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_750L, 20.0));
        assertEquals(NORMAL, update(controller, 11_000L, 20.0));
    }

    @Test
    public void outOfOrderFrameClearsAnOpenGate() {
        FixedGuideController controller = openedController();

        assertEquals(HIDDEN, controller.update(timedInput(nanos(10_900L), nanos(11_100L)),
                nanos(11_100L), nanos(11_100L)));
        assertEquals(HIDDEN, update(controller, 11_200L, 20.0));
        assertEquals(HIDDEN, update(controller, 11_700L, 20.0));
        assertEquals(NORMAL, update(controller, 12_200L, 20.0));
    }

    private static FixedGuideController openedController() {
        FixedGuideController controller = new FixedGuideController();
        assertEquals(HIDDEN, update(controller, 10_000L, 20.0));
        assertEquals(HIDDEN, update(controller, 10_500L, 20.0));
        assertEquals(NORMAL, update(controller, 11_000L, 20.0));
        return controller;
    }

    private static FixedGuideController.Mode update(FixedGuideController controller,
                                                    long timeMillis, double speedKmh) {
        return updateState(controller, timeMillis, speedKmh,
                LeadVehicleTracker.State.TRACKING, true, false, false);
    }

    private static FixedGuideController.Mode updateState(FixedGuideController controller,
            long timeMillis, double speedKmh, LeadVehicleTracker.State state,
            boolean targetValid, boolean warning, boolean danger) {
        long now = nanos(timeMillis);
        return controller.update(new FixedGuideController.Input(1L, now,
                true, true, true, false, false, speedKmh, now,
                state, targetValid, warning, danger), now, now);
    }

    private static FixedGuideController.Mode updateSession(FixedGuideController controller,
                                                           long sessionId, long timeMillis) {
        long now = nanos(timeMillis);
        return controller.update(new FixedGuideController.Input(sessionId, now,
                true, true, true, false, false, 20.0, now,
                LeadVehicleTracker.State.TRACKING, true, false, false), now, now);
    }

    private static FixedGuideController.Input timedInput(long frameTimestampNanos,
                                                        long speedTimestampNanos) {
        return new FixedGuideController.Input(1L, frameTimestampNanos,
                true, true, true, false, false, 20.0, speedTimestampNanos,
                LeadVehicleTracker.State.TRACKING, true, false, false);
    }

    private static long nanos(long millis) {
        return millis * 1_000_000L;
    }
}
