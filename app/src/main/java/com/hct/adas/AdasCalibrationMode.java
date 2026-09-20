package com.hct.adas;

/** Product calibration gates for the lightweight, front-vehicle-only release. */
public final class AdasCalibrationMode {
    /** Lane departure is intentionally disabled in this lightweight product profile. */
    public static final boolean LDW_ENABLED = false;

    private AdasCalibrationMode() {
    }

    /**
     * Distance warnings use the installation profile immediately after the wizard is saved.
     * WIZARD_COMPLETED and CALIBRATED remain accepted so an existing valid profile is not
     * incorrectly shown as unavailable after switching off online lane learning.
     */
    public static boolean distanceReady(CalibrationStore.Status status,
                                        CameraCalibration calibration,
                                        int frameWidth, int frameHeight) {
        if (!CalibrationAlignment.isValid(calibration)
                || !calibration.isUsableFor(frameWidth, frameHeight)) {
            return false;
        }
        return status == CalibrationStore.Status.WIZARD_COMPLETED
                || status == CalibrationStore.Status.DISTANCE_READY
                || status == CalibrationStore.Status.CALIBRATED;
    }
}
