package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UsbCameraSourceTest {
    @Test
    public void cameraIdPrefersSerialNumberOverDevicePath() {
        assertEquals("usb:1234:5678:serial:SN-42",
                UsbCameraSource.buildCameraId(1234, 5678, " SN-42 ", "/dev/bus/usb/001/002"));
    }

    @Test
    public void cameraIdFailsClosedWhenSerialIsUnavailable() {
        assertEquals("",
                UsbCameraSource.buildCameraId(1234, 5678, "", "/dev/bus/usb/001/002"));
    }

    @Test
    public void cameraIdFailsClosedWhenNeitherSerialNorPathIsAvailable() {
        assertEquals("", UsbCameraSource.buildCameraId(1234, 5678, null, " "));
    }

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
