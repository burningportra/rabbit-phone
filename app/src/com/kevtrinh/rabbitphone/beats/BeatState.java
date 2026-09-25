package com.kevtrinh.rabbitphone.beats;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** The current beat's settings, kept in a small properties file next to the jar. */
public final class BeatState {
    public static final int DEFAULT_VOLUME = 80;
    /** 0 to 100. */
    public int volume = DEFAULT_VOLUME;
    public int beat = Arranger.FIRST_BEAT;
    /** The newest catch for each job. */
    public final Map<Job, String> sounds = new EnumMap<>(Job.class);
    /** Tempos set by hand, by beat number. */
    public final Map<Integer, Integer> tempos = new TreeMap<>();

    /** Missing or unreadable files give the defaults instead of an error. */
    public static BeatState load(File file) {
        BeatState state = new BeatState();
        if (!file.isFile()) return state;
        Properties values = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            values.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            return state;
        }
        state.volume = clamp(number(values.getProperty("volume"), DEFAULT_VOLUME), 0, 100);
        state.beat = clamp(number(values.getProperty("beat"), Arranger.FIRST_BEAT), Arranger.FIRST_BEAT, Arranger.LAST_BEAT);
        for (Job job : Job.values()) {
            String id = values.getProperty("sound." + job.name());
            if (Jar.isValidId(id)) state.sounds.put(job, id);
        }
        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith("tempo.")) continue;
            int beat = number(key.substring(6), -1), bpm = number(values.getProperty(key), -1);
            if (beat >= Arranger.FIRST_BEAT && beat <= Arranger.LAST_BEAT && bpm >= Beat.MIN_BPM && bpm <= Beat.MAX_BPM)
                state.tempos.put(beat, bpm);
        }
        // The first version kept one voice and one tempo.
        String voice = values.getProperty("voice");
        if (Jar.isValidId(voice) && !state.sounds.containsKey(Job.VOICE)) state.sounds.put(Job.VOICE, voice);
        int bpm = number(values.getProperty("bpm"), -1);
        if (bpm >= Beat.MIN_BPM && bpm <= Beat.MAX_BPM && state.tempos.isEmpty()) state.tempos.put(state.beat, bpm);
        return state;
    }

    public BeatState copy() {
        BeatState copy = new BeatState();
        copy.volume = volume;
        copy.beat = beat;
        copy.sounds.putAll(sounds);
        copy.tempos.putAll(tempos);
        return copy;
    }

    public void save(File file) throws IOException {
        Properties values = new Properties();
        values.setProperty("volume", Integer.toString(volume));
        values.setProperty("beat", Integer.toString(beat));
        for (Map.Entry<Job, String> sound : sounds.entrySet())
            values.setProperty("sound." + sound.getKey().name(), sound.getValue());
        for (Map.Entry<Integer, Integer> tempo : tempos.entrySet())
            values.setProperty("tempo." + tempo.getKey(), Integer.toString(tempo.getValue()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "Beats state");
        Jar.replace(file, bytes.toByteArray());
    }

    private static int number(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
