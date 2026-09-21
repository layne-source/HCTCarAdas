package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;

public final class LeadVehicleTrackerTest {
    private static final VehicleDetector.Detection FRONT = box(0.40f, 0.40f, 0.60f, 0.70f);

    @Test
    public void ignoresLargeSideVehicleAndConfirmsCentralCandidate() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection side = box(0.75f, 0.15f, 0.99f, 0.95f);
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(0, side, FRONT)).state());
        tracker.update(frame(200, FRONT, side));
        LeadVehicleTracker.Snapshot result = tracker.update(frame(400, side, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING, result.state());
        assertSame(FRONT, result.detection());
        assertNotEquals(0L, result.trackId());
    }

    @Test
    public void favorsLowerGroundContactOverLargerBoxArea() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection far = box(0.30f, 0.10f, 0.70f, 0.58f);
        VehicleDetector.Detection near = box(0.46f, 0.65f, 0.54f, 0.80f);
        assertSame(near, tracker.update(frame(0, far, near)).detection());
    }

    @Test
    public void missingCandidateMustBeConfirmedAgain() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        tracker.update(frame(0, FRONT));
        tracker.update(frame(200, FRONT));
        assertEquals(LeadVehicleTracker.State.NONE, tracker.update(frame(400)).state());
        assertEquals(0L, tracker.update(frame(600, FRONT)).trackId());
        tracker.update(frame(800, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING,
                tracker.update(frame(1000, FRONT)).state());
    }

    @Test
    public void keepsIdAcrossJitterClassChangesAndDetectionOrdering() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        VehicleDetector.Detection shifted = new VehicleDetector.Detection(
                "truck", 0.85f, 0.41f, 0.41f, 0.61f, 0.71f);
        VehicleDetector.Detection far = box(0.47f, 0.25f, 0.53f, 0.48f);
        LeadVehicleTracker.Snapshot result = tracker.update(frame(600, far, shifted));
        assertEquals(id, result.trackId());
        assertSame(shifted, result.detection());
    }

    @Test
    public void shortMissRetainsIdButNeverPublishesOldBoxAsVisible() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        LeadVehicleTracker.Snapshot missing = tracker.update(frame(600));
        assertEquals(LeadVehicleTracker.State.LOST, missing.state());
        assertEquals(id, missing.trackId());
        assertNull(missing.detection());
        assertEquals(id, tracker.update(frame(800, FRONT)).trackId());
    }

    @Test
    public void longGapRequiresNewConfirmationAndNewId() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        LeadVehicleTracker.Snapshot expired = tracker.update(frame(1200, FRONT));
        assertEquals(LeadVehicleTracker.State.CANDIDATE, expired.state());
        assertEquals(0L, expired.trackId());
        tracker.update(frame(1400, FRONT));
        LeadVehicleTracker.Snapshot reacquired = tracker.update(frame(1600, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING, reacquired.state());
        assertNotEquals(id, reacquired.trackId());
        assertEquals(LeadVehicleTracker.State.NONE, tracker.update(frame(2400)).state());
    }

    @Test
    public void switchesToPersistentNearerVehicleWhileOriginalRemainsVisible() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        VehicleDetector.Detection cutIn = box(0.38f, 0.67f, 0.62f, 0.94f);
        assertEquals(id, tracker.update(frame(600, cutIn, FRONT)).trackId());
        assertEquals(id, tracker.update(frame(800, FRONT, cutIn)).trackId());
        LeadVehicleTracker.Snapshot switched = tracker.update(frame(1000, cutIn, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING, switched.state());
        assertNotEquals(id, switched.trackId());
        assertSame(cutIn, switched.detection());
    }

    @Test
    public void singleFrameChallengerDoesNotSwitchTarget() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        VehicleDetector.Detection cutIn = box(0.38f, 0.67f, 0.62f, 0.94f);
        tracker.update(frame(600, cutIn, FRONT));
        tracker.update(frame(800, FRONT));
        assertEquals(id, tracker.update(frame(1000, cutIn, FRONT)).trackId());
    }

    @Test
    public void unrelatedReplacementNeverInheritsOldId() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        VehicleDetector.Detection replacement = box(0.56f, 0.70f, 0.72f, 0.92f);
        LeadVehicleTracker.Snapshot missing = tracker.update(frame(600, replacement));
        assertEquals(LeadVehicleTracker.State.LOST, missing.state());
        assertNull(missing.detection());
        tracker.update(frame(800, replacement));
        LeadVehicleTracker.Snapshot switched = tracker.update(frame(1000, replacement));
        assertEquals(LeadVehicleTracker.State.TRACKING, switched.state());
        assertNotEquals(id, switched.trackId());
        assertSame(replacement, switched.detection());
    }

    @Test
    public void ambiguousOverlappingVehiclesDoNotSilentlyInheritId() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        confirm(tracker, 0, FRONT);
        VehicleDetector.Detection left = box(0.39f, 0.40f, 0.56f, 0.70f);
        VehicleDetector.Detection right = box(0.44f, 0.40f, 0.61f, 0.70f);
        LeadVehicleTracker.Snapshot ambiguous = tracker.update(frame(600, left, right));
        assertEquals(LeadVehicleTracker.State.LOST, ambiguous.state());
        assertNull(ambiguous.detection());
    }

    @Test
    public void rejectsLowConfidenceInvalidCoordinatesAndNonVehicleClasses() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        assertEquals(LeadVehicleTracker.State.NONE, tracker.update(frame(0,
                new VehicleDetector.Detection("car", Float.NaN, .4f, .4f, .6f, .7f),
                new VehicleDetector.Detection("car", .2f, .4f, .4f, .6f, .7f),
                new VehicleDetector.Detection("person", .9f, .4f, .4f, .6f, .7f),
                box(Float.NaN, .4f, .6f, .7f), box(.6f, .4f, .4f, .7f),
                box(-.1f, .4f, .6f, .7f), box(.4f, .4f, .6f, 1.1f),
                box(.4f, .1f, .6f, .3f))).state());
    }

    @Test
    public void replayedOrOutOfOrderFramesDoNotAdvanceOrRefreshTrack() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        tracker.update(frame(0, FRONT));
        tracker.update(frame(200, FRONT));
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(200, FRONT)).state());
        LeadVehicleTracker.Snapshot confirmed = tracker.update(frame(400, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING, confirmed.state());
        assertSame(confirmed, tracker.update(frame(100, FRONT)));
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(1200, FRONT)).state());
    }

    @Test
    public void cameraResolutionChangeDropsAssociation() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        confirm(tracker, 0, FRONT);
        LeadVehicleTracker.Snapshot changed = tracker.update(new VehicleDetector.Result(
                600_000_000L, 640, 480, 0, List.of(FRONT)));
        assertEquals(LeadVehicleTracker.State.CANDIDATE, changed.state());
        assertEquals(0L, changed.trackId());
    }

    @Test
    public void resetClearsCameraHistoryWithoutReusingConfirmedId() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        long id = confirm(tracker, 0, FRONT).trackId();
        tracker.reset();
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(600, FRONT)).state());
        tracker.update(frame(800, FRONT));
        LeadVehicleTracker.Snapshot reacquired = tracker.update(frame(1000, FRONT));
        assertEquals(LeadVehicleTracker.State.TRACKING, reacquired.state());
        assertNotEquals(id, reacquired.trackId());
    }

    @Test
    public void confirmationRequiresElapsedTimeEvenWithManyFrames() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        tracker.update(frame(0, FRONT));
        tracker.update(frame(50, FRONT));
        tracker.update(frame(100, FRONT));
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(150, FRONT)).state());
        assertEquals(LeadVehicleTracker.State.TRACKING,
                tracker.update(frame(400, FRONT)).state());
    }

    @Test
    public void longGapAlsoRestartsUnconfirmedCandidate() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        tracker.update(frame(0, FRONT));
        tracker.update(frame(200, FRONT));
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(1000, FRONT)).state());
        assertEquals(LeadVehicleTracker.State.CANDIDATE,
                tracker.update(frame(1200, FRONT)).state());
        assertEquals(LeadVehicleTracker.State.TRACKING,
                tracker.update(frame(1400, FRONT)).state());
    }

    @Test
    public void vehicleOutsideSearchRegionIsNoLongerAnActiveTarget() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        confirm(tracker, 0, FRONT);
        LeadVehicleTracker.Snapshot outside = tracker.update(frame(600,
                box(0.72f, 0.40f, 0.92f, 0.70f)));
        assertEquals(LeadVehicleTracker.State.LOST, outside.state());
        assertNull(outside.detection());
    }

    @Test
    public void laneGeometryKeepsNearerAdjacentVehicleFromBecomingLead() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection inLane = box(0.42f, 0.50f, 0.58f, 0.74f);
        VehicleDetector.Detection adjacent = box(0.70f, 0.68f, 0.82f, 0.92f);
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.9, true, 0.44, 0.56, 0.35, 0.65);

        assertSame(inLane, tracker.update(frame(0, adjacent, inLane), lane).detection());
        tracker.update(frame(200, adjacent, inLane), lane);
        LeadVehicleTracker.Snapshot confirmed = tracker.update(frame(400, adjacent, inLane), lane);

        assertEquals(LeadVehicleTracker.State.TRACKING, confirmed.state());
        assertSame(inLane, confirmed.detection());
    }

    @Test
    public void laneRecoveryCanReplaceAnAlreadyTrackedAdjacentVehicle() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection adjacent = box(0.70f, 0.68f, 0.82f, 0.92f);
        VehicleDetector.Detection inLane = box(0.42f, 0.50f, 0.58f, 0.74f);
        LaneDepartureDetector.Observation lane = new LaneDepartureDetector.Observation(
                0.0, 0.9, true, 0.44, 0.56, 0.35, 0.65);

        long oldId = confirm(tracker, 0, adjacent).trackId();
        assertEquals(oldId, tracker.update(frame(600, adjacent, inLane), lane).trackId());
        assertEquals(oldId, tracker.update(frame(800, adjacent, inLane), lane).trackId());

        LeadVehicleTracker.Snapshot switched = tracker.update(frame(1000, adjacent, inLane), lane);
        assertEquals(LeadVehicleTracker.State.TRACKING, switched.state());
        assertSame(inLane, switched.detection());
        assertNotEquals(oldId, switched.trackId());
    }

    @Test
    public void unavailableLanePrefersCentralVehicleOverNearerAdjacentVehicle() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection inLane = box(0.42f, 0.50f, 0.58f, 0.74f);
        VehicleDetector.Detection adjacent = box(0.70f, 0.68f, 0.82f, 0.92f);

        assertSame(inLane, tracker.update(frame(0, adjacent, inLane),
                LaneDepartureDetector.Observation.UNAVAILABLE).detection());
    }

    @Test
    public void unavailableLaneCanRecoverFromAdjacentTargetToCentralVehicle() {
        LeadVehicleTracker tracker = new LeadVehicleTracker();
        VehicleDetector.Detection adjacent = box(0.70f, 0.68f, 0.82f, 0.92f);
        VehicleDetector.Detection inLane = box(0.42f, 0.50f, 0.58f, 0.74f);
        LaneDepartureDetector.Observation unavailable = LaneDepartureDetector.Observation.UNAVAILABLE;

        long oldId = confirm(tracker, 0, adjacent).trackId();
        assertEquals(oldId, tracker.update(frame(600, adjacent, inLane), unavailable).trackId());
        assertEquals(oldId, tracker.update(frame(800, adjacent, inLane), unavailable).trackId());

        LeadVehicleTracker.Snapshot switched = tracker.update(frame(1000, adjacent, inLane), unavailable);
        assertEquals(LeadVehicleTracker.State.TRACKING, switched.state());
        assertSame(inLane, switched.detection());
        assertNotEquals(oldId, switched.trackId());
    }

    @Test
    public void calibratedHorizonAllowsDistantLeadAcrossInstallationAnglesAndResolutions() {
        for (int width : new int[] {1280, 1920}) {
            int height = width * 9 / 16;
            for (double pitch : new double[] {2.0, 8.0, 14.0}) {
                for (double hfov : new double[] {90.0, 120.0}) {
                    CameraCalibration calibration = CameraCalibration.fromWizard(
                            width, height, 1.3, hfov, pitch);
                    // Independent pinhole projection of a ground contact 22 m ahead.
                    double fy = width / (2.0 * height * Math.tan(Math.toRadians(hfov / 2.0)));
                    float bottom = (float) (0.5 + fy * Math.tan(
                            Math.atan2(1.3, 22.0) - Math.toRadians(pitch)));
                    VehicleDetector.Detection distant = box(0.46f, bottom - 0.08f, 0.54f, bottom);
                    LeadVehicleTracker tracker = new LeadVehicleTracker();
                    LeadVehicleTracker.Snapshot result = null;
                    for (int i = 0; i < 4; i++) {
                        result = tracker.update(new VehicleDetector.Result(
                                i * 200_000_000L, width, height, 0, List.of(distant)), null, calibration);
                    }
                    assertEquals(LeadVehicleTracker.State.TRACKING, result.state());
                    assertSame(distant, result.detection());
                }
            }
        }
    }

    @Test
    public void missingOrMismatchedCalibrationKeepsFallbackSearchRegion() {
        VehicleDetector.Detection distant = box(0.46f, 0.25f, 0.54f, 0.333f);
        CameraCalibration wrongSize = CameraCalibration.fromWizard(1920, 1080, 1.3, 90, 14);
        assertEquals(LeadVehicleTracker.State.NONE,
                new LeadVehicleTracker().update(frame(0, distant), null, null).state());
        assertEquals(LeadVehicleTracker.State.NONE,
                new LeadVehicleTracker().update(frame(0, distant), null, wrongSize).state());
    }

    @Test
    public void calibratedSearchRejectsAboveHorizonAndDoesNotUseVisualGuideAsCameraCenter() {
        CameraCalibration lens = CameraCalibration.fromWizard(1280, 720, 1.3, 90, 14);
        CameraCalibration shiftedGuide = new CameraCalibration(1280, 720, 1.3,
                lens.focalLengthYNormalized(), 0.5, 14, 0.62);
        VehicleDetector.Detection aboveHorizon = box(0.46f, 0.15f, 0.54f, 0.25f);
        VehicleDetector.Detection side = box(0.59f, 0.25f, 0.65f, 0.333f);
        assertEquals(LeadVehicleTracker.State.NONE, new LeadVehicleTracker().update(
                frame(0, aboveHorizon, side), null, shiftedGuide).state());
    }

    private static LeadVehicleTracker.Snapshot confirm(LeadVehicleTracker tracker,
                                                     long startMillis,
                                                     VehicleDetector.Detection detection) {
        tracker.update(frame(startMillis, detection));
        tracker.update(frame(startMillis + 200, detection));
        return tracker.update(frame(startMillis + 400, detection));
    }

    private static VehicleDetector.Detection box(float left, float top, float right, float bottom) {
        return new VehicleDetector.Detection("car", 0.9f, left, top, right, bottom);
    }

    private static VehicleDetector.Result frame(long millis, VehicleDetector.Detection... boxes) {
        return new VehicleDetector.Result(millis * 1_000_000L, 1280, 720, 0, List.of(boxes));
    }
}
