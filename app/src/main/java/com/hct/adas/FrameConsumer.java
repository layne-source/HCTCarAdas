package com.hct.adas;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Runs frame processing away from the UVC callback thread. */
public final class FrameConsumer implements AutoCloseable {
    public interface Handler {
        void onFrame(FrameDispatcher.Frame frame);
    }

    public record Metrics(long processedFrames, long lastTimestampNanos) {
    }

    private final FrameDispatcher dispatcher;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong processedFrames = new AtomicLong();
    private final AtomicLong lastTimestampNanos = new AtomicLong();
    private Thread worker;

    public FrameConsumer(FrameDispatcher dispatcher, Handler handler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        worker = new Thread(this::runLoop, "adas-frame-consumer");
        worker.start();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                FrameDispatcher.Frame frame = dispatcher.await(500L);
                if (frame == null) {
                    continue;
                }
                handler.onFrame(frame);
                lastTimestampNanos.set(frame.timestampNanos());
                processedFrames.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // A bad frame must not terminate the long-lived camera consumer.
            }
        }
    }

    public Metrics metrics() {
        return new Metrics(processedFrames.get(), lastTimestampNanos.get());
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }
}
