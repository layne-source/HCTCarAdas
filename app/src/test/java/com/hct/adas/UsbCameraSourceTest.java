package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UsbCameraSourceTest {
    @Test
    public void previewCandidatesIncludeMjpegAndYuyvFallbacks() {
        UsbCameraSource.PreviewConfig[] candidates = UsbCameraSource.previewCandidates();

        assertTrue(candidates.length >= 4);
        assertEquals(1280, candidates[0].width);
        assertEquals(720, candidates[0].height);
        assertEquals(com.serenegiant.usb.UVCCamera.FRAME_FORMAT_MJPEG, candidates[0].format);

        boolean hasYuyv = false;
        for (UsbCameraSource.PreviewConfig candidate : candidates) {
            hasYuyv |= candidate.format == com.serenegiant.usb.UVCCamera.FRAME_FORMAT_YUYV;
        }
        assertTrue(hasYuyv);
    }

    @Test
    public void surfaceTextureDestroyedCallbackOwnsReleaseWhenReturningFalse() {
        assertFalse(UsbCameraSource.surfaceTextureDestroyedReturnValue());
    }
}
