package com.kevtrinh.rabbitphone.beats;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 16-bit PCM WAV reading and writing. Samples are floats in [-1, 1]. */
public final class Wav {
    private Wav() { }

    public static byte[] encode(float[] samples, int channels, int rate) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(44 + samples.length * 2);
        try {
            write(bytes, samples, channels, rate);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    public static void write(OutputStream out, float[] samples, int channels, int rate) throws IOException {
        if (channels < 1 || samples.length % channels != 0)
            throw new IllegalArgumentException("Samples must hold whole frames");
        int dataBytes = samples.length * 2;
        byte[] header = new byte[44];
        ascii(header, 0, "RIFF");
        int32(header, 4, 36 + dataBytes);
        ascii(header, 8, "WAVE");
        ascii(header, 12, "fmt ");
        int32(header, 16, 16);
        int16(header, 20, 1);
        int16(header, 22, channels);
        int32(header, 24, rate);
        int32(header, 28, rate * channels * 2);
        int16(header, 32, channels * 2);
        int16(header, 34, 16);
        ascii(header, 36, "data");
        int32(header, 40, dataBytes);
        out.write(header);
        byte[] chunk = new byte[8192];
        int used = 0;
        for (float sample : samples) {
            int value = Math.round(Math.max(-1f, Math.min(1f, sample)) * 32767f);
            chunk[used++] = (byte) value;
            chunk[used++] = (byte) (value >> 8);
            if (used == chunk.length) { out.write(chunk); used = 0; }
        }
        if (used > 0) out.write(chunk, 0, used);
    }

    /** Reads a 48 kHz file as mono; files with more channels are averaged. */
    public static float[] readMono(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return readMono(in);
        }
    }

    public static float[] readMono(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (!"RIFF".equals(tag(in))) throw new IOException("Not a WAV file");
        int32(in);
        if (!"WAVE".equals(tag(in))) throw new IOException("Not a WAV file");
        int channels = 0;
        while (true) {
            String id = tag(in);
            long size = int32(in) & 0xffffffffL;
            if ("fmt ".equals(id)) {
                int format = int16(in);
                channels = int16(in);
                int rate = int32(in);
                int32(in);
                int16(in);
                int bits = int16(in);
                if (format != 1 || bits != 16 || channels < 1)
                    throw new IOException("Only 16-bit PCM WAV is supported");
                if (rate != Engine.RATE) throw new IOException("Expected 48 kHz audio, found " + rate);
                skip(in, size - 16 + (size & 1));
            } else if ("data".equals(id)) {
                if (channels == 0) throw new IOException("WAV data came before its format");
                int frames = (int) (size / (2L * channels));
                float[] out = new float[frames];
                byte[] frame = new byte[2 * channels];
                for (int i = 0; i < frames; i++) {
                    in.readFully(frame);
                    float sum = 0;
                    // The same scale as writing, so a round trip keeps the level.
                    for (int c = 0; c < channels; c++)
                        sum += Math.max(-1f, (short) ((frame[2 * c] & 0xff) | (frame[2 * c + 1] << 8)) / 32767f);
                    out[i] = sum / channels;
                }
                return out;
            } else {
                skip(in, size + (size & 1));
            }
        }
    }

    private static void ascii(byte[] target, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, at, 4);
    }

    private static void int32(byte[] target, int at, int value) {
        for (int i = 0; i < 4; i++) target[at + i] = (byte) (value >> (8 * i));
    }

    private static void int16(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >> 8);
    }

    private static String tag(DataInputStream in) throws IOException {
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static int int32(DataInputStream in) throws IOException {
        byte[] b = new byte[4];
        in.readFully(b);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    private static int int16(DataInputStream in) throws IOException {
        byte[] b = new byte[2];
        in.readFully(b);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8;
    }

    private static void skip(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) { in.readByte(); skipped = 1; }
            count -= skipped;
        }
    }
}
