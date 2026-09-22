package com.kevtrinh.rabbitphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
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
    public interface Listener {
        void onRecordingChanged(boolean recording);
        void onMessage(String message);
    }

    private static final long MIN_VALID_BYTES = 512L;
    private static final int MAX_DURATION_MS = 60_000;

    private final Activity activity;
    private final Listener listener;
    private final File notesDirectory;
    private final AudioManager audioManager;
    private final AudioAttributes playbackAttributes;
    private final AudioFocusRequest focusRequest;

    private MediaRecorder recorder;
    private File currentFile;
    private boolean recording;
    private MediaPlayer player;
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

    public boolean start() {
        if (!requireMainThread("start recording")) return false;
        if (recording || recorder != null) {
            message("A voice note is already recording");
            return false;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            message("Microphone permission is required to record a voice note");
            return false;
        }
        if (!notesDirectory.exists() && !notesDirectory.mkdirs()) {
            message("Voice-note storage isn't available");
            return false;
        }

        stopPlayback();
        currentFile = nextFile();
        recorder = new MediaRecorder();
        final MediaRecorder activeRecorder = recorder;
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setMaxDuration(MAX_DURATION_MS);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override public void onInfo(MediaRecorder source, int what, int extra) {
                    if (source == activeRecorder
                            && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopInternal("Voice note saved at the 60-second limit");
                    }
                }
            });
            recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                @Override public void onError(MediaRecorder source, int what, int extra) {
                    if (source == activeRecorder) failRecording("Voice-note recording failed");
                }
            });
            recorder.prepare();
            recorder.start();
            recording = true;
            listener.onRecordingChanged(true);
            return true;
        } catch (IOException exception) {
            failRecording("Couldn't prepare the microphone");
        } catch (RuntimeException exception) {
            failRecording("Couldn't start voice-note recording");
        }
        return false;
    }

    public void stop() {
        if (!requireMainThread("stop recording")) return;
        stopInternal("Voice note saved");
    }

    public void cancel() {
        if (!requireMainThread("cancel recording")) return;
        cancelInternal(true);
    }

    public void showLibrary() {
        if (!requireMainThread("open voice notes")) return;
        stopPlayback();
        final List<File> notes = listNotes();
        AlertDialog.Builder builder = new AlertDialog.Builder(
                activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Voice notes")
                .setNegativeButton("Close", null);
        if (notes.isEmpty()) {
            builder.setMessage("Hold the side button to record your first voice note.");
        } else {
            CharSequence[] labels = new CharSequence[notes.size()];
            SimpleDateFormat format = new SimpleDateFormat(
                    "EEE, MMM d  ·  h:mm a", Locale.getDefault());
            for (int index = 0; index < notes.size(); index++) {
                labels[index] = format.format(new Date(notes.get(index).lastModified()));
            }
            builder.setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (which >= 0 && which < notes.size()) play(notes.get(which));
                }
            });
        }
        try {
            builder.show();
        } catch (RuntimeException exception) {
            message("Couldn't open voice notes");
        }
    }

    public void release() {
        if (!requireMainThread("release voice notes")) return;
        cancelInternal(false);
        stopPlayback();
    }

    private void stopInternal(String successMessage) {
        if (!recording || recorder == null) return;
        MediaRecorder activeRecorder = recorder;
        File completedFile = currentFile;
        recording = false;
        recorder = null;
        currentFile = null;
        clearRecorderListeners(activeRecorder);

        boolean stopped = false;
        try {
            activeRecorder.stop();
            stopped = true;
        } catch (RuntimeException ignored) {
            stopped = false;
        } finally {
            try {
                activeRecorder.release();
            } catch (RuntimeException ignored) {
                // The recording is already stopped; cleanup continues below.
            }
        }

        listener.onRecordingChanged(false);
        if (stopped && isValid(completedFile)) {
            message(successMessage);
        } else {
            deleteQuietly(completedFile);
            message("Voice note was too short or couldn't be saved");
        }
    }

    private void failRecording(String failureMessage) {
        MediaRecorder failedRecorder = recorder;
        File failedFile = currentFile;
        boolean wasRecording = recording;
        recorder = null;
        currentFile = null;
        recording = false;
        if (failedRecorder != null) {
            clearRecorderListeners(failedRecorder);
            try {
                failedRecorder.release();
            } catch (RuntimeException ignored) {
                // Cleanup below still removes any incomplete file.
            }
        }
        deleteQuietly(failedFile);
        if (wasRecording) listener.onRecordingChanged(false);
        message(failureMessage);
    }

    private void cancelInternal(boolean announce) {
        MediaRecorder canceledRecorder = recorder;
        File canceledFile = currentFile;
        boolean wasRecording = recording;
        recorder = null;
        currentFile = null;
        recording = false;
        if (canceledRecorder != null) {
            clearRecorderListeners(canceledRecorder);
            if (wasRecording) {
                try {
                    canceledRecorder.stop();
                } catch (RuntimeException ignored) {
                    // A canceled recording does not need to be finalized.
                }
            }
            try {
                canceledRecorder.release();
            } catch (RuntimeException ignored) {
                // The file is removed below regardless.
            }
        }
        deleteQuietly(canceledFile);
        if (wasRecording) listener.onRecordingChanged(false);
        if (announce && (wasRecording || canceledFile != null)) message("Voice note canceled");
    }

    private void clearRecorderListeners(MediaRecorder target) {
        try {
            target.setOnInfoListener(null);
            target.setOnErrorListener(null);
        } catch (RuntimeException ignored) {
            // Listener removal is best effort during recorder teardown.
        }
    }

    private void play(File note) {
        stopPlayback();
        if (!note.isFile()) {
            message("That voice note is no longer available");
            return;
        }
        if (audioManager == null
                || audioManager.requestAudioFocus(focusRequest)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            message("Audio is busy right now");
            return;
        }
        hasAudioFocus = true;
        final MediaPlayer activePlayer = new MediaPlayer();
        player = activePlayer;
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
                        message("Couldn't play that voice note");
                    }
                    return true;
                }
            });
            activePlayer.prepare();
            activePlayer.start();
        } catch (IOException exception) {
            stopPlayback();
            message("Couldn't read that voice note");
        } catch (RuntimeException exception) {
            stopPlayback();
            message("Couldn't play that voice note");
        }
    }

    private void stopPlayback() {
        MediaPlayer oldPlayer = player;
        player = null;
        if (oldPlayer != null) {
            oldPlayer.setOnCompletionListener(null);
            oldPlayer.setOnErrorListener(null);
            try {
                oldPlayer.stop();
            } catch (RuntimeException ignored) {
                // Release is still safe from an error or completed state.
            }
            oldPlayer.release();
        }
        if (hasAudioFocus && audioManager != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            hasAudioFocus = false;
        }
    }

    private List<File> listNotes() {
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

    private File nextFile() {
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date());
        File candidate = new File(notesDirectory, "voice-note-" + base + ".m4a");
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(notesDirectory,
                    "voice-note-" + base + "-" + suffix + ".m4a");
            suffix++;
        }
        return candidate;
    }

    private boolean requireMainThread(String action) {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        message("Can't " + action + " away from the main screen thread");
        return false;
    }

    private boolean isValid(File file) {
        return file != null && file.isFile() && file.length() >= MIN_VALID_BYTES;
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private void message(String value) {
        listener.onMessage(value);
    }
}
