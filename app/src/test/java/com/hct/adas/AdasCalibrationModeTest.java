package com.hct.adas;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdasCalibrationModeTest {
    private static final CameraCalibration CALIBRATION = new CameraCalibration(
            1280, 720, 1.55, 0.90, 0.50, 8.0);

    @Test
    public void distanceWarningsAreReadyAfterInstallationWizard() {
        assertTrue(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.DISTANCE_READY, CALIBRATION, 1280, 720));
    }

    @Test
    public void distanceWarningsStayDisabledForMissingOrMismatchedCalibration() {
        assertFalse(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.DISTANCE_READY, null, 1280, 720));
        assertFalse(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.DISTANCE_READY, CALIBRATION, 1920, 1080));
    }

    @Test
    public void existingWizardProfileIsReadyWithoutOnlineLaneLearning() {
        assertTrue(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.WIZARD_COMPLETED, CALIBRATION, 1280, 720));
    }

    @Test
    public void distanceWarningsRejectProfilesOutsideConfirmedAlignment() {
        for (double pitch : new double[] {0.0, 1.99, 14.01, 45.0}) {
            assertFalse(AdasCalibrationMode.distanceReady(
                    CalibrationStore.Status.DISTANCE_READY,
                    CALIBRATION.withPitchDegrees(pitch), 1280, 720));
        }
        CameraCalibration offCenter = new CameraCalibration(
                1280, 720, 1.55, 0.90, 0.50, 8.0, 0.63);
        assertFalse(AdasCalibrationMode.distanceReady(
                CalibrationStore.Status.CALIBRATED, offCenter, 1280, 720));
        for (double pitch : new double[] {2.0, 14.0}) {
            assertTrue(AdasCalibrationMode.distanceReady(
                    CalibrationStore.Status.DISTANCE_READY,
                    CALIBRATION.withPitchDegrees(pitch), 1280, 720));
        }
    }

    @Test
    public void lightweightProductDoesNotEnableLaneDepartureBranch() {
        assertFalse(AdasCalibrationMode.LDW_ENABLED);
    }
}
