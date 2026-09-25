package com.kevtrinh.rabbitphone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.kevtrinh.rabbitphone.beats.Engine;

/**
 * Streams the Beats engine to a low-latency float AudioTrack from an urgent-audio thread.
 * The buffer starts small and grows by one burst whenever Android reports an underrun.
 */
final class BeatSpeaker {
    interface Listener { void onOutputFailed(); }

    private static final String TAG = "RabbitBeats";
    private static final int START_BURSTS = 4, MAX_BURSTS = 16;
    private final Context context;
    private final Engine engine;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;

    BeatSpeaker(Context context, Engine engine, Listener listener) {
        this.context = context;
        this.engine = engine;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "Beats output");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Thread old = thread;
        thread = null;
        if (old == null) return;
        boolean interrupted = false;
        while (true) {
            try {
                old.join();
                break;
            } catch (InterruptedException wake) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int burst = framesPerBurst();
        AudioTrack track = build(burst);
        if (track == null) {
            fail();
            return;
        }
        track.addOnRoutingChangedListener(new AudioRouting.OnRoutingChangedListener() {
            @Override public void onRoutingChanged(AudioRouting router) { updateRoute(router); }
        }, main);
        float[] mono = new float[burst];
        float[] stereo = new float[burst * 2];
        int bursts = START_BURSTS;
        track.setBufferSizeInFrames(burst * bursts);
        int underruns = track.getUnderrunCount();
        boolean playing = false;
        long checkedAt = SystemClock.uptimeMillis(), loggedAt = 0, renderNanos = 0, blocks = 0;
        try {
            while (running) {
                if (!engine.isActive()) {
                    if (playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    SystemClock.sleep(10);
                    continue;
                }
                if (!playing) {
                    track.play();
                    playing = true;
                    updateRoute(track);
                }
                long started = System.nanoTime();
                engine.render(mono, burst);
                renderNanos += System.nanoTime() - started;
                blocks++;
                for (int i = 0; i < burst; i++) {
                    stereo[2 * i] = mono[i];
                    stereo[2 * i + 1] = mono[i];
                }
                int written = track.write(stereo, 0, stereo.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("AudioTrack write failed: " + written);
                long now = SystemClock.uptimeMillis();
                if (now - checkedAt < 1000) continue;
                int count = track.getUnderrunCount();
                if (count > underruns && bursts < MAX_BURSTS) track.setBufferSizeInFrames(burst * ++bursts);
                if (count != underruns || now - loggedAt >= 10_000) {
                    Log.i(TAG, "Render " + (renderNanos / Math.max(1, blocks) / 1000) + " us per "
                            + burst + "-frame burst (budget " + (burst * 1_000_000L / Engine.RATE)
                            + " us), underruns " + count + ", buffer " + bursts + " bursts");
                    loggedAt = now;
                }
                underruns = count;
                checkedAt = now;
                renderNanos = blocks = 0;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Beats output stopped", error);
            if (running) fail();
        } finally {
            try {
                track.stop();
            } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    private void fail() {
        main.post(new Runnable() {
            @Override public void run() { listener.onOutputFailed(); }
        });
    }

    private void updateRoute(AudioRouting router) {
        AudioDeviceInfo device = router.getRoutedDevice();
        engine.setSpeakerMode(device == null || device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
    }

    private int framesPerBurst() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        String value = audio == null ? null : audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        try {
            return value == null ? 256 : Math.max(64, Math.min(1024, Integer.parseInt(value)));
        } catch (NumberFormatException invalid) {
            return 256;
        }
    }

    private AudioTrack build(int burst) {
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(Engine.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(burst * MAX_BURSTS * 2 * 4)
                    .build();
            if (track.getState() == AudioTrack.STATE_INITIALIZED) return track;
            track.release();
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Beats output unavailable", unavailable);
        }
        return null;
    }
}
