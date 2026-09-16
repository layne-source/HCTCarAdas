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
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final long FRAME_WATCHDOG_INTERVAL_MILLIS = 1_000L;
    private static final long FRAME_WATCHDOG_TIMEOUT_NANOS = 3_000_000_000L;
    private static final long STREAM_STABLE_NANOS = 5_000_000_000L;
    private final Activity activity;
    private final TextureView previewView;
    private final FrameDispatcher dispatcher;
    private final Listener listener;
    private final UsbManager usbManager;
    private final String permissionAction = "com.hct.adas.USB_PERMISSION." + UUID.randomUUID();
    private final HandlerThread cameraThread = new HandlerThread("adas-camera");
    private final Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicLong invalidFrames = new AtomicLong();
    private volatile boolean running;
    private boolean closed;
    private boolean registered;
    private boolean permissionRequested;
    private volatile boolean opening;
    private volatile int openRetryCount;
    private volatile boolean previewActive;
    private volatile long previewStartedNanos;
    private volatile long lastFrameNanos;
    private boolean streamConfirmed;
    private UsbDevice selectedDevice;
    private SurfaceTexture surface;
    // The camera thread exclusively owns these native resources.
    private UVCCamera camera;
    private USBMonitor monitor;

    private final Runnable frameWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!running || closed) {
                return;
            }
            if (previewActive) {
                long now = System.nanoTime();
                long reference = lastFrameNanos > 0L ? lastFrameNanos : previewStartedNanos;
                if (reference > 0L && now - reference >= FRAME_WATCHDOG_TIMEOUT_NANOS) {
                    int token = invalidatePreview();
                    cameraHandler.post(UsbCameraSource.this::closeCamera);
                    listener.onError(openRetryCount < 3
                            ? "USB 摄像头视频流中断，正在自动重连"
                            : "USB 摄像头视频流中断，自动重试已用尽，请点击重试");
                    scheduleOpenRetry(token);
                } else if (streamConfirmed && lastFrameNanos - previewStartedNanos >= STREAM_STABLE_NANOS) {
                    // A single frame followed by another outage must not replenish the retry budget.
                    openRetryCount = 0;
                }
            }
            mainHandler.postDelayed(this, FRAME_WATCHDOG_INTERVAL_MILLIS);
        }
    };

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
                        listener.onError("USB 摄像头授权被拒绝，请点击重试授权");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                selectCamera();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
                    && isSelected(device)) {
                invalidatePreview();
                selectedDevice = null;
                permissionRequested = false;
                openRetryCount = 0;
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
        mainHandler.removeCallbacks(frameWatchdog);
        mainHandler.postDelayed(frameWatchdog, FRAME_WATCHDOG_INTERVAL_MILLIS);
        openRetryCount = 0;
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
            requestPermission(selectedDevice);
        }
    }

    private void requestPermission(UsbDevice device) {
        permissionRequested = true;
        Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName());
        PendingIntent permission = PendingIntent.getBroadcast(activity, generation.get(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try {
            usbManager.requestPermission(device, permission);
        } catch (RuntimeException failure) {
            permissionRequested = false;
            reportError(generation.get(), "无法申请 USB 摄像头权限", failure);
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
        // All callers run on the main thread; device and surface snapshots belong to this token.
        if (!running || closed || opening || previewActive || selectedDevice == null || surface == null
                || !usbManager.hasPermission(selectedDevice)) {
            return;
        }
        int token = invalidatePreview();
        opening = true;
        UsbDevice device = selectedDevice;
        SurfaceTexture target = surface;
        cameraHandler.post(() -> openCamera(token, device, target));
    }

    private boolean isCurrent(int token) {
        return running && generation.get() == token;
    }

    private void openCamera(int token, UsbDevice device, SurfaceTexture target) {
        if (!isCurrent(token)) {
            return;
        }
        closeCamera();
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
            AtomicBoolean receivedFrame = new AtomicBoolean();
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
                synchronized (dispatcher) {
                    if (!isCurrent(token)) {
                        return;
                    }
                    lastFrameNanos = now;
                    dispatcher.offer(nv21, frameWidth, frameHeight, now);
                }
                if (receivedFrame.compareAndSet(false, true)) {
                    mainHandler.post(() -> confirmStream(token, device));
                }
            }, UVCCamera.PIXEL_FORMAT_NV21);
            camera.setPreviewTexture(target);
            if (!isCurrent(token)) {
                closeCamera();
                return;
            }
            long previewStart = System.nanoTime();
            camera.startPreview();
            if (!isCurrent(token)) {
                closeCamera();
                return;
            }
            Log.i(TAG, "Preview " + width + "x" + height + ", analysis sample rate=5 fps");
            mainHandler.post(() -> {
                if (isCurrent(token)) {
                    previewStartedNanos = previewStart;
                    previewActive = true;
                    opening = false;
                    confirmStream(token, device);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            closeCamera();
            Log.e(TAG, "USB camera open failed", failure);
            mainHandler.post(() -> {
                if (isCurrent(token)) {
                    opening = false;
                    previewActive = false;
                    listener.onError("USB 摄像头打开失败: " + failure.getClass().getSimpleName());
                    scheduleOpenRetry(token);
                }
            });
        }
    }

    private void confirmStream(int token, UsbDevice device) {
        if (isCurrent(token) && previewActive && lastFrameNanos > 0L && !streamConfirmed) {
            streamConfirmed = true;
            listener.onDeviceConnectionChanged(device, true);
        }
    }

    private int invalidatePreview() {
        int token = generation.incrementAndGet();
        opening = false;
        previewActive = false;
        streamConfirmed = false;
        previewStartedNanos = 0L;
        synchronized (dispatcher) {
            lastFrameNanos = 0L;
            dispatcher.discardPending();
        }
        return token;
    }

    private void scheduleOpenRetry(int token) {
        if (!isCurrent(token) || surface == null || selectedDevice == null || openRetryCount >= 3) {
            return;
        }
        long delayMillis = 500L << openRetryCount;
        openRetryCount++;
        mainHandler.postDelayed(() -> {
            if (isCurrent(token)) {
                tryOpen();
            }
        }, delayMillis);
    }

    private void reportError(int token, String message, Throwable failure) {
        Log.e(TAG, message, failure);
        mainHandler.post(() -> {
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

    /** Allows the foreground UI to retry after the bounded automatic retries are exhausted. */
    public void retryOpen() {
        if (!running || closed || opening) {
            return;
        }
        if (previewActive) {
            long reference = lastFrameNanos > 0L ? lastFrameNanos : previewStartedNanos;
            if (System.nanoTime() - reference < FRAME_WATCHDOG_TIMEOUT_NANOS) {
                return;
            }
        }
        openRetryCount = 0;
        invalidatePreview();
        cameraHandler.post(this::closeCamera);
        listener.onError("正在重新连接 USB 摄像头");
        if (selectedDevice == null) {
            selectCamera();
        } else if (usbManager.hasPermission(selectedDevice)) {
            tryOpen();
        } else {
            permissionRequested = false;
            requestPermission(selectedDevice);
        }
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(frameWatchdog);
        invalidatePreview();
        openRetryCount = 0;
        if (registered) {
            activity.unregisterReceiver(receiver);
            registered = false;
        }
        selectedDevice = null;
        permissionRequested = false;
        cameraHandler.post(this::closeCamera);
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.destroy();
            } catch (RuntimeException | LinkageError failure) {
                Log.w(TAG, "Camera release failed", failure);
            } finally {
                camera = null;
            }
        }
        if (monitor != null) {
            try {
                monitor.destroy();
            } catch (RuntimeException | LinkageError failure) {
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
        invalidatePreview();
        openRetryCount = 0;
        if (running && !closed) {
            // Also invalidate an inference already removed from the frame queue.
            listener.onDeviceConnectionChanged(selectedDevice, false);
        }
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
