package com.hct.adas;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.IOException;
import java.util.Set;

/** Fixed short alert tones. No recognized speech is sent to TTS. */
public final class AlertAudio implements AutoCloseable {
    private static final String TAG = "HctAdasAudio";
    private final SoundPool soundPool;
    private final int fcwSound;
    private final int ldwSound;
    private final int lvsaSound;

    public AlertAudio(Context context) throws IOException {
        soundPool = new SoundPool.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setMaxStreams(1)
                .build();
        try {
            fcwSound = load(context, "alerts/fcw.wav");
            ldwSound = load(context, "alerts/ldw.wav");
            lvsaSound = load(context, "alerts/lvsa.wav");
        } catch (IOException | RuntimeException failure) {
            soundPool.release();
            throw failure;
        }
    }

    private int load(Context context, String name) throws IOException {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(name)) {
            return soundPool.load(descriptor, 1);
        }
    }

    public void play(Set<AdasDecisionEngine.Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        int sound = alerts.contains(AdasDecisionEngine.Alert.FCW) ? fcwSound
                : alerts.contains(AdasDecisionEngine.Alert.LDW) ? ldwSound : lvsaSound;
        try {
            if (soundPool.play(sound, 1f, 1f, 1, 0, 1f) == 0) {
                Log.w(TAG, "Alert sound not ready");
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Alert sound playback failed", failure);
        }
    }

    @Override
    public void close() {
        soundPool.release();
    }
}
