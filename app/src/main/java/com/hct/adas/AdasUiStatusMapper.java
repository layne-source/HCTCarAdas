package com.hct.adas;

import java.util.Locale;

/**
 * Maps the decision engine output and the measured lane geometry onto the three driver-facing
 * readouts. This is presentation only: it never changes a threshold the decision engine uses, so the
 * safety-critical gating stays testable in one place and the wording can be tuned freely here.
 *
 * <p>Sign convention for the lane offset follows the vehicle frame published by
 * {@link LaneGeometry}: the offset is positive when the vehicle sits to the right of the lane centre
 * and therefore has to move left.
 */
public final class AdasUiStatusMapper {
    /** Text shown while a judgement is still forming or blocked by missing inputs. */
    public static final String INACTIVE_TEXT = "Determined …";

    /** Risk bands, in the order of increasing severity. */
    public enum RiskLevel {
        INACTIVE,
        NORMAL,
        PROMPT,
        WARNING
    }

    public record LaneStatus(String departure, int departureColor, String keeping, int keepingColor) {
        public static final LaneStatus EMPTY = new LaneStatus("", COLOR_INACTIVE, "", COLOR_INACTIVE);
    }

    public record Status(RiskLevel risk,
                         String riskText,
                         int riskColor,
                         String detail,
                         LaneStatus lane) { }

    public static final int COLOR_NORMAL = 0xFF00E676;   // green
    public static final int COLOR_INACTIVE = 0xFFFFD54F; // yellow
    public static final int COLOR_PROMPT = 0xFFFF7043;   // orange-red
    public static final int COLOR_WARNING = 0xFFF44336;  // red

    /** TTC at or below this is an immediate warning. */
    public static final double TTC_WARNING_SECONDS = 1.2;
    /** TTC at or below this is a prompt. Above it the headway is normal. */
    public static final double TTC_PROMPT_SECONDS = 2.4;
    /** Time headway used for the prompt band when no closing speed is available. */
    public static final double THW_PROMPT_SECONDS = 1.5;
    /** Lane centre offset, in fractions of the lane width, that counts as a departure. */
    public static final double LANE_OFFSET_THRESHOLD = 0.12;
    /** Radius above which the road ahead counts as straight. */
    public static final double CURVE_STRAIGHT_RADIUS_METERS = 500.0;
    /** Radius below which the curve counts as hard. */
    public static final double CURVE_HARD_RADIUS_METERS = 200.0;

    private AdasUiStatusMapper() {
    }

    /**
     * @param decision        newest decision from the engine
     * @param targetVisible   whether a lead vehicle is currently measured
     * @param ttcSeconds      time to collision, NaN when it cannot be computed
     * @param distanceMeters  measured distance, NaN when unavailable
     * @param speedKmh        ego speed, NaN when GPS has no valid fix
     * @param headwayWarning  the engine's existing headway caution flag
     */
    public static Status forward(AdasDecisionEngine.Decision decision, boolean targetVisible,
                                 double ttcSeconds, double distanceMeters, double speedKmh,
                                 boolean headwayWarning) {
        boolean immediate = decision.collisionDanger()
                || decision.events().contains(AdasDecisionEngine.Alert.FCW)
                || decision.events().contains(AdasDecisionEngine.Alert.HMW_CRITICAL);
        RiskLevel level;
        String detail;
        if (immediate) {
            level = RiskLevel.WARNING;
            detail = "collision imminent";
        } else if (!Double.isFinite(speedKmh)) {
            // Distance judgement needs the ego speed for a time headway; without it the band stays
            // undecided rather than being reported as normal.
            level = RiskLevel.INACTIVE;
            detail = "ego speed unavailable";
        } else if (!targetVisible || !Double.isFinite(distanceMeters)) {
            level = RiskLevel.INACTIVE;
            detail = "no lead vehicle";
        } else {
            double thw = speedKmh > 1.0 ? distanceMeters / (speedKmh / 3.6) : Double.NaN;
            if (Double.isFinite(ttcSeconds) && ttcSeconds <= TTC_WARNING_SECONDS) {
                // The engine confirms a collision danger only after several frames and above its own
                // speed gate; the readout shows the same band as soon as the measured TTC is inside the
                // warning threshold, so the driver text never lags the geometry.
                level = RiskLevel.WARNING;
                detail = String.format(java.util.Locale.ROOT, "TTC %.1f s", ttcSeconds);
            } else {
                boolean prompt = (Double.isFinite(ttcSeconds) && ttcSeconds <= TTC_PROMPT_SECONDS)
                        || headwayWarning
                        || (Double.isFinite(thw) && thw <= THW_PROMPT_SECONDS);
                if (prompt) {
                    level = RiskLevel.PROMPT;
                } else if (Double.isFinite(ttcSeconds)) {
                    level = RiskLevel.NORMAL;
                } else {
                    // A lead vehicle is measured but no closing speed exists yet: undecided.
                    level = RiskLevel.INACTIVE;
                }
                detail = Double.isFinite(ttcSeconds)
                        ? String.format(java.util.Locale.ROOT, "TTC %.1f s", ttcSeconds)
                        : "closing speed unavailable";
            }
        }
        return new Status(level, riskText(level), riskColor(level), detail, LaneStatus.EMPTY);
    }

    public static String riskText(RiskLevel level) {
        return switch (level) {
            case WARNING -> "Warning Risk";
            case PROMPT -> "Prompt Risk";
            case NORMAL -> "Normal Risk";
            case INACTIVE -> INACTIVE_TEXT;
        };
    }

    public static int riskColor(RiskLevel level) {
        return switch (level) {
            case WARNING -> COLOR_WARNING;
            case PROMPT -> COLOR_PROMPT;
            case NORMAL -> COLOR_NORMAL;
            case INACTIVE -> COLOR_INACTIVE;
        };
    }

    /**
     * Builds the LDWS and LKAS readouts.
     *
     * @param lane         measured lane geometry, or null when lanes are not tracked
     * @param supported    whether the current calibration allows lane geometry at all
     * @param laneSteering the engine's lane warning flag, used to colour the departure text
     */
    public static LaneStatus lane(LaneGeometry.LaneSnapshot lane, boolean supported,
                                  boolean laneSteering) {
        if (!supported || lane == null || !lane.valid()) {
            String departure = supported ? "To Be Determined …" : "Not Calibrated";
            return new LaneStatus(departure, COLOR_INACTIVE,
                    "To Be Determined …", COLOR_INACTIVE);
        }
        boolean departed = Math.abs(lane.centerOffsetNormalized()) >= LANE_OFFSET_THRESHOLD;
        String departure;
        if (departed) {
            // The offset is signed in the vehicle frame, positive to the right of the lane centre, so
            // the instruction names the opposite direction of travel.
            departure = lane.vehicleRightOfCenter() ? "Please Keep Left" : "Please Keep Right";
        } else {
            departure = "Good Lane Keeping";
        }
        int departureColor = departed
                ? (laneSteering ? COLOR_WARNING : COLOR_PROMPT)
                : COLOR_NORMAL;
        return new LaneStatus(departure, departureColor,
                keepingText(lane.curvatureRadiusMeters()),
                keepingColor(lane.curvatureRadiusMeters()));
    }

    public static String keepingText(double curvatureRadiusMeters) {
        if (!Double.isFinite(curvatureRadiusMeters)) {
            return "To Be Determined …";
        }
        double magnitude = Math.abs(curvatureRadiusMeters);
        if (magnitude > CURVE_STRAIGHT_RADIUS_METERS) {
            return "Keep Straight Ahead";
        }
        String direction = curvatureRadiusMeters < 0.0 ? "Left" : "Right";
        return magnitude <= CURVE_HARD_RADIUS_METERS
                ? "Hard " + direction + " Curve Ahead"
                : "Gentle " + direction + " Curve Ahead";
    }

    public static int keepingColor(double curvatureRadiusMeters) {
        if (!Double.isFinite(curvatureRadiusMeters)) {
            return COLOR_INACTIVE;
        }
        double magnitude = Math.abs(curvatureRadiusMeters);
        if (magnitude > CURVE_STRAIGHT_RADIUS_METERS) {
            return COLOR_NORMAL;
        }
        return magnitude <= CURVE_HARD_RADIUS_METERS ? COLOR_WARNING : COLOR_PROMPT;
    }

    /**
     * Converts the lane offset into metres using the measured lane width, so the driver readout does
     * not depend on the nominal width prior alone. The sign follows the vehicle frame: positive means
     * the vehicle sits right of the lane centre.
     */
    public static double offsetMeters(LaneGeometry.LaneSnapshot lane) {
        if (lane == null || !lane.valid() || !Double.isFinite(lane.laneWidthMeters())) {
            return Double.NaN;
        }
        return lane.centerOffsetMeters();
    }

    /** Numeric lane detail, omitted until calibrated metric geometry is available. */
    public static String laneDetail(LaneGeometry.LaneSnapshot lane) {
        if (lane == null || !lane.valid() || !Double.isFinite(lane.centerOffsetMeters())) {
            return "";
        }
        StringBuilder builder = new StringBuilder(String.format(Locale.ROOT,
                " · Offset %+.2f m", lane.centerOffsetMeters()));
        if (Double.isFinite(lane.laneWidthMeters())) {
            builder.append(String.format(Locale.ROOT, " (lane %.1f m)", lane.laneWidthMeters()));
        }
        builder.append(lane.curvatureValid()
                ? String.format(Locale.ROOT, " · R %.0f m", Math.abs(lane.curvatureRadiusMeters()))
                : " · R straight");
        return builder.toString();
    }
}
