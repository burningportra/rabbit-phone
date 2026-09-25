package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.kevtrinh.rabbitphone.beats.Engine;

import java.util.Arrays;

/**
 * Press-and-hold microphone capture for Beats: 48 kHz mono, at most ten seconds. The
 * recorder is prepared while Beats is open but only records between start and stop.
 */
final class CatchMic {
    interface Listener {
        void onLevel(float peak);
        void onLimit();
    }

    static final int MAX_FRAMES = Engine.RATE * 10;
    private static final String TAG = "RabbitBeats";
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] buffer = new float[MAX_FRAMES];
    private AudioRecord record;
    private Thread reader;
    private volatile boolean capturing;
    private volatile int length;
    private String source = "none";

    CatchMic(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Creates the recorder without recording. Unprocessed audio is preferred for music. */
    boolean prepare() {
        if (record != null) return true;
        if (!hasPermission()) return false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        boolean unprocessed = audio != null && "true".equals(
                audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = unprocessed
                ? new int[] {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[] {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        String[] names = unprocessed
                ? new String[] {"unprocessed", "voice recognition", "mic"}
                : new String[] {"voice recognition", "mic"};
        int minimum = AudioRecord.getMinBufferSize(Engine.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        for (int i = 0; i < sources.length; i++) {
            try {
                AudioRecord candidate = new AudioRecord.Builder()
                        .setAudioSource(sources[i])
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(Engine.RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(minimum, Engine.RATE / 5 * 2) * 2)
                        .build();
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    record = candidate;
                    source = names[i];
                    Log.i(TAG, "Catch microphone source: " + source);
                    return true;
                }
                candidate.release();
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Microphone source unavailable: " + names[i], unavailable);
            }
        }
        return false;
    }

    boolean start() {
        if (reader != null) return true;
        if (!prepare()) return false;
        length = 0;
        try {
            record.startRecording();
        } catch (IllegalStateException busy) {
            return false;
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) { }
            return false;
        }
        capturing = true;
        final AudioRecord active = record;
        reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(active); }
        }, "Beats microphone");
        reader.setPriority(Thread.MAX_PRIORITY);
        reader.start();
        return true;
    }

    /** Stops recording and returns what was caught, or null when nothing was recording. */
    float[] stop() {
        if (reader == null) return null;
        capturing = false;
        try {
            record.stop();
        } catch (IllegalStateException ignored) { }
        boolean interrupted = false;
        while (true) {
            try {
                reader.join();
                break;
            } catch (InterruptedException wake) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        reader = null;
        return Arrays.copyOf(buffer, length);
    }

    void cancel() { stop(); }

    boolean isRecording() { return reader != null; }

    void release() {
        cancel();
        if (record != null) {
            record.release();
            record = null;
        }
    }

    String source() { return source; }

    private void readLoop(AudioRecord active) {
        short[] chunk = new short[Engine.RATE / 100];
        long levelAt = 0;
        float peak = 0;
        while (capturing) {
            int count = active.read(chunk, 0, chunk.length);
            if (count < 0) break;
            int kept = Math.min(count, MAX_FRAMES - length);
            for (int i = 0; i < kept; i++) {
                float value = chunk[i] / 32768f;
                buffer[length + i] = value;
                peak = Math.max(peak, Math.abs(value));
            }
            length += kept;
            long now = SystemClock.uptimeMillis();
            if (now - levelAt >= 50) {
                final float level = peak;
                peak = 0;
                levelAt = now;
                main.post(new Runnable() {
                    @Override public void run() { listener.onLevel(level); }
                });
            }
            if (length >= MAX_FRAMES) {
                capturing = false;
                main.post(new Runnable() {
                    @Override public void run() { listener.onLimit(); }
                });
            }
        }
    }
}
