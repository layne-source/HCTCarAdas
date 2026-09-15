package com.hct.adas;

import android.app.Activity;
import android.hardware.usb.UsbDevice;

import com.hct.usbcamera.UVCCameraHelper;
import com.serenegiant.usb.common.AbstractUVCCameraHandler;
import com.serenegiant.usb.widget.CameraViewInterface;

/** Bridges the existing UVC library to the ADAS frame dispatcher. */
public final class UsbCameraSource implements AutoCloseable {
    public interface Listener {
        void onDeviceAttached(UsbDevice device);

        void onDeviceConnectionChanged(UsbDevice device, boolean connected);

        void onDeviceDetached(UsbDevice device);
    }

    private static final int PREVIEW_WIDTH = 1280;
    private static final int PREVIEW_HEIGHT = 720;

    private final Activity activity;
    private final CameraViewInterface previewView;
    private final FrameDispatcher dispatcher;
    private final Listener listener;
    private final UVCCameraHelper.OnMyDevConnectListener deviceListener =
            new UVCCameraHelper.OnMyDevConnectListener() {
                @Override
                public void onAttachDev(UsbDevice device) {
                    listener.onDeviceAttached(device);
                }

                @Override
                public void onDettachDev(UsbDevice device) {
                    listener.onDeviceDetached(device);
                }

                @Override
                public void onConnectDev(UsbDevice device, boolean isConnected) {
                    listener.onDeviceConnectionChanged(device, isConnected);
                }

                @Override
                public void onDisConnectDev(UsbDevice device) {
                    listener.onDeviceConnectionChanged(device, false);
                }
            };

    private UVCCameraHelper cameraHelper;
    private boolean initialized;

    public UsbCameraSource(
            Activity activity,
            CameraViewInterface previewView,
            FrameDispatcher dispatcher,
            Listener listener) {
        this.activity = activity;
        this.previewView = previewView;
        this.dispatcher = dispatcher;
        this.listener = listener;
    }

    public void start() {
        if (!initialized) {
            cameraHelper = UVCCameraHelper.getInstance();
            cameraHelper.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_MJPEG);
            cameraHelper.setDefaultPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            cameraHelper.initUSBMonitor(activity, previewView, deviceListener);
            cameraHelper.setOnPreviewFrameListener(
                    new AbstractUVCCameraHandler.OnPreViewResultListener() {
                        @Override
                        public void onPreviewResult(byte[] nv21Yuv) {
                            dispatcher.offer(
                                    nv21Yuv,
                                    PREVIEW_WIDTH,
                                    PREVIEW_HEIGHT,
                                    System.nanoTime());
                        }
                    });
            initialized = true;
        }
        cameraHelper.registerUSB();
    }

    public void stop() {
        if (cameraHelper != null) {
            cameraHelper.unregisterUSB();
        }
    }

    @Override
    public void close() {
        if (cameraHelper != null) {
            cameraHelper.unregisterUSB();
            cameraHelper.closeCamera();
            cameraHelper.release();
            cameraHelper = null;
        }
        initialized = false;
    }
}
