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

    public record Metrics(long processedFrames, long lastTimestampNanos,
                          long failedFrames, String lastError) {
    }

    private final FrameDispatcher dispatcher;
    private final Handler handler;
    private long processedFrames;
    private long lastTimestampNanos;
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
                    handler.onFrame(frame);
                    consecutiveFailures = 0;
                    synchronized (this) {
                        lastTimestampNanos = frame.timestampNanos();
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
            } catch (RuntimeException failure) {
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

    private synchronized void recordFailure(RuntimeException failure) {
        failedFrames++;
        lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    public synchronized Metrics metrics() {
        return new Metrics(processedFrames, lastTimestampNanos, failedFrames, lastError);
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
