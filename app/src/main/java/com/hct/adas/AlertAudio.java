package com.hct.adas;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Media-stream alert playback with software tone fallback and explicit path health. */
public final class AlertAudio implements AutoCloseable {
    public enum Status { READY, LOADING, UNAVAILABLE, MUTED }

    private static final String TAG = "HctAdasAudio";
    private static final long LOAD_TIMEOUT_MILLIS = 5_000L;
    private final AudioManager audioManager;
    private final SoundPool soundPool;
    private final int fcwSound;
    private final int hmwSound;
    private final int ldwSound;
    private final int lvsaSound;
    private final Set<Integer> loadedSounds = new HashSet<>();
    private final Set<Integer> pendingSounds = new HashSet<>();
    private final ArrayDeque<Integer> playingStreams = new ArrayDeque<>();
    private final long loadStartedMillis = SystemClock.elapsedRealtime();
    private ToneGenerator toneGenerator;
    private boolean playbackFailed;
    private boolean closed;
    private int activePriority = 0;
    private long priorityLockUntilNanos = 0L;

    public AlertAudio(Context context) throws IOException {
        audioManager = context.getSystemService(AudioManager.class);
        SoundPool pool = null;
        try {
            pool = new SoundPool.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setMaxStreams(4)
                    .build();
        } catch (RuntimeException failure) {
            Log.w(TAG, "SoundPool unavailable", failure);
        }
        soundPool = pool;
        synchronized (this) {
            if (soundPool != null) {
                soundPool.setOnLoadCompleteListener((ignored, sampleId, status) -> {
                    synchronized (AlertAudio.this) {
                        if (closed) {
                            return;
                        }
                        pendingSounds.remove(sampleId);
                        if (status == 0) {
                            loadedSounds.add(sampleId);
                            playbackFailed = false;
                        } else {
                            Log.w(TAG, "Sound sample " + sampleId + " load failed: " + status);
                        }
                    }
                });
            }
            fcwSound = load(context, "alerts/fcw.wav");
            hmwSound = load(context, "alerts/hmw.wav");
            ldwSound = load(context, "alerts/ldw.wav");
            lvsaSound = load(context, "alerts/lvsa.wav");
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Software tone fallback unavailable", failure);
            }
        }
    }

    private int load(Context context, String name) {
        if (soundPool == null) {
            return 0;
        }
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(name)) {
            int sound = soundPool.load(descriptor, 1);
            if (sound > 0) {
                pendingSounds.add(sound);
            } else {
                Log.w(TAG, "SoundPool rejected " + name);
            }
            return sound;
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Cannot load " + name, failure);
            return 0;
        }
    }

    /** Reports software readiness; READY is not proof that a physical speaker is audible. */
    public synchronized Status status() {
        if (closed) {
            return Status.UNAVAILABLE;
        }
        if (isMuted()) {
            return Status.MUTED;
        }
        if (playbackFailed) {
            return Status.UNAVAILABLE;
        }
        if (toneGenerator != null || (loadedSounds.contains(fcwSound)
                && loadedSounds.contains(hmwSound) && loadedSounds.contains(ldwSound)
                && loadedSounds.contains(lvsaSound))) {
            return Status.READY;
        }
        return !pendingSounds.isEmpty()
                && SystemClock.elapsedRealtime() - loadStartedMillis < LOAD_TIMEOUT_MILLIS
                ? Status.LOADING : Status.UNAVAILABLE;
    }

    private boolean isMuted() {
        if (audioManager == null) {
            return false;
        }
        try {
            return audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                    || audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot read media volume", failure);
            return false;
        }
    }

    public synchronized boolean testSound() {
        activePriority = 1;
        priorityLockUntilNanos = System.nanoTime() + 250_000_000L;
        return playSound(hmwSound, ToneGenerator.TONE_PROP_ACK, 250);
    }

    public synchronized boolean play(Set<AdasDecisionEngine.Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return true;
        }
        int priority = 1;
        int sound = lvsaSound;
        int fallbackTone = ToneGenerator.TONE_PROP_ACK;
        int durationMillis = 350;

        if (alerts.contains(AdasDecisionEngine.Alert.FCW)) {
            priority = 4;
            sound = fcwSound;
            fallbackTone = ToneGenerator.TONE_PROP_BEEP2;
            durationMillis = 600;
        } else if (alerts.contains(AdasDecisionEngine.Alert.HMW_CRITICAL)) {
            priority = 3;
            sound = hmwSound;
            fallbackTone = ToneGenerator.TONE_PROP_BEEP;
            durationMillis = 250;
        } else if (alerts.contains(AdasDecisionEngine.Alert.LDW)) {
            priority = 2;
            sound = ldwSound;
            fallbackTone = ToneGenerator.TONE_SUP_PIP;
            durationMillis = 400;
        }

        long now = System.nanoTime();
        if (now < priorityLockUntilNanos && priority < activePriority) {
            return false;
        }

        boolean played = playSound(sound, fallbackTone, durationMillis);
        if (played) {
            activePriority = priority;
            priorityLockUntilNanos = now + (long) durationMillis * 1_000_000L;
        }
        return played;
    }

    private boolean playSound(int sound, int fallbackTone, int durationMillis) {
        if (closed || isMuted()) {
            return false;
        }
        if (soundPool != null && loadedSounds.contains(sound)) {
            try {
                int stream = soundPool.play(sound, 1f, 1f, 1, 0, 1f);
                if (stream != 0) {
                    // SoundPool supports at most four concurrent streams; bound our stop handles too.
                    if (playingStreams.size() == 4) {
                        playingStreams.removeFirst();
                    }
                    playingStreams.addLast(stream);
                    playbackFailed = false;
                    return true;
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "SoundPool playback failed", failure);
            }
        }
        if (toneGenerator != null) {
            try {
                if (toneGenerator.startTone(fallbackTone, durationMillis)) {
                    playbackFailed = false;
                    return true;
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Tone playback failed", failure);
            }
        }
        playbackFailed = true;
        return false;
    }

    public synchronized void stop() {
        while (!playingStreams.isEmpty()) {
            int stream = playingStreams.removeFirst();
            try {
                if (soundPool != null) {
                    soundPool.stop(stream);
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Cannot stop alert stream", failure);
            }
        }
        try {
            if (toneGenerator != null) {
                toneGenerator.stopTone();
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot stop alert tone", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stop();
        try {
            if (soundPool != null) {
                try {
                    soundPool.setOnLoadCompleteListener(null);
                } finally {
                    soundPool.release();
                }
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "SoundPool release failed", failure);
        } finally {
            loadedSounds.clear();
            pendingSounds.clear();
            if (toneGenerator != null) {
                try {
                    toneGenerator.release();
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Tone release failed", failure);
                } finally {
                    toneGenerator = null;
                }
            }
        }
    }
}
