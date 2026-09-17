package com.hct.adas;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Bounded handoff queue between the UVC preview callback and the ADAS worker.
 * The newest frames are preferred because stale frames are not useful for warnings.
 */
public final class FrameDispatcher implements AutoCloseable {
    public record Metrics(long offeredFrames, long droppedFrames, long discardedFrames) {
        public Metrics(long offeredFrames, long droppedFrames) {
            this(offeredFrames, droppedFrames, 0L);
        }
    }
    private final ArrayDeque<Frame> frames;
    private final int capacity;
    private long offeredFrames;
    private long droppedFrames;
    private long discardedFrames;
    private boolean closed;

    public FrameDispatcher(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.frames = new ArrayDeque<>(capacity);
    }

    /**
     * Enqueues a frame. When full, the oldest frame is dropped.
     * The byte array ownership is transferred to this dispatcher.
     */
    public synchronized boolean offer(byte[] nv21, int width, int height, long timestampNanos) {
        Objects.requireNonNull(nv21, "nv21");
        if (closed) {
            return false;
        }
        offeredFrames++;
        if (frames.size() == capacity) {
            frames.removeFirst();
            droppedFrames++;
        }
        // Wall clock at hand-off travels with the frame so the consumer can split the end-to-end
        // age into a queueing part and a processing part. It is per-frame rather than a shared
        // field: the producer may hand over the next frame while this one is still being handled.
        frames.addLast(new Frame(nv21, width, height, timestampNanos, System.nanoTime()));
        notifyAll();
        return true;
    }

    public synchronized Frame poll() {
        return frames.pollFirst();
    }

    /**
     * Waits for a frame or until the timeout expires. A closed and empty
     * dispatcher returns null immediately.
     */
    public synchronized Frame await(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        long deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (frames.isEmpty() && !closed) {
            if (timeoutMillis == 0) {
                return null;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }
            // Object.wait(0, n) blocks for n nanoseconds, but wait(0) blocks forever, so the
            // sub-millisecond remainder must stay explicit. This call is written in the nanos-only
            // form whenever the millisecond part is zero, because collapsing it to wait(millis)
            // would silently turn the wait into an unbounded one.
            long waitMillis = remainingNanos / 1_000_000L;
            int waitNanos = (int) (remainingNanos % 1_000_000L);
            if (waitNanos > 0) {
                wait(waitMillis, waitNanos);
            } else {
                wait(waitMillis);
            }
        }
        return frames.pollFirst();
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized Metrics metrics() {
        return new Metrics(offeredFrames, droppedFrames, discardedFrames);
    }

    public synchronized void discardPending() {
        // Deliberate invalidation (disconnect, reopen, session reset) is not queue overflow.
        // Keeping the counters apart lets the UI show congestion and stream loss separately.
        discardedFrames += frames.size();
        frames.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        frames.clear();
        notifyAll();
    }

    /**
     * @param timestampNanos capture timestamp, used for analysis ordering and staleness checks
     * @param offeredAtNanos {@link System#nanoTime()} when the producer handed the frame over
     */
    public record Frame(byte[] nv21, int width, int height, long timestampNanos,
                        long offeredAtNanos) {
        public Frame {
            Objects.requireNonNull(nv21, "nv21");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("frame dimensions must be positive");
            }
        }
    }
}
