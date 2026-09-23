package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Records and plays private, app-local voice notes. All public methods expect the main thread. */
public final class VoiceNotes {
    public static final int MAX_DURATION_MS = 60_000;

    public enum Status { SAVED, MAX_DURATION, CANCELED, TOO_SHORT, ERROR }

    public static final class Result {
        public final Status status;
        public final String message;
        public final File file;
        public final long durationMillis;

        Result(Status status, String message, File file, long durationMillis) {
            this.status = status;
            this.message = message;
            this.file = file;
            this.durationMillis = durationMillis;
        }

        public boolean isSaved() {
            return (status == Status.SAVED || status == Status.MAX_DURATION)
                    && file != null && file.isFile();
        }
    }

    public interface Listener {
        void onRecordingChanged(boolean recording);
        void onResult(Result result);
        void onPlaybackChanged(File note, boolean playing);
    }

    private static final long MIN_VALID_BYTES = 512L;

    private final Activity activity;
    private final Listener listener;
    private final File notesDirectory;
    private final AudioManager audioManager;
    private final AudioAttributes playbackAttributes;
    private final AudioFocusRequest focusRequest;

    private MediaRecorder recorder;
    private File currentPartialFile;
    private File currentFinalFile;
    private boolean recording;
    private long recordingStartedAt;
    private MediaPlayer player;
    private File playingFile;
    private boolean hasAudioFocus;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        stopPlayback();
                    }
                }
            };

    public VoiceNotes(Activity activity, Listener listener) {
        if (activity == null) throw new IllegalArgumentException("Activity is required");
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        this.activity = activity;
        this.listener = listener;
        notesDirectory = new File(activity.getFilesDir(), "voice-notes");
        audioManager = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
        playbackAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(focusChangeListener,
                        new Handler(Looper.getMainLooper()))
                .build();
    }

    public boolean hasMicrophonePermission() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean start() {
        if (!requireMainThread() || recording || recorder != null) return false;
        if (!hasMicrophonePermission()) {
            result(Status.ERROR, "Microphone permission is required", null, 0L);
            return false;
        }
        if (!notesDirectory.exists() && !notesDirectory.mkdirs()) {
            result(Status.ERROR, "Voice-note storage isn't available", null, 0L);
            return false;
        }

        stopPlayback();
        currentFinalFile = nextFinalFile();
        currentPartialFile = new File(currentFinalFile.getParentFile(),
                currentFinalFile.getName() + ".part");
        recorder = new MediaRecorder();
        final MediaRecorder activeRecorder = recorder;
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setMaxDuration(MAX_DURATION_MS);
            recorder.setOutputFile(currentPartialFile.getAbsolutePath());
            recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override public void onInfo(MediaRecorder source, int what, int extra) {
                    if (source == activeRecorder
                            && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopInternal(Status.MAX_DURATION, "Saved at the 60-second limit");
                    }
                }
            });
            recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override public void onError(MediaRecorder source, int what, int extra) {
                    if (source == activeRecorder) failRecording("Recording failed");
                }
            });
            recorder.prepare();
            recorder.start();
            recordingStartedAt = SystemClock.elapsedRealtime();
            recording = true;
            listener.onRecordingChanged(true);
            return true;
        } catch (IOException exception) {
            failRecording("Couldn't prepare the microphone");
        } catch (RuntimeException exception) {
            failRecording("Couldn't start recording");
        }
        return false;
    }

    public void stop() {
        if (requireMainThread()) stopInternal(Status.SAVED, "Voice note saved");
    }

    public void cancel() {
        if (requireMainThread()) cancelInternal(true);
    }

    public boolean isRecording() { return recording; }

    public long elapsedMillis() {
        return recording ? Math.min(MAX_DURATION_MS,
                Math.max(0L, SystemClock.elapsedRealtime() - recordingStartedAt)) : 0L;
    }

    public int maxAmplitude() {
        if (!recording || recorder == null) return 0;
        try {
            return Math.max(0, recorder.getMaxAmplitude());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public List<File> listNotes() {
        File[] files = notesDirectory.listFiles();
        if (files == null || files.length == 0) return new ArrayList<File>();
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        ArrayList<File> notes = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".m4a")) notes.add(file);
        }
        return notes;
    }

    /** Read duration metadata without creating a player, taking audio focus, or starting audio.
     * -1 means unavailable. Only an existing private .m4a note is accepted. */
    public long durationMillis(File note) {
        if (!requireMainThread() || !isValid(note) || !note.getName().endsWith(".m4a")) return -1;
        try {
            File canonical = note.getCanonicalFile();
            if (!notesDirectory.getCanonicalFile().equals(canonical.getParentFile())) return -1;
            try (FileInputStream input = new FileInputStream(canonical)) {
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    metadata.setDataSource(input.getFD());
                    if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))) return -1;
                    String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long millis = duration == null ? -1 : Long.parseLong(duration);
                    return millis > 0 ? millis : -1;
                } finally {
                    try { metadata.release(); } catch (Exception ignored) { }
                }
            }
        } catch (IOException | RuntimeException unavailable) { return -1; }
    }

    public boolean play(File note) {
        if (!requireMainThread()) return false;
        stopPlayback();
        if (!isValid(note)) {
            result(Status.ERROR, "That voice note is no longer available", null, 0L);
            return false;
        }
        if (audioManager == null
                || audioManager.requestAudioFocus(focusRequest)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            result(Status.ERROR, "Audio is busy right now", null, 0L);
            return false;
        }
        hasAudioFocus = true;
        final MediaPlayer activePlayer = new MediaPlayer();
        player = activePlayer;
        playingFile = note;
        try {
            activePlayer.setAudioAttributes(playbackAttributes);
            activePlayer.setDataSource(note.getAbsolutePath());
            activePlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer completed) {
                    if (completed == activePlayer) stopPlayback();
                }
            });
            activePlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer failed, int what, int extra) {
                    if (failed == activePlayer) {
                        stopPlayback();
                        result(Status.ERROR, "Couldn't play that voice note", null, 0L);
                    }
                    return true;
                }
            });
            activePlayer.prepare();
            activePlayer.start();
            listener.onPlaybackChanged(note, true);
            return true;
        } catch (IOException exception) {
            stopPlayback();
            result(Status.ERROR, "Couldn't read that voice note", null, 0L);
        } catch (RuntimeException exception) {
            stopPlayback();
            result(Status.ERROR, "Couldn't play that voice note", null, 0L);
        }
        return false;
    }

    public void stopPlayback() {
        File stoppedFile = playingFile;
        MediaPlayer oldPlayer = player;
        player = null;
        playingFile = null;
        if (oldPlayer != null) {
            oldPlayer.setOnCompletionListener(null);
            oldPlayer.setOnErrorListener(null);
            try { oldPlayer.stop(); } catch (RuntimeException ignored) { }
            try { oldPlayer.release(); } catch (RuntimeException ignored) { }
        }
        if (hasAudioFocus && audioManager != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            hasAudioFocus = false;
        }
        if (stoppedFile != null) listener.onPlaybackChanged(stoppedFile, false);
    }

    public boolean isPlaying(File note) {
        return player != null && playingFile != null && playingFile.equals(note);
    }

    public long playbackPositionMillis() {
        if (player == null) return 0L;
        try { return Math.max(0, player.getCurrentPosition()); }
        catch (RuntimeException unavailable) { return 0L; }
    }

    public long playbackDurationMillis() {
        if (player == null) return 0L;
        try { return Math.max(0, player.getDuration()); }
        catch (RuntimeException unavailable) { return 0L; }
    }

    public void release() {
        if (!requireMainThread()) return;
        cancelInternal(false);
        stopPlayback();
    }

    private void stopInternal(Status successStatus, String successMessage) {
        if (!recording || recorder == null) return;
        MediaRecorder activeRecorder = recorder;
        File partialFile = currentPartialFile;
        File finalFile = currentFinalFile;
        long duration = elapsedMillis();
        recording = false;
        recorder = null;
        currentPartialFile = null;
        currentFinalFile = null;
        clearRecorderListeners(activeRecorder);

        boolean stopped;
        try {
            activeRecorder.stop();
            stopped = true;
        } catch (RuntimeException ignored) {
            stopped = false;
        } finally {
            try { activeRecorder.release(); } catch (RuntimeException ignored) { }
        }

        listener.onRecordingChanged(false);
        if (stopped && isValid(partialFile) && partialFile.renameTo(finalFile)
                && isValid(finalFile)) {
            result(successStatus, successMessage, finalFile, duration);
        } else {
            deleteQuietly(partialFile);
            deleteQuietly(finalFile);
            result(Status.TOO_SHORT, "That recording was too short to save", null, duration);
        }
    }

    private void failRecording(String failureMessage) {
        MediaRecorder failedRecorder = recorder;
        File partialFile = currentPartialFile;
        File finalFile = currentFinalFile;
        boolean wasRecording = recording;
        recorder = null;
        currentPartialFile = null;
        currentFinalFile = null;
        recording = false;
        if (failedRecorder != null) {
            clearRecorderListeners(failedRecorder);
            try { failedRecorder.release(); } catch (RuntimeException ignored) { }
        }
        deleteQuietly(partialFile);
        deleteQuietly(finalFile);
        if (wasRecording) listener.onRecordingChanged(false);
        result(Status.ERROR, failureMessage, null, 0L);
    }

    private void cancelInternal(boolean announce) {
        MediaRecorder canceledRecorder = recorder;
        File partialFile = currentPartialFile;
        File finalFile = currentFinalFile;
        boolean wasRecording = recording;
        recorder = null;
        currentPartialFile = null;
        currentFinalFile = null;
        recording = false;
        if (canceledRecorder != null) {
            clearRecorderListeners(canceledRecorder);
            if (wasRecording) {
                try { canceledRecorder.stop(); } catch (RuntimeException ignored) { }
            }
            try { canceledRecorder.release(); } catch (RuntimeException ignored) { }
        }
        deleteQuietly(partialFile);
        deleteQuietly(finalFile);
        if (wasRecording) listener.onRecordingChanged(false);
        if (announce && (wasRecording || partialFile != null)) {
            result(Status.CANCELED, "Recording canceled", null, 0L);
        }
    }

    private void clearRecorderListeners(MediaRecorder target) {
        try {
            target.setOnInfoListener(null);
            target.setOnErrorListener(null);
        } catch (RuntimeException ignored) { }
    }

    private File nextFinalFile() {
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date());
        File candidate = new File(notesDirectory, "voice-note-" + base + ".m4a");
        int suffix = 1;
        while (candidate.exists() || new File(candidate.getPath() + ".part").exists()) {
            candidate = new File(notesDirectory,
                    "voice-note-" + base + "-" + suffix + ".m4a");
            suffix++;
        }
        return candidate;
    }

    private boolean requireMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private boolean isValid(File file) {
        return file != null && file.isFile() && file.length() >= MIN_VALID_BYTES;
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private void result(Status status, String message, File file, long durationMillis) {
        listener.onResult(new Result(status, message, file, durationMillis));
    }
}
