package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FrameDispatcherTest {
    @Test
    public void keepsNewestFramesWhenQueueIsFull() {
        FrameDispatcher dispatcher = new FrameDispatcher(2);

        assertTrue(dispatcher.offer(new byte[] {1}, 640, 480, 1L));
        assertTrue(dispatcher.offer(new byte[] {2}, 640, 480, 2L));
        assertTrue(dispatcher.offer(new byte[] {3}, 640, 480, 3L));

        assertEquals(2, dispatcher.size());
        assertEquals(2L, dispatcher.poll().timestampNanos());
        assertEquals(3L, dispatcher.poll().timestampNanos());
        assertNull(dispatcher.poll());
    }

    @Test
    public void rejectsFramesAfterClose() {
        FrameDispatcher dispatcher = new FrameDispatcher(1);
        dispatcher.close();

        assertFalse(dispatcher.offer(new byte[] {1}, 1, 1, 1L));
        assertNull(dispatcher.poll());
    }

    @Test
    public void awaitReturnsQueuedFrame() throws InterruptedException {
        FrameDispatcher dispatcher = new FrameDispatcher(1);
        dispatcher.offer(new byte[] {7}, 320, 192, 7L);

        FrameDispatcher.Frame frame = dispatcher.await(50L);

        assertNotNull(frame);
        assertEquals(7L, frame.timestampNanos());
        assertEquals(320, frame.width());
        assertEquals(192, frame.height());
    }
}
