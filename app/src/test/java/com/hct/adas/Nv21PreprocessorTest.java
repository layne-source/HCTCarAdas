package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Test;

public final class Nv21PreprocessorTest {
    @Test
    public void preservesAspectRatioAndMapsBoxesBackToTheCameraFrame() {
        Nv21Preprocessor.Transform transform = Nv21Preprocessor.transform(1280, 720, 320);
        assertEquals(320, transform.width());
        assertEquals(180, transform.height());
        assertEquals(70, transform.top());
        assertEquals(0f, transform.sourceY(70f / 320f), 0.00001f);
        assertEquals(1f, transform.sourceY(250f / 320f), 0.00001f);
    }

    @Test
    public void convertsNv21VBeforeUToRgbWithoutSwappingRedAndBlue() {
        // BT.601 limited-range red: Y=81, V=240, U=90.
        byte[] frame = {(byte) 81, (byte) 81, (byte) 81, (byte) 81,
                (byte) 240, (byte) 90};
        ByteBuffer rgb = ByteBuffer.allocateDirect(12);
        Nv21Preprocessor.fill(frame, 2, 2, 2, rgb);
        assertEquals(255, rgb.get(0) & 255);
        assertEquals(0, rgb.get(1) & 255);
        assertEquals(0, rgb.get(2) & 255);
    }

    @Test
    public void addsPaddingWithoutChangingInputPixels() {
        byte[] frame = new byte[12]; // 4x2 NV21 black.
        Arrays.fill(frame, 0, 8, (byte) 16);
        Arrays.fill(frame, 8, 12, (byte) 128);
        ByteBuffer rgb = ByteBuffer.allocateDirect(48);
        Nv21Preprocessor.fill(frame, 4, 2, 4, rgb);
        assertEquals(114, rgb.get(0) & 255);
        assertEquals(0, rgb.get(12) & 255);
        assertEquals(114, rgb.get(36) & 255);
        assertEquals(0, rgb.position());
    }

    @Test
    public void rejectsMalformedFrameBeforeIndexingItsPlanes() {
        assertThrows(IllegalArgumentException.class,
                () -> Nv21Preprocessor.fill(new byte[5], 2, 2, 2, ByteBuffer.allocate(12)));
        assertThrows(IllegalArgumentException.class,
                () -> Nv21Preprocessor.transform(3, 2, 320));
    }
}
