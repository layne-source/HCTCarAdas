package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class FrameConsumerTest {
    @Test
    public void consumesQueuedFrameAndReportsMetrics() throws Exception {
        FrameDispatcher dispatcher = new FrameDispatcher(2);
        CountDownLatch consumed = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        FrameConsumer consumer = new FrameConsumer(dispatcher, new FrameConsumer.Handler() {
            public void onFrame(FrameDispatcher.Frame frame) { consumed.countDown(); }
            public void onStopped() { stopped.countDown(); }
        });

        try {
            consumer.start();
            dispatcher.offer(new byte[] {1}, 320, 192, 123L);
            assertTrue(consumed.await(1, TimeUnit.SECONDS));
        } finally {
            consumer.close();
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
            dispatcher.close();
        }
        FrameConsumer.Metrics metrics = consumer.metrics();
        assertEquals(1L, metrics.processedFrames());
        assertEquals(123L, metrics.lastTimestampNanos());
        // The three hand-off timestamps must be ordered and populated, otherwise the heartbeat
        // latency split would silently report zeros.
        assertTrue("hand-off must be recorded", metrics.offeredNanos() > 0L);
        assertTrue("pick-up must follow the hand-off",
                metrics.pickedUpNanos() >= metrics.offeredNanos());
        assertTrue("completion must follow the pick-up",
                metrics.finishedNanos() >= metrics.pickedUpNanos());
    }

    @Test
    public void reportsZeroHandoffTimestampsBeforeAnyFrameIsProcessed() {
        FrameDispatcher dispatcher = new FrameDispatcher(1);
        FrameConsumer consumer = new FrameConsumer(dispatcher, new FrameConsumer.Handler() {
            public void onFrame(FrameDispatcher.Frame frame) { }
        });
        try {
            FrameConsumer.Metrics metrics = consumer.metrics();
            assertEquals(0L, metrics.processedFrames());
            assertEquals(0L, metrics.offeredNanos());
            assertEquals(0L, metrics.pickedUpNanos());
            assertEquals(0L, metrics.finishedNanos());
        } finally {
            consumer.close();
            dispatcher.close();
        }
    }

    @Test
    public void restartWaitsForInFlightInferenceAndItsResourceRelease() throws Exception {
        FrameDispatcher dispatcher = new FrameDispatcher(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch bothStopped = new CountDownLatch(2);
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger releasesBeforeSecond = new AtomicInteger(-1);
        FrameConsumer consumer = new FrameConsumer(dispatcher, new FrameConsumer.Handler() {
            public void onFrame(FrameDispatcher.Frame frame) {
                if (frame.timestampNanos() == 1L) {
                    firstEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            releaseFirst.await();
                            released = true;
                        } catch (InterruptedException ignored) {
                            // Simulates a native invocation that cannot be interrupted.
                        }
                    }
                } else {
                    releasesBeforeSecond.set(releases.get());
                    secondEntered.countDown();
                }
            }
            public void onStopped() {
                releases.incrementAndGet();
                bothStopped.countDown();
            }
        });
        try {
            consumer.start();
            dispatcher.offer(new byte[] {1}, 2, 2, 1L);
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            consumer.close();
            consumer.start();
            dispatcher.offer(new byte[] {2}, 2, 2, 2L);
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, releasesBeforeSecond.get());
        } finally {
            releaseFirst.countDown();
            consumer.close();
            dispatcher.close();
        }
        assertTrue(bothStopped.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void restartSurvivesNativeReleaseFailure() throws Exception {
        FrameDispatcher dispatcher = new FrameDispatcher(2);
        CountDownLatch firstConsumed = new CountDownLatch(1);
        CountDownLatch releaseEntered = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        CountDownLatch secondConsumed = new CountDownLatch(1);
        CountDownLatch secondStopped = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        FrameConsumer consumer = new FrameConsumer(dispatcher, new FrameConsumer.Handler() {
            public void onFrame(FrameDispatcher.Frame frame) {
                if (frame.timestampNanos() == 1L) firstConsumed.countDown();
                else secondConsumed.countDown();
            }
            public void onStopped() {
                if (releases.incrementAndGet() == 1) {
                    releaseEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            allowRelease.await();
                            released = true;
                        } catch (InterruptedException ignored) {
                            // Native release may not respond to interruption.
                        }
                    }
                    throw new UnsatisfiedLinkError("native close failed");
                }
                secondStopped.countDown();
            }
        });
        try {
            consumer.start();
            dispatcher.offer(new byte[] {1}, 2, 2, 1L);
            assertTrue(firstConsumed.await(1, TimeUnit.SECONDS));
            consumer.close();
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS));
            consumer.start();
            dispatcher.offer(new byte[] {2}, 2, 2, 2L);
            assertFalse(secondConsumed.await(100, TimeUnit.MILLISECONDS));
            allowRelease.countDown();
            assertTrue(secondConsumed.await(1, TimeUnit.SECONDS));
            assertEquals(1L, consumer.metrics().failedFrames());
        } finally {
            allowRelease.countDown();
            consumer.close();
            dispatcher.close();
        }
        assertTrue(secondStopped.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void reportsInferenceFailureAndReleasesHandlerOnWorkerExit() throws Exception {
        FrameDispatcher dispatcher = new FrameDispatcher(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        FrameConsumer consumer = new FrameConsumer(dispatcher, new FrameConsumer.Handler() {
            public void onFrame(FrameDispatcher.Frame frame) {
                entered.countDown();
                throw new IllegalStateException("model contract mismatch");
            }
            public void onStopped() { stopped.countDown(); }
        });
        try {
            consumer.start();
            dispatcher.offer(new byte[] {1}, 2, 2, 123L);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            consumer.close();
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
            dispatcher.close();
        }
        assertEquals(0L, consumer.metrics().processedFrames());
        assertEquals(1L, consumer.metrics().failedFrames());
        assertTrue(consumer.metrics().lastError().contains("model contract mismatch"));
    }
}
