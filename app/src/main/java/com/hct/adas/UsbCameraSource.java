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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private static final long OPEN_WATCHDOG_TIMEOUT_NANOS = 8_000_000_000L;
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
    private boolean cameraSelectionNeedsConfirmation;
    private volatile boolean opening;
    private volatile int openRetryCount;
    private volatile boolean previewActive;
    private volatile long previewStartedNanos;
    private volatile long lastFrameNanos;
    private volatile PreviewSnapshot latestPreviewSnapshot;
    private volatile long openingStartedNanos;
    private boolean streamConfirmed;
    private UsbDevice selectedDevice;
    // Connection-local identity only; never persisted or used to bind calibration.
    private String pausedDeviceName;
    private SurfaceTexture surface;
    // The camera thread exclusively owns these native resources.
    private UVCCamera camera;
    private USBMonitor monitor;
    private USBMonitor.UsbControlBlock controlBlock;

    /** Validated capture metadata, independent of model initialization and inference. */
    public record PreviewSnapshot(int frameWidth, int frameHeight, long timestampNanos,
                                  int sessionId) { }

    public PreviewSnapshot previewSnapshot() {
        PreviewSnapshot snapshot = latestPreviewSnapshot;
        return snapshot != null && isCurrent(snapshot.sessionId()) ? snapshot : null;
    }

    /** Preview modes are tried from the preferred analysis size down to broadly supported UVC modes. */
    static final class PreviewConfig {
        final int width;
        final int height;
        final int format;

        PreviewConfig(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    static PreviewConfig[] previewCandidates() {
        return new PreviewConfig[] {
                new PreviewConfig(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG),
                new PreviewConfig(640, 480, UVCCamera.FRAME_FORMAT_MJPEG),
                new PreviewConfig(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG),
                new PreviewConfig(1280, 720, UVCCamera.FRAME_FORMAT_YUYV),
                new PreviewConfig(640, 480, UVCCamera.FRAME_FORMAT_YUYV),
                new PreviewConfig(320, 240, UVCCamera.FRAME_FORMAT_YUYV),
        };
    }

    static boolean surfaceTextureDestroyedReturnValue() {
        // The camera thread releases the texture after detaching the native preview. Returning
        // false transfers ownership to this listener; returning true would make TextureView
        // release it immediately as well.
        return false;
    }

    private final Runnable frameWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!running || closed) {
                return;
            }
            long now = System.nanoTime();
            if (opening && !previewActive && openingStartedNanos > 0L
                    && now - openingStartedNanos >= OPEN_WATCHDOG_TIMEOUT_NANOS) {
                int token = invalidatePreview();
                cameraHandler.post(UsbCameraSource.this::closeCamera);
                listener.onError("USB 摄像头打开超时，正在自动重连");
                scheduleOpenRetry(token);
            } else if (previewActive) {
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
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (isSelected(device)) {
                    invalidatePreview();
                    selectedDevice = null;
                    permissionRequested = false;
                    openRetryCount = 0;
                    cameraHandler.post(UsbCameraSource.this::closeCamera);
                    listener.onDeviceDetached(device);
                    cameraSelectionNeedsConfirmation = usbManager.getDeviceList().values().stream()
                            .anyMatch(UsbCameraSource::isVideoDevice);
                    if (cameraSelectionNeedsConfirmation) {
                        listener.onError("前视摄像头已断开，请确认剩余摄像头后点击重试");
                    }
                } else if (selectedDevice == null) {
                    // Startup ambiguity can resolve when one of two cameras is unplugged.
                    boolean hasRemainingVideoDevice = usbManager.getDeviceList().values().stream()
                            .anyMatch(UsbCameraSource::isVideoDevice);
                    if (!hasRemainingVideoDevice) {
                        cameraSelectionNeedsConfirmation = false;
                    }
                    selectCamera();
                }
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
        // NOT_EXPORTED is the safe choice, but it makes delivery of the USB permission result a
        // platform behaviour rather than something this code controls. On the current test hardware
        // the app is whitelisted with USB access already granted, so no permission dialog appears
        // and this path is not exercised at all. Re-verify the whole receiver - the permission
        // result plus the attach/detach broadcasts - on any device that is not pre-authorised, or as
        // soon as the app is distributed normally: a missed permission result would leave the
        // camera waiting for an authorisation the user has already given.
        activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        registered = true;
        List<String> currentCameras = usbManager.getDeviceList().values().stream()
                .filter(UsbCameraSource::isVideoDevice)
                .map(UsbDevice::getDeviceName).collect(Collectors.toList());
        cameraSelectionNeedsConfirmation = needsConfirmationOnResume(
                cameraSelectionNeedsConfirmation, pausedDeviceName, currentCameras);
        pausedDeviceName = null;
        selectCamera();
        if (cameraSelectionNeedsConfirmation) {
            listener.onError("前视摄像头已断开，请确认剩余摄像头后点击重试");
        }
    }

    private void selectCamera() {
        if (!running || selectedDevice != null || cameraSelectionNeedsConfirmation) {
            return;
        }
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        List<String> candidates = devices.values().stream()
                .filter(UsbCameraSource::isVideoDevice)
                .map(UsbDevice::getDeviceName).collect(Collectors.toList());
        String name = singleCameraName(candidates);
        if (name == null) {
            if (candidates.size() > 1) {
                listener.onError("检测到多个 USB 摄像头，请仅保留前视摄像头");
            }
            return;
        }
        selectedDevice = devices.get(name);
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

    /** Enumeration order cannot identify which of several video devices faces the road. */
    static String singleCameraName(List<String> candidates) {
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** Detach broadcasts are not received while stopped, so check the previous connection too. */
    static boolean needsConfirmationOnResume(boolean pending, String previousDeviceName,
                                              List<String> candidates) {
        return !candidates.isEmpty() && (pending
                || (previousDeviceName != null && !candidates.contains(previousDeviceName)));
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
        openingStartedNanos = System.nanoTime();
        UsbDevice device = selectedDevice;
        SurfaceTexture target = surface;
        cameraHandler.post(() -> openCamera(token, device, target));
    }

    private boolean isCurrent(int token) {
        return running && generation.get() == token;
    }

    private static PreviewConfig configurePreview(UVCCamera camera) {
        RuntimeException lastUnsupported = null;
        for (PreviewConfig candidate : previewCandidates()) {
            try {
                camera.setPreviewSize(candidate.width, candidate.height, candidate.format);
                return candidate;
            } catch (RuntimeException unsupported) {
                lastUnsupported = unsupported;
            }
        }
        if (lastUnsupported != null) {
            throw lastUnsupported;
        }
        throw new IllegalArgumentException("USB 摄像头没有可用的视频格式");
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
            controlBlock = monitor.openDevice(device);
            camera.open(controlBlock);
            PreviewConfig preview = configurePreview(camera);
            int width = preview.width;
            int height = preview.height;
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
                    latestPreviewSnapshot = new PreviewSnapshot(frameWidth, frameHeight, now, token);
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
                    int retryToken = invalidatePreview();
                    listener.onError("USB 摄像头打开失败: " + failure.getClass().getSimpleName());
                    scheduleOpenRetry(retryToken);
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
        openingStartedNanos = 0L;
        previewActive = false;
        streamConfirmed = false;
        previewStartedNanos = 0L;
        synchronized (dispatcher) {
            lastFrameNanos = 0L;
            latestPreviewSnapshot = null;
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
        cameraSelectionNeedsConfirmation = false;
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
        if (running) {
            pausedDeviceName = selectedDevice == null ? null : selectedDevice.getDeviceName();
        }
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
        // UVCCamera owns a clone. Closing that clone removes the monitor's original block from
        // its map, so monitor.destroy() alone cannot reliably release the original connection.
        if (controlBlock != null) {
            try {
                controlBlock.close();
            } catch (RuntimeException | LinkageError failure) {
                Log.w(TAG, "USB connection release failed", failure);
            } finally {
                controlBlock = null;
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
        // The listener releases the texture after the camera has detached from it. Returning false
        // tells TextureView not to release the same SurfaceTexture a second time.
        return surfaceTextureDestroyedReturnValue();
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
