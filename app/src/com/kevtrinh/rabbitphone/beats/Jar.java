package com.kevtrinh.rabbitphone.beats;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Every caught sound, kept until it's tossed: {@code raw/<id>.wav} is the untouched capture,
 * {@code sounds/<id>.wav} the cleaned sound and {@code sounds/<id>.properties} what we know about it.
 */
public final class Jar {
    private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}-\\d{3}");
    private final File raw;
    private final File sounds;

    public Jar(File root) {
        raw = new File(root, "raw");
        sounds = new File(root, "sounds");
    }

    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** A time-based ID that no stored sound uses yet. */
    public String nextId(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        String id = format.format(new Date(millis));
        while (new File(raw, id + ".wav").exists() || new File(sounds, id + ".wav").exists())
            id = format.format(new Date(++millis));
        return id;
    }

    public void saveRaw(String id, float[] samples) throws IOException {
        replace(file(raw, id, ".wav"), Wav.encode(samples, 1, Engine.RATE));
    }

    public void saveSound(String id, float[] samples, Properties about) throws IOException {
        replace(file(sounds, id, ".wav"), Wav.encode(samples, 1, Engine.RATE));
        saveAbout(id, about);
    }

    public void saveAbout(String id, Properties about) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        about.store(bytes, "Beats sound");
        replace(file(sounds, id, ".properties"), bytes.toByteArray());
    }

    public float[] loadSound(String id) throws IOException {
        return Wav.readMono(file(sounds, id, ".wav"));
    }

    /** Loads a sound with its analysis, analysing and storing it again when it's missing or stale. */
    public Arranger.Sound load(String id) throws IOException {
        float[] samples = loadSound(id);
        Properties about = loadAbout(id);
        Analysis analysis = Analysis.read(about, samples.length);
        if (analysis == null) {
            analysis = Analysis.of(samples);
            analysis.write(about);
            try {
                saveAbout(id, about);
            } catch (IOException unsaved) {
                // Still usable this session; it's analysed again next time.
            }
        }
        return new Arranger.Sound(id, samples, analysis);
    }

    /** What's stored about a sound; empty when nothing is. */
    public Properties loadAbout(String id) throws IOException {
        Properties about = new Properties();
        File file = file(sounds, id, ".properties");
        if (!file.isFile()) return about;
        try (InputStream in = new FileInputStream(file)) {
            about.load(in);
        }
        return about;
    }

    /** Stored sound IDs, newest first. */
    public List<String> soundIds() {
        List<String> ids = new ArrayList<>();
        String[] names = sounds.list();
        if (names == null) return ids;
        for (String name : names) {
            if (!name.endsWith(".wav")) continue;
            String id = name.substring(0, name.length() - 4);
            if (isValidId(id)) ids.add(id);
        }
        Collections.sort(ids, Collections.reverseOrder());
        return ids;
    }

    private static File file(File directory, String id, String extension) {
        if (!isValidId(id)) throw new IllegalArgumentException("Invalid sound ID: " + id);
        return new File(directory, id + extension);
    }

    /** Writes a whole file or leaves the old one: a temporary copy is synced, then renamed. */
    static void replace(File target, byte[] bytes) throws IOException {
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Can't create " + directory);
        File temporary = new File(directory, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Can't replace " + target);
        }
    }
}
