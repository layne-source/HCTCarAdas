package com.hct.adas;

import java.util.List;

/**
 * Single forward-candidate tracker, owned by the frame consumer thread.
 * Geometry associates detections and provides a soft in-lane priority; it does not claim physical
 * target identity.
 */
public final class LeadVehicleTracker {
    public enum State { NONE, CANDIDATE, TRACKING, LOST }

    /** detection is from this frame only; LOST never exposes the previous bounding box. */
    public record Snapshot(long timestampNanos, State state, long trackId,
                           VehicleDetector.Detection detection) { }

    // Normalized image coordinates. This trapezoid is a search region, not detected lane lines.
    public static final float REGION_TOP = 0.45f;
    public static final float REGION_TOP_HALF_WIDTH = 0.10f;
    public static final float REGION_BOTTOM_HALF_WIDTH = 0.30f;
    public static final long MAX_OBSERVATION_GAP_NANOS = 750_000_000L;
    private static final long CONFIRM_NANOS = 400_000_000L;
    private static final int CONFIRM_OBSERVATIONS = 3;
    private static final float MIN_IOU = 0.30f;
    private static final float AMBIGUOUS_IOU_MARGIN = 0.08f;
    private static final float SWITCH_BOTTOM_MARGIN = 0.10f;

    private VehicleDetector.Detection target;
    private long targetId;
    private long nextId = 1L;
    private long lastSeenNanos;
    private VehicleDetector.Detection pending;
    private long pendingSinceNanos;
    private long pendingLastSeenNanos;
    private int pendingObservations;
    private int frameWidth;
    private int frameHeight;
    private Snapshot latest;

    public Snapshot update(VehicleDetector.Result frame) {
        return update(frame, null);
    }

    /**
     * Updates the tracker with the current lane geometry when it is available. Lane membership is
     * deliberately a soft score: a briefly invalid lane observation must not make an already
     * tracked vehicle disappear, and the legacy trapezoid remains the fallback before calibration.
     */
    public Snapshot update(VehicleDetector.Result frame, LaneDepartureDetector.Observation lane) {
        long now = frame.timestampNanos();
        if (latest != null && now <= latest.timestampNanos()) {
            return latest;
        }
        if (frame.frameWidth() != frameWidth || frame.frameHeight() != frameHeight) {
            reset();
            frameWidth = frame.frameWidth();
            frameHeight = frame.frameHeight();
        }
        if (target != null && now - lastSeenNanos > MAX_OBSERVATION_GAP_NANOS) {
            target = null;
            targetId = 0L;
        }

        VehicleDetector.Detection current = null;
        if (target != null) {
            Match match = findMatch(frame.vehicles());
            if (match.ambiguous()) {
                clearPending();
                return publish(now, State.LOST, targetId, null);
            }
            current = match.detection();
            if (current != null) {
                target = current;
                lastSeenNanos = now;
            }
        }

        VehicleDetector.Detection candidate = selectCandidate(frame.vehicles(), current, lane);
        if (confirmCandidate(candidate, now)) {
            target = candidate;
            targetId = nextId++;
            lastSeenNanos = now;
            current = target;
            clearPending();
        }
        if (current != null) {
            return publish(now, State.TRACKING, targetId, current);
        }
        if (target != null) {
            return publish(now, State.LOST, targetId, null);
        }
        return publish(now, candidate == null ? State.NONE : State.CANDIDATE, 0L, candidate);
    }

    /** Start a new camera session; identifiers remain unique for this tracker instance. */
    public void reset() {
        target = null;
        targetId = 0L;
        lastSeenNanos = 0L;
        clearPending();
        frameWidth = 0;
        frameHeight = 0;
        latest = null;
    }

    private Snapshot publish(long now, State state, long id, VehicleDetector.Detection box) {
        latest = new Snapshot(now, state, id, box);
        return latest;
    }

    private record Match(VehicleDetector.Detection detection, boolean ambiguous) { }

    private Match findMatch(List<VehicleDetector.Detection> detections) {
        VehicleDetector.Detection best = null;
        VehicleDetector.Detection second = null;
        float bestScore = -1f;
        float secondScore = -1f;
        for (VehicleDetector.Detection detection : detections) {
            if (!eligible(detection) || !compatible(target, detection)) {
                continue;
            }
            float score = iou(target, detection);
            if (score > bestScore) {
                second = best;
                secondScore = bestScore;
                best = detection;
                bestScore = score;
            } else if (score > secondScore) {
                second = detection;
                secondScore = score;
            }
        }
        // Similar matches to two distinct boxes are ambiguous. Near-duplicate boxes are allowed.
        boolean ambiguous = second != null && bestScore - secondScore < AMBIGUOUS_IOU_MARGIN
                && iou(best, second) < 0.80f;
        return new Match(best, ambiguous);
    }

    private static VehicleDetector.Detection selectCandidate(
            List<VehicleDetector.Detection> detections, VehicleDetector.Detection current,
            LaneDepartureDetector.Observation lane) {
        VehicleDetector.Detection best = null;
        float bestScore = -Float.MAX_VALUE;
        for (VehicleDetector.Detection detection : detections) {
            if (!eligible(detection)) {
                continue;
            }
            boolean laneRecovery = current != null && detection != current
                    && shouldRecoverToLane(current, detection, lane);
            if (current != null && (detection == current
                    || (!laneRecovery && (detection.bottom() < current.bottom() + SWITCH_BOTTOM_MARGIN
                    || iou(current, detection) >= 0.50f)))) {
                continue;
            }
            // A lower ground-contact point is only a proximity heuristic, not metric distance.
            float score = detection.bottom() - 0.30f * Math.abs(centerX(detection) - 0.5f);
            score += laneMembershipScore(detection, lane);
            if (score > bestScore || (score == bestScore && best != null
                    && detection.left() < best.left())) {
                best = detection;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Once a target is clearly outside the measured ego lane, allow an in-lane candidate to enter
     * the normal confirmation hysteresis even when it is currently farther away. Without this
     * exception the bottom-edge proximity gate permanently locks onto an adjacent near vehicle
     * after lane geometry becomes available.
     */
    private static boolean shouldRecoverToLane(VehicleDetector.Detection current,
                                               VehicleDetector.Detection candidate,
                                               LaneDepartureDetector.Observation lane) {
        return lane != null && lane.available()
                && laneMembershipScore(current, lane) < 0.0f
                && laneMembershipScore(candidate, lane) >= 0.20f;
    }

    /** Returns a bounded bonus/penalty so proximity remains useful inside the same lane. */
    private static float laneMembershipScore(VehicleDetector.Detection detection,
                                             LaneDepartureDetector.Observation lane) {
        if (lane == null || !lane.available()) {
            return 0f;
        }
        float y = detection.bottom();
        float left = (float) interpolateBoundary(lane, y, true);
        float right = (float) interpolateBoundary(lane, y, false);
        if (!Float.isFinite(left) || !Float.isFinite(right) || right <= left) {
            return 0f;
        }
        float halfWidth = Math.max(0.06f, (right - left) * 0.5f);
        float lateral = Math.abs(centerX(detection) - (left + right) * 0.5f) / halfWidth;
        if (lateral <= 1f) {
            return 0.55f * (1f - lateral);
        }
        if (lateral <= 2f) {
            return -0.20f * (lateral - 1f);
        }
        return -0.35f;
    }

    private static double interpolateBoundary(LaneDepartureDetector.Observation lane, double rowY,
                                              boolean leftSide) {
        List<LaneGeometry.WidthSample> samples = lane.widthSamples();
        if (!samples.isEmpty()) {
            LaneGeometry.WidthSample before = null;
            LaneGeometry.WidthSample after = null;
            for (LaneGeometry.WidthSample sample : samples) {
                if (sample.rowY() <= rowY) {
                    before = sample;
                }
                if (sample.rowY() >= rowY) {
                    after = sample;
                    break;
                }
            }
            if (before != null && after != null) {
                if (Math.abs(after.rowY() - before.rowY()) < 1.0e-9) {
                    return leftSide ? before.leftX() : before.rightX();
                }
                double t = (rowY - before.rowY()) / (after.rowY() - before.rowY());
                double start = leftSide ? before.leftX() : before.rightX();
                double end = leftSide ? after.leftX() : after.rightX();
                return start + t * (end - start);
            }
        }
        double top = leftSide ? lane.leftTopX() : lane.rightTopX();
        double bottom = leftSide ? lane.leftBottomX() : lane.rightBottomX();
        double span = LaneDepartureDetector.Y_BOTTOM - LaneDepartureDetector.Y_TOP;
        if (!Double.isFinite(top) || !Double.isFinite(bottom) || span <= 0.0) {
            return Double.NaN;
        }
        double t = (rowY - LaneDepartureDetector.Y_TOP) / span;
        return top + t * (bottom - top);
    }

    private boolean confirmCandidate(VehicleDetector.Detection candidate, long now) {
        if (candidate == null) {
            clearPending();
            return false;
        }
        if (pending == null || now - pendingLastSeenNanos > MAX_OBSERVATION_GAP_NANOS
                || !compatible(pending, candidate)) {
            pendingSinceNanos = now;
            pendingObservations = 0;
        }
        pending = candidate;
        pendingLastSeenNanos = now;
        pendingObservations++;
        return pendingObservations >= CONFIRM_OBSERVATIONS && now - pendingSinceNanos >= CONFIRM_NANOS;
    }

    private void clearPending() {
        pending = null;
        pendingObservations = 0;
    }

    private static boolean eligible(VehicleDetector.Detection box) {
        if (box == null || !("car".equals(box.label()) || "bus".equals(box.label())
                || "truck".equals(box.label())) || !Float.isFinite(box.confidence())
                || box.confidence() < 0.5f || box.confidence() > 1f
                || !Float.isFinite(box.left()) || !Float.isFinite(box.top())
                || !Float.isFinite(box.right()) || !Float.isFinite(box.bottom())
                || box.left() < 0f || box.top() < 0f || box.right() > 1f || box.bottom() > 1f
                || box.right() <= box.left() || box.bottom() <= box.top()
                || box.bottom() < REGION_TOP) {
            return false;
        }
        float depth = (box.bottom() - REGION_TOP) / (1f - REGION_TOP);
        float halfWidth = REGION_TOP_HALF_WIDTH
                + depth * (REGION_BOTTOM_HALF_WIDTH - REGION_TOP_HALF_WIDTH);
        return Math.abs(centerX(box) - 0.5f) <= halfWidth;
    }

    private static boolean compatible(VehicleDetector.Detection a, VehicleDetector.Detection b) {
        float aWidth = a.right() - a.left();
        float bWidth = b.right() - b.left();
        float aHeight = a.bottom() - a.top();
        float bHeight = b.bottom() - b.top();
        float aArea = aWidth * aHeight;
        float bArea = bWidth * bHeight;
        return Math.min(aArea, bArea) >= Math.max(aArea, bArea) * 0.5f
                && Math.abs(centerX(a) - centerX(b)) <= Math.max(aWidth, bWidth) * 0.5f
                && Math.abs((a.top() + a.bottom() - b.top() - b.bottom()) * 0.5f)
                <= Math.max(aHeight, bHeight) * 0.5f
                && iou(a, b) >= MIN_IOU;
    }

    private static float centerX(VehicleDetector.Detection box) {
        return (box.left() + box.right()) * 0.5f;
    }

    private static float iou(VehicleDetector.Detection a, VehicleDetector.Detection b) {
        float intersection = Math.max(0f, Math.min(a.right(), b.right()) - Math.max(a.left(), b.left()))
                * Math.max(0f, Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top()));
        float union = (a.right() - a.left()) * (a.bottom() - a.top())
                + (b.right() - b.left()) * (b.bottom() - b.top()) - intersection;
        return union > 0f ? intersection / union : 0f;
    }
}
