package com.hct.adas;

import java.util.Objects;

/** Runs frame processing away from the UVC callback thread. */
public final class FrameConsumer implements AutoCloseable {
    public interface Handler {
        void onFrame(FrameDispatcher.Frame frame);

        /** Called on the same worker after its last inference, including on failure. */
        default void onStopped() {
        }
    }

    /**
     * @param lastTimestampNanos capture timestamp of the newest processed frame
     * @param pickedUpNanos      {@link System#nanoTime()} when the worker dequeued that frame
     * @param finishedNanos      {@link System#nanoTime()} when the worker finished handling it
     * @param offeredNanos       {@link System#nanoTime()} when the producer handed that frame over
     */
    public record Metrics(long processedFrames, long lastTimestampNanos,
                          long offeredNanos, long pickedUpNanos, long finishedNanos,
                          long failedFrames, String lastError) {
    }

    private final FrameDispatcher dispatcher;
    private final Handler handler;
    private long processedFrames;
    private long lastTimestampNanos;
    private long offeredNanos;
    private long pickedUpNanos;
    private long finishedNanos;
    private long failedFrames;
    private String lastError = "";
    private boolean desiredRunning;
    private Session session;

    private static final class Session {
        volatile boolean cancelled;
        Thread thread;
    }

    public FrameConsumer(FrameDispatcher dispatcher, Handler handler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public synchronized void start() {
        desiredRunning = true;
        if (session == null) {
            launchWorker();
        }
    }

    private void launchWorker() {
        Session next = new Session();
        session = next;
        next.thread = new Thread(() -> runLoop(next), "adas-frame-consumer");
        next.thread.start();
    }

    private void runLoop(Session current) {
        int consecutiveFailures = 0;
        try {
            while (!current.cancelled) {
                try {
                    FrameDispatcher.Frame frame = dispatcher.await(500L);
                    if (current.cancelled) {
                        break;
                    }
                    if (frame == null) {
                        continue;
                    }
                    // The hand-off time travels with the frame: it splits that frame's total age into
                    // "waiting to be picked up" and "being processed", so a latency regression can be
                    // attributed to the queue or to the pipeline instead of only to their sum.
                    long offered = frame.offeredAtNanos();
                    long pickedUp = System.nanoTime();
                    handler.onFrame(frame);
                    long finished = System.nanoTime();
                    consecutiveFailures = 0;
                    synchronized (this) {
                        lastTimestampNanos = frame.timestampNanos();
                        offeredNanos = offered;
                        pickedUpNanos = pickedUp;
                        finishedNanos = finished;
                        processedFrames++;
                        lastError = "";
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    consecutiveFailures++;
                    recordFailure(failure);
                    if (consecutiveFailures >= 3) {
                        // Break loop to trigger handler.onStopped() cleanup and self-healing restart
                        break;
                    }
                }
            }
        } finally {
            try {
                handler.onStopped();
            } catch (RuntimeException | LinkageError failure) {
                recordFailure(failure);
            }
            synchronized (this) {
                session = null;
                // Restart only after the previous worker releases its interpreter.
                if (desiredRunning) {
                    launchWorker();
                }
            }
        }
    }

    private synchronized void recordFailure(Throwable failure) {
        failedFrames++;
        lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    public synchronized Metrics metrics() {
        return new Metrics(processedFrames, lastTimestampNanos, offeredNanos, pickedUpNanos,
                finishedNanos, failedFrames, lastError);
    }

    @Override
    public synchronized void close() {
        desiredRunning = false;
        if (session != null) {
            session.cancelled = true;
            session.thread.interrupt();
        }
    }
}
