package com.hct.adas;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.serenegiant.usb.widget.UVCCameraTextureView;

/** Application entry point for the USB frame-input phase. */
public final class MainActivity extends Activity {
    private FrameDispatcher frameDispatcher;
    private UsbCameraSource cameraSource;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        frameDispatcher = new FrameDispatcher(2);
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
        cameraSource.start();
    }

    @Override
    protected void onStop() {
        cameraSource.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cameraSource.close();
        frameDispatcher.close();
        super.onDestroy();
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }
}
