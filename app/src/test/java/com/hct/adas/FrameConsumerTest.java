package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class FrameConsumerTest {
    @Test
    public void consumesQueuedFrameAndReportsMetrics() throws Exception {
        FrameDispatcher dispatcher = new FrameDispatcher(2);
        CountDownLatch consumed = new CountDownLatch(1);
        FrameConsumer consumer = new FrameConsumer(dispatcher, frame -> consumed.countDown());

        consumer.start();
        dispatcher.offer(new byte[] {1}, 320, 192, 123L);

        assertTrue(consumed.await(1, TimeUnit.SECONDS));
        FrameConsumer.Metrics metrics = consumer.metrics();
        assertEquals(1L, metrics.processedFrames());
        assertEquals(123L, metrics.lastTimestampNanos());

        consumer.close();
        dispatcher.close();
    }
}
