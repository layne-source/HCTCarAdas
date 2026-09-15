package com.hct.adas;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.Locale;

import com.serenegiant.usb.widget.UVCCameraTextureView;

/** Application entry point for the USB frame-input phase. */
public final class MainActivity extends Activity {
    private FrameDispatcher frameDispatcher;
    private FrameConsumer frameConsumer;
    private UsbCameraSource cameraSource;
    private TextView statusView;
    private final Handler metricsHandler = new Handler(Looper.getMainLooper());
    private final Runnable metricsUpdater = new Runnable() {
        @Override
        public void run() {
            if (frameDispatcher != null && frameConsumer != null) {
                FrameDispatcher.Metrics queueMetrics = frameDispatcher.metrics();
                FrameConsumer.Metrics consumerMetrics = frameConsumer.metrics();
                setStatus(String.format(
                        Locale.US,
                        "USB 帧流 received=%d processed=%d dropped=%d",
                        queueMetrics.offeredFrames(),
                        consumerMetrics.processedFrames(),
                        queueMetrics.droppedFrames()));
            }
            metricsHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        frameDispatcher = new FrameDispatcher(2);
        frameConsumer = new FrameConsumer(frameDispatcher, frame -> {
            // The detector will consume frames here in the next implementation step.
        });
        cameraSource = new UsbCameraSource(
                this,
                (UVCCameraTextureView) findViewById(R.id.usb_preview),
                frameDispatcher,
                new UsbCameraSource.Listener() {
                    @Override
                    public void onDeviceAttached(android.hardware.usb.UsbDevice device) {
                        setStatus("USB 摄像头已连接，等待预览");
                    }

                    @Override
                    public void onDeviceConnectionChanged(
                            android.hardware.usb.UsbDevice device, boolean connected) {
                        setStatus(connected ? "USB 摄像头已打开" : "USB 摄像头连接失败");
                    }

                    @Override
                    public void onDeviceDetached(android.hardware.usb.UsbDevice device) {
                        setStatus("USB 摄像头已断开");
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        frameConsumer.start();
        cameraSource.start();
        metricsHandler.post(metricsUpdater);
    }

    @Override
    protected void onStop() {
        metricsHandler.removeCallbacks(metricsUpdater);
        frameConsumer.close();
        cameraSource.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        metricsHandler.removeCallbacks(metricsUpdater);
        frameConsumer.close();
        cameraSource.close();
        frameDispatcher.close();
        super.onDestroy();
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }
}
