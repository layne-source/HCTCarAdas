package com.hct.adas;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.TextureView;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** App-owned Android 13+ permissions/lifecycle, with the existing native UVC capture library. */
public final class UsbCameraSource implements AutoCloseable, TextureView.SurfaceTextureListener {
    public interface Listener {
        void onDeviceAttached(UsbDevice device);
        void onDeviceConnectionChanged(UsbDevice device, boolean connected);
        void onDeviceDetached(UsbDevice device);
        void onError(String message);
    }

    private static final String TAG = "HctAdasCamera";
    private static final long SAMPLE_INTERVAL_NANOS = 200_000_000L;
    private final Activity activity;
    private final TextureView previewView;
    private final FrameDispatcher dispatcher;
    private final Listener listener;
    private final UsbManager usbManager;
    private final String permissionAction = "com.hct.adas.USB_PERMISSION." + UUID.randomUUID();
    private final HandlerThread cameraThread = new HandlerThread("adas-camera");
    private final Handler cameraHandler;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicLong invalidFrames = new AtomicLong();
    private volatile boolean running;
    private boolean closed;
    private boolean registered;
    private boolean permissionRequested;
    private boolean opening;
    private UsbDevice selectedDevice;
    private SurfaceTexture surface;
    // The camera thread exclusively owns these native resources.
    private UVCCamera camera;
    private USBMonitor monitor;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!running) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (permissionAction.equals(intent.getAction())) {
                if (isSelected(device)) {
                    if (usbManager.hasPermission(device)) {
                        tryOpen();
                    } else {
                        listener.onError("USB 摄像头授权被拒绝，请重新进入页面授权");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                selectCamera();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
                    && isSelected(device)) {
                generation.incrementAndGet();
                selectedDevice = null;
                permissionRequested = false;
                opening = false;
                dispatcher.discardPending();
                cameraHandler.post(UsbCameraSource.this::closeCamera);
                listener.onDeviceDetached(device);
                selectCamera();
            }
        }
    };

    public UsbCameraSource(Activity activity, TextureView previewView,
                           FrameDispatcher dispatcher, Listener listener) {
        this.activity = activity;
        this.previewView = previewView;
        this.dispatcher = dispatcher;
        this.listener = listener;
        usbManager = activity.getSystemService(UsbManager.class);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        previewView.setSurfaceTextureListener(this);
    }

    /** Called on the main thread after CAMERA runtime permission is granted. */
    public void start() {
        if (running || closed) {
            return;
        }
        running = true;
        generation.incrementAndGet();
        surface = previewView.isAvailable() ? previewView.getSurfaceTexture() : null;
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        registered = true;
        selectCamera();
    }

    private void selectCamera() {
        if (!running || selectedDevice != null) {
            return;
        }
        selectedDevice = usbManager.getDeviceList().values().stream()
                .filter(UsbCameraSource::isVideoDevice)
                .min(Comparator.comparing(UsbDevice::getDeviceName)).orElse(null);
        if (selectedDevice == null) {
            return;
        }
        listener.onDeviceAttached(selectedDevice);
        if (usbManager.hasPermission(selectedDevice)) {
            tryOpen();
        } else if (!permissionRequested) {
            permissionRequested = true;
            Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName());
            PendingIntent permission = PendingIntent.getBroadcast(activity, generation.get(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            try {
                usbManager.requestPermission(selectedDevice, permission);
            } catch (RuntimeException failure) {
                reportError(generation.get(), "无法申请 USB 摄像头权限", failure);
            }
        }
    }

    private static boolean isVideoDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    private boolean isSelected(UsbDevice device) {
        return device != null && selectedDevice != null
                && device.getDeviceName().equals(selectedDevice.getDeviceName());
    }

    private void tryOpen() {
        if (!running || opening || selectedDevice == null || surface == null
                || !usbManager.hasPermission(selectedDevice)) {
            return;
        }
        opening = true;
        int token = generation.get();
        UsbDevice device = selectedDevice;
        SurfaceTexture target = surface;
        cameraHandler.post(() -> openCamera(token, device, target));
    }

    private boolean isCurrent(int token) {
        return running && generation.get() == token;
    }

    private void openCamera(int token, UsbDevice device, SurfaceTexture target) {
        closeCamera();
        if (!isCurrent(token)) {
            return;
        }
        try {
            // Use only the control-block bridge; do not call the old monitor.register().
            monitor = new USBMonitor(activity.getApplicationContext(), NO_MONITOR_CALLBACKS);
            camera = new UVCCamera();
            camera.open(monitor.openDevice(device));
            int width = 1280;
            int height = 720;
            try {
                camera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (IllegalArgumentException unsupportedSize) {
                width = 640;
                height = 480;
                camera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG);
            }
            int frameWidth = width;
            int frameHeight = height;
            long[] lastSample = {0}; // Confined to the native frame callback thread.
            camera.setFrameCallback(buffer -> {
                if (!isCurrent(token)) {
                    return;
                }
                capturedFrames.incrementAndGet();
                long now = System.nanoTime();
                if (now - lastSample[0] < SAMPLE_INTERVAL_NANOS) {
                    return;
                }
                lastSample[0] = now;
                int expected = frameWidth * frameHeight * 3 / 2;
                if (buffer == null || buffer.capacity() != expected) {
                    if (invalidFrames.getAndIncrement() == 0) {
                        Log.w(TAG, "NV21 size mismatch: expected=" + expected + ", actual="
                                + (buffer == null ? 0 : buffer.capacity()));
                    }
                    return;
                }
                // Native memory is reused on return. Snapshot only the sampled frames.
                ByteBuffer view = buffer.duplicate();
                view.clear();
                byte[] nv21 = new byte[expected];
                view.get(nv21);
                if (isCurrent(token)) {
                    dispatcher.offer(nv21, frameWidth, frameHeight, now);
                }
            }, UVCCamera.PIXEL_FORMAT_NV21);
            camera.setPreviewTexture(target);
            if (!isCurrent(token)) {
                closeCamera();
                return;
            }
            camera.startPreview();
            Log.i(TAG, "Preview " + width + "x" + height + ", analysis sample rate=5 fps");
            activity.runOnUiThread(() -> {
                if (isCurrent(token)) {
                    listener.onDeviceConnectionChanged(device, true);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            closeCamera();
            reportError(token, "USB 摄像头打开失败", failure);
        }
    }

    private void reportError(int token, String message, Throwable failure) {
        Log.e(TAG, message, failure);
        activity.runOnUiThread(() -> {
            if (isCurrent(token)) {
                listener.onError(message + ": " + failure.getClass().getSimpleName());
            }
        });
    }

    public long capturedFrames() {
        return capturedFrames.get();
    }

    public long invalidFrames() {
        return invalidFrames.get();
    }

    public void stop() {
        running = false;
        generation.incrementAndGet();
        if (registered) {
            activity.unregisterReceiver(receiver);
            registered = false;
        }
        selectedDevice = null;
        permissionRequested = false;
        opening = false;
        dispatcher.discardPending();
        cameraHandler.post(this::closeCamera);
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.destroy();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Camera release failed", failure);
            } finally {
                camera = null;
            }
        }
        if (monitor != null) {
            try {
                monitor.destroy();
            } catch (RuntimeException failure) {
                Log.w(TAG, "USB control block release failed", failure);
            } finally {
                monitor = null;
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stop();
        cameraHandler.post(() -> cameraThread.quitSafely());
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = texture;
        tryOpen();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        surface = null;
        generation.incrementAndGet();
        opening = false;
        dispatcher.discardPending();
        if (!cameraHandler.post(() -> {
            closeCamera();
            texture.release();
        })) {
            texture.release();
        }
        return false;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private static final USBMonitor.OnDeviceConnectListener NO_MONITOR_CALLBACKS =
            new USBMonitor.OnDeviceConnectListener() {
                public void onAttach(UsbDevice device) { }
                public void onDettach(UsbDevice device) { }
                public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock block,
                                      boolean createNew) { }
                public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock block) { }
                public void onCancel(UsbDevice device) { }
            };
}
