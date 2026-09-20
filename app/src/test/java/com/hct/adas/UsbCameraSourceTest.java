package com.hct.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.List;

public final class UsbCameraSourceTest {
    @Test
    public void cameraSelectionRequiresOneUnambiguousVideoDevice() {
        assertNull(UsbCameraSource.singleCameraName(List.of()));
        assertEquals("/dev/bus/usb/002/004", UsbCameraSource.singleCameraName(
                List.of("/dev/bus/usb/002/004")));
        assertNull(UsbCameraSource.singleCameraName(
                List.of("/dev/bus/usb/001/002", "/dev/bus/usb/002/004")));
        assertNull(UsbCameraSource.singleCameraName(
                List.of("/dev/bus/usb/002/004", "/dev/bus/usb/001/002")));
    }

    @Test
    public void backgroundDisconnectCannotSilentlySelectAnotherCamera() {
        assertTrue(UsbCameraSource.needsConfirmationOnResume(false,
                "/dev/bus/usb/001/002", List.of("/dev/bus/usb/002/004")));
    }

    @Test
    public void foregroundConfirmationSurvivesStopAndStart() {
        assertTrue(UsbCameraSource.needsConfirmationOnResume(true, null,
                List.of("/dev/bus/usb/002/004")));
    }

    @Test
    public void unchangedCameraAndFirstConnectionDoNotRequireConfirmation() {
        assertFalse(UsbCameraSource.needsConfirmationOnResume(false,
                "/dev/bus/usb/001/002", List.of("/dev/bus/usb/001/002")));
        assertFalse(UsbCameraSource.needsConfirmationOnResume(false, null,
                List.of("/dev/bus/usb/001/002")));
    }

    @Test
    public void noRemainingCameraClearsOldConfirmation() {
        assertFalse(UsbCameraSource.needsConfirmationOnResume(true,
                "/dev/bus/usb/001/002", List.of()));
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
