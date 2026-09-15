package com.hct.adas;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Bounded handoff queue between the UVC preview callback and the ADAS worker.
 * The newest frames are preferred because stale frames are not useful for warnings.
 */
public final class FrameDispatcher implements AutoCloseable {
    public record Metrics(long offeredFrames, long droppedFrames) {
    }

    private final ArrayDeque<Frame> frames;
    private final int capacity;
    private long offeredFrames;
    private long droppedFrames;
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
        frames.addLast(new Frame(nv21, width, height, timestampNanos));
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
            long waitMillis = remainingNanos / 1_000_000L;
            int waitNanos = (int) (remainingNanos % 1_000_000L);
            wait(waitMillis, waitNanos);
        }
        return frames.pollFirst();
    }

    public synchronized int size() {
        return frames.size();
    }

    public synchronized Metrics metrics() {
        return new Metrics(offeredFrames, droppedFrames);
    }

    public synchronized void discardPending() {
        droppedFrames += frames.size();
        frames.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        frames.clear();
        notifyAll();
    }

    public record Frame(byte[] nv21, int width, int height, long timestampNanos) {
        public Frame {
            Objects.requireNonNull(nv21, "nv21");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("frame dimensions must be positive");
            }
        }
    }
}
