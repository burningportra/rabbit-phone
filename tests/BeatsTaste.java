package com.kevtrinh.rabbitphone.beats;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the Beats pipeline over real catches on the Mac: cleaning (for raw captures),
 * analysis, and optionally a rendered preview beat to listen to. Nothing is written back.
 */
public final class BeatsTaste {
    public static void main(String[] args) throws Exception {
        List<File> files = new ArrayList<>();
        boolean raw = false;
        File render = null;
        int beat = Arranger.FIRST_BEAT, bars = 8;
        for (int i = 0; i < args.length; i++) {
            if ("--raw".equals(args[i])) raw = true;
            else if ("--render".equals(args[i])) render = new File(args[++i]);
            else if ("--beat".equals(args[i])) beat = Integer.parseInt(args[++i]);
            else if ("--bars".equals(args[i])) bars = Integer.parseInt(args[++i]);
            else {
                File file = new File(args[i]);
                File[] listed = file.isDirectory() ? file.listFiles() : new File[] {file};
                if (listed != null) for (File each : listed) if (each.getName().endsWith(".wav")) files.add(each);
            }
        }
        files.sort(null);
        Map<Job, Arranger.Sound> newest = new EnumMap<>(Job.class);
        System.out.println("file                          secs  job    pieces  centroid  low  body high  cross/s  voiced  f0     spread");
        for (File file : files) {
            float[] samples = Wav.readMono(file);
            if (raw) {
                Cleaner.Result cleaned = Cleaner.clean(samples);
                if (cleaned.samples == null) {
                    System.out.println(String.format(Locale.US, "%-28s rejected: %s", file.getName(), cleaned.problem));
                    continue;
                }
                samples = cleaned.samples;
            }
            long started = System.nanoTime();
            Analysis analysis = Analysis.of(samples);
            long millis = (System.nanoTime() - started) / 1_000_000;
            Features f = analysis.features;
            System.out.println(String.format(Locale.US,
                    "%-28s %5.2f %-6s %6d  %8.0f %4.2f %4.2f %4.2f %8.0f  %6.2f %6.0f %6.2f  (%d ms)",
                    file.getName(), f.seconds, analysis.job.label(), analysis.pieces.length, f.centroidHz, f.lowShare,
                    f.bodyShare, f.highShare, f.zeroCrossingsPerSecond, f.voicedFraction, f.medianHz,
                    f.pitchSpreadSemitones, millis));
            newest.put(analysis.job, new Arranger.Sound(file.getName(), samples, analysis));
        }
        if (render == null) return;
        Beat preview = Arranger.build(beat, null, newest);
        Engine engine = new Engine();
        engine.setVolume(1f);
        engine.setSpeakerMode(false);
        engine.setBeat(preview);
        engine.play();
        int frames = (int) Math.round(bars * Beat.STEPS_PER_BAR * preview.stepFrames());
        float[] mono = new float[frames];
        float[] block = new float[256];
        for (int at = 0; at < frames; at += block.length) {
            int count = Math.min(block.length, frames - at);
            engine.render(block, count);
            System.arraycopy(block, 0, mono, at, count);
        }
        try (OutputStream out = new FileOutputStream(render)) {
            Wav.write(out, mono, 1, Engine.RATE);
        }
        StringBuilder rows = new StringBuilder();
        for (int t = 0; t < preview.trackCount(); t++) {
            Beat.Track track = preview.track(t);
            StringBuilder steps = new StringBuilder();
            for (int s = 0; s < Beat.STEPS_PER_BAR; s++) steps.append(track.hits(s) ? 'x' : '.');
            rows.append(String.format(Locale.US, "  %-6s %s  %s%n", track.job.label(), steps, track.label));
        }
        System.out.print(String.format(Locale.US, "Rendered beat %d at %d BPM, %d bars, to %s%n%s", beat, preview.bpm,
                bars, render, rows));
    }
}
