package com.kevtrinh.rabbitphone.beats;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Host checks for the pure Beats units. Runs without Android and never touches a microphone. */
public final class BeatsTest {
    private static final int RATE = Engine.RATE;
    private static int groups;

    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static void near(double expected, double actual, double tolerance, String what) {
        check(Math.abs(expected - actual) <= tolerance, what + ": expected " + expected + ", got " + actual);
    }
    private static void rejected(Runnable action) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "Invalid input accepted");
    }

    private static float[] tone(double seconds, double hz, double amplitude) {
        float[] out = new float[(int) (seconds * RATE)];
        for (int i = 0; i < out.length; i++) out[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
        return out;
    }
    private static float[] noise(double seconds, double amplitude, long seed) {
        java.util.Random random = new java.util.Random(seed);
        float[] out = new float[(int) (seconds * RATE)];
        for (int i = 0; i < out.length; i++) out[i] = (float) (amplitude * (random.nextDouble() * 2 - 1));
        return out;
    }
    private static float[] join(float[]... parts) {
        int total = 0;
        for (float[] part : parts) total += part.length;
        float[] out = new float[total];
        int at = 0;
        for (float[] part : parts) { System.arraycopy(part, 0, out, at, part.length); at += part.length; }
        return out;
    }
    private static float peak(float[] samples) {
        float peak = 0;
        for (float value : samples) peak = Math.max(peak, Math.abs(value));
        return peak;
    }
    private static int firstAbove(float[] samples, float threshold) {
        for (int i = 0; i < samples.length; i++) if (Math.abs(samples[i]) > threshold) return i;
        return -1;
    }

    /** Renders in bursts and returns every frame where silence turns into sound. */
    private static List<Integer> onsets(Engine engine, int frames) {
        float[] out = new float[frames];
        float[] burst = new float[256];
        for (int at = 0; at < frames; at += burst.length) {
            int count = Math.min(burst.length, frames - at);
            engine.render(burst, count);
            System.arraycopy(burst, 0, out, at, count);
        }
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < frames; i++) if (out[i] != 0 && (i == 0 || out[i - 1] == 0)) found.add(i);
        return found;
    }
    private static Beat.Track click(String hits) {
        float[] blip = new float[100];
        Arrays.fill(blip, 0.5f);
        return Beat.Track.hits(Job.TUNE, "test", blip, 0, blip.length, Arranger.pattern(hits), 1f, false,
                Beat.Voicing.NONE);
    }
    private static Engine testEngine(Beat beat) {
        Engine engine = new Engine();
        engine.bypassMaster = true;
        engine.setBeat(beat);
        engine.play();
        return engine;
    }
    private static float[] harmonic(double seconds, double hz) {
        float[] out = new float[(int) (seconds * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = 2 * Math.PI * hz * i / RATE;
            out[i] = (float) (0.3 * Math.sin(t) + 0.15 * Math.sin(2 * t) + 0.08 * Math.sin(3 * t));
        }
        return out;
    }
    /** Three 150 ms syllables, one every 250 ms, with quiet gaps. */
    private static float[] threeBursts() {
        float[] burst = harmonic(0.15, 300);
        for (int i = 0; i < 240; i++) { burst[i] *= i / 240f; burst[burst.length - 1 - i] *= i / 240f; }
        return join(burst, noise(0.1, 1e-4, 11), burst, noise(0.1, 1e-4, 12), burst, noise(0.05, 1e-4, 13));
    }
    private static float[] filtered(float[] input, double[] coefficients) {
        float[] out = new float[input.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < input.length; i++) {
            double y = coefficients[0] * input[i] + coefficients[1] * x1 + coefficients[2] * x2
                    - coefficients[3] * y1 - coefficients[4] * y2;
            x2 = x1; x1 = input[i]; y2 = y1; y1 = y;
            out[i] = (float) y;
        }
        return out;
    }
    private static int audible(float[] samples) {
        int count = 0;
        for (float value : samples) if (value != 0f) count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        wav(); groups++;
        cleanerTrimsAndNormalizes(); groups++;
        cleanerRejects(); groups++;
        button(); groups++;
        engineTiming(); groups++;
        engineBeatSwap(); groups++;
        engineStopAndCeiling(); groups++;
        engineVoicing(); groups++;
        pitch(); groups++;
        sorter(); groups++;
        chopper(); groups++;
        snapper(); groups++;
        analysisRoundTrip(); groups++;
        arranger(); groups++;
        state(); groups++;
        jar(); groups++;
        System.out.println(groups + " beats cases passed: WAV round trip, cleaning, rejection, button, "
                + "step timing and swing, bar-boundary swaps, stop fade and ceiling, voicing, pitch, sorting, "
                + "chopping, snapping, stored analysis, arrangement, state, jar.");
    }

    private static void wav() throws IOException {
        float[] mono = tone(0.1, 440, 0.7);
        float[] back = Wav.readMono(new ByteArrayInputStream(Wav.encode(mono, 1, RATE)));
        check(back.length == mono.length, "Mono length changed");
        for (int i = 0; i < mono.length; i++) near(mono[i], back[i], 1.0 / 32000, "Mono sample " + i);
        float[] stereo = {0.5f, -0.5f, 0.25f, 0.25f};
        float[] averaged = Wav.readMono(new ByteArrayInputStream(Wav.encode(stereo, 2, RATE)));
        check(averaged.length == 2, "Stereo frames");
        near(0, averaged[0], 1e-4, "Stereo average");
        near(0.25, averaged[1], 1e-4, "Stereo average");
        boolean refused = false;
        try { Wav.readMono(new ByteArrayInputStream(Wav.encode(mono, 1, 44_100))); } catch (IOException expected) { refused = true; }
        check(refused, "A 44.1 kHz file was read as 48 kHz");
    }

    private static void cleanerTrimsAndNormalizes() {
        // A loud button click inside the head guard, quiet room noise, then the sound itself.
        float[] raw = join(tone(0.02, 3000, 1.0), noise(0.28, 1e-4, 1), tone(0.5, 440, 0.5), noise(0.3, 1e-4, 2));
        Cleaner.Result result = Cleaner.clean(raw);
        check(result.samples != null, "Clean tone rejected: " + result.problem);
        check(!result.clipped, "Unclipped catch marked clipped");
        // 500 ms of tone plus 25 ms of pads, and up to 20 ms more while the rumble filter
        // rings out after this unnaturally abrupt ending.
        double length = result.samples.length / (double) RATE;
        check(length >= 0.525 && length <= 0.545, "Trimmed length " + length);
        near(Cleaner.TARGET_PEAK, peak(result.samples), 0.002, "Normalized peak");
        check(result.samples[0] == 0f, "No fade-in");
        // The tone starts 5 ms after the cut: the click and the silence are gone.
        int onset = firstAbove(result.samples, 0.05f);
        check(onset >= Cleaner.frames(5) - 12 && onset <= Cleaner.frames(5) + 12, "Onset at " + onset);
    }

    private static void cleanerRejects() {
        check(Cleaner.TOO_QUIET.equals(Cleaner.clean(join(noise(0.3, 1e-4, 3), tone(0.5, 440, 0.005), noise(0.3, 1e-4, 4))).problem),
                "Quiet catch accepted");
        check(Cleaner.clean(join(noise(0.3, 1e-4, 5), tone(0.03, 440, 0.5), noise(0.3, 1e-4, 6))).samples == null,
                "A 30 ms sound was accepted");
        check(Cleaner.clean(join(noise(0.3, 1e-4, 7), tone(0.08, 440, 0.5), noise(0.3, 1e-4, 8))).samples != null,
                "An 80 ms sound was rejected");
        check(Cleaner.clean(new float[100]).samples == null, "A catch shorter than the guards was accepted");
        float[] loud = join(noise(0.2, 1e-4, 9), tone(0.5, 440, 0.5), noise(0.2, 1e-4, 10));
        for (int i = 0; i < loud.length; i += 40) loud[i] = 1f;
        Cleaner.Result clipped = Cleaner.clean(loud);
        check(clipped.clipped && clipped.samples != null, "Clipping not reported");
    }

    private static final class Clock implements BeatButton.Scheduler {
        long now;
        final List<Object[]> tasks = new ArrayList<>();
        public long now() { return now; }
        public void postDelayed(Runnable action, long delay) { tasks.add(new Object[] {action, now + delay}); }
        public void remove(Runnable action) {
            for (int i = tasks.size() - 1; i >= 0; i--) if (tasks.get(i)[0] == action) tasks.remove(i);
        }
        void advance(long delta) {
            long target = now + delta;
            while (true) {
                Object[] next = null;
                for (Object[] task : tasks) if ((Long) task[1] <= target && (next == null || (Long) task[1] < (Long) next[1])) next = task;
                if (next == null) break;
                tasks.remove(next);
                now = (Long) next[1];
                ((Runnable) next[0]).run();
            }
            now = target;
        }
    }

    private static void button() {
        final Clock clock = new Clock();
        final List<String> log = new ArrayList<>();
        BeatButton button = new BeatButton(clock, new BeatButton.Actions() {
            public void onPress() { log.add("press"); }
            public void onHoldStart() { log.add("hold"); }
            public void onClick() { log.add("click"); }
            public void onHoldEnd() { log.add("release"); }
        });
        button.down(clock.now);
        check(log.equals(Arrays.asList("press")), "Press isn't immediate: " + log);
        clock.advance(120);
        button.up(clock.now);
        check(log.equals(Arrays.asList("press", "click")), "Click: " + log);

        log.clear();
        button.down(clock.now);
        clock.advance(500);
        button.up(clock.now);
        check(log.equals(Arrays.asList("press", "hold", "release")), "Hold: " + log);

        // The UI was too busy to run the hold timer, but the button was down for 600 ms.
        log.clear();
        button.down(clock.now);
        clock.now += 600;
        button.up(clock.now);
        check(log.equals(Arrays.asList("press", "hold", "release")), "Late hold: " + log);
        clock.advance(1000);
        check(log.size() == 3, "Stale hold timer fired");

        // Tapping along to a beat is only ever clicks: no refresh, no power off.
        log.clear();
        for (int i = 0; i < 8; i++) {
            button.down(clock.now);
            clock.advance(60);
            button.up(clock.now);
            clock.advance(60);
        }
        check(log.size() == 16 && !log.contains("hold"), "Fast clicks: " + log);

        log.clear();
        button.down(clock.now);
        button.down(clock.now);
        button.cancel();
        clock.advance(1000);
        button.up(clock.now);
        check(log.equals(Arrays.asList("press")), "Cancel: " + log);
    }

    private static void engineTiming() {
        near(19_440, Engine.stepOffset(3, 6000, 0.62f), 1e-3, "Swung odd step");
        near(24_000, Engine.stepOffset(4, 6000, 0.62f), 1e-9, "Even step");
        near(110, Engine.fold(55, 100), 1e-9, "Speaker thump octave");
        near(55, Engine.fold(55, 50), 1e-9, "Headphone thump octave");
        near(110, Engine.fold(440, 100), 1e-9, "Folding down");

        // 120 BPM gives 6000-frame steps; hits land exactly on their frames, bar after bar.
        Engine straight = testEngine(new Beat(120, 0.5f, 55, click("x...x...........")));
        check(onsets(straight, 2 * 16 * 6000).equals(Arrays.asList(0, 24_000, 96_000, 120_000)), "Straight timing");
        Engine swung = testEngine(new Beat(120, 0.6f, 55, click("xx..............")));
        check(onsets(swung, 16 * 6000).equals(Arrays.asList(0, 7200)), "Swing timing");
    }

    private static void engineBeatSwap() {
        Engine engine = testEngine(new Beat(120, 0.5f, 55, click("x...............")));
        List<Integer> first = onsets(engine, 48_000);
        // A new beat at half the tempo arrives mid-bar; it starts on the next bar line.
        engine.setBeat(new Beat(60, 0.5f, 55, click("..x.............")));
        List<Integer> rest = onsets(engine, 96_000);
        check(first.equals(Arrays.asList(0)), "First bar: " + first);
        check(rest.equals(Arrays.asList(120_000 - 48_000)), "Swapped at the bar line: " + rest);
    }

    private static void engineStopAndCeiling() {
        Engine engine = testEngine(new Beat(120, 0.5f, 55, click("x...x...x...x...")));
        float[] out = new float[1000];
        engine.render(out, out.length);
        engine.stop();
        check(!engine.isPlaying() && engine.isActive(), "Stop should fade before going quiet");
        engine.render(out, out.length);
        for (int i = RATE / 100; i < out.length; i++) check(out[i] == 0f, "Sound after the stop fade at " + i);
        check(!engine.isActive(), "Still active after the fade");

        float[] loud = tone(1.0, 440, 1.0);
        Beat.Track[] tracks = new Beat.Track[4];
        for (int i = 0; i < tracks.length; i++)
            tracks[i] = Beat.Track.hits(Job.BOOM, "loud", loud, 0, loud.length, Arranger.pattern("x..............."),
                    1f, i == 0, Beat.Voicing.NONE);
        Engine master = new Engine();
        master.setVolume(1f);
        master.setBeat(new Beat(120, 0.5f, 55, tracks));
        master.play();
        float[] burst = new float[256];
        float highest = 0;
        for (int at = 0; at < RATE; at += burst.length) {
            master.render(burst, burst.length);
            for (float value : burst) {
                check(!Float.isNaN(value) && !Float.isInfinite(value), "Invalid sample");
                highest = Math.max(highest, Math.abs(value));
            }
        }
        check(highest <= Engine.CEILING + 1e-6f, "Output above the ceiling: " + highest);
        check(highest > 0.5f, "The master chain crushed the level: " + highest);
    }

    private static void engineVoicing() {
        // A 50 ms length limit ends the hit early, with a fade instead of a click.
        float[] held = new float[RATE];
        Arrays.fill(held, 0.5f);
        Engine limited = testEngine(new Beat(120, 0.5f, 55, Beat.Track.hits(Job.TICK, "test", held, 0, held.length,
                Arranger.pattern("x..............."), 1f, false, new Beat.Voicing(1f, 0, 0, 50))));
        float[] out = new float[RATE / 4];
        limited.render(out, out.length);
        int heard = audible(out);
        check(heard >= 2300 && heard <= 2400, "50 ms limit played " + heard + " frames");
        // Half speed plays a 1000-frame stretch for about 2000 frames.
        Engine slow = testEngine(new Beat(120, 0.5f, 55, Beat.Track.hits(Job.BOOM, "test", held, 5000, 1000,
                Arranger.pattern("x..............."), 1f, false, new Beat.Voicing(0.5f, 0, 0, 0))));
        slow.render(out, out.length);
        heard = audible(out);
        check(heard >= 1950 && heard <= 2000, "Half speed played " + heard + " frames");
        // Filters stay finite and the low-pass takes the top off noise.
        float[] hiss = noise(0.2, 0.5, 14);
        Engine muffled = testEngine(new Beat(120, 0.5f, 55, Beat.Track.hits(Job.BOOM, "test", hiss, 0, hiss.length,
                Arranger.pattern("x..............."), 1f, false, new Beat.Voicing(1f, 500, 100, 0))));
        muffled.render(out, out.length);
        double input = 0, output = 0;
        for (int i = 1000; i < 9000; i++) {
            check(!Float.isNaN(out[i]), "Filtered sample is NaN");
            input += hiss[i] * hiss[i];
            output += out[i] * out[i];
        }
        check(output < input * 0.2, "Low-pass let through " + output / input);
    }

    private static void pitch() {
        for (double hz : new double[] {110, 220, 440}) {
            Pitch pitch = Pitch.track(tone(0.5, hz, 0.5));
            check(pitch.voicedFraction() > 0.9, hz + " Hz voiced " + pitch.voicedFraction());
            near(hz, pitch.medianHz(), hz * 0.01, "Pitch of " + hz + " Hz");
            check(pitch.spreadSemitones() < 0.1, "Steady tone spread " + pitch.spreadSemitones());
        }
        check(Pitch.track(noise(0.5, 0.3, 15)).voicedFraction() < 0.2, "Noise called pitched");
        check(Pitch.track(new float[100]).voicedFraction() == 0, "A tiny sound has no pitch");
    }

    private static void sorter() {
        float[] kick = new float[(int) (0.3 * RATE)];
        java.util.Random random = new java.util.Random(16);
        for (int i = 0; i < kick.length; i++) {
            double t = i / (double) RATE;
            kick[i] = (float) (0.9 * Math.sin(2 * Math.PI * 55 * t) * Math.exp(-t / 0.08)
                    + (i < 240 ? 0.3 * (random.nextDouble() * 2 - 1) : 0));
        }
        check(Sorter.sort(Features.of(kick)) == Job.BOOM, "Kick: " + Sorter.sort(Features.of(kick)));
        float[] hat = noise(0.06, 0.5, 17);
        check(Sorter.sort(Features.of(hat)) == Job.TICK, "Hat: " + Sorter.sort(Features.of(hat)));
        float[] snare = filtered(filtered(filtered(noise(0.15, 0.8, 18), Biquad.lowPass(2500)), Biquad.lowPass(2500)), Biquad.highPass(500));
        Features snareFeatures = Features.of(snare);
        check(Sorter.sort(snareFeatures) == Job.SNAP, "Snare: centroid " + snareFeatures.centroidHz
                + ", crossings " + snareFeatures.zeroCrossingsPerSecond);
        check(Sorter.sort(Features.of(harmonic(1.0, 220))) == Job.TUNE, "Hum");
        float[] talk = new float[(int) (1.5 * RATE)];
        double phase = 0;
        for (int i = 0; i < talk.length; i++) {
            double t = i / (double) RATE;
            double hz = t < 0.75 ? 150 + 100 * t / 0.75 : 250 - 130 * (t - 0.75) / 0.75;
            phase += 2 * Math.PI * hz / RATE;
            double syllables = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * t);
            talk[i] = (float) (syllables * (0.3 * Math.sin(phase) + 0.15 * Math.sin(2 * phase) + 0.1 * Math.sin(3 * phase)));
        }
        check(Sorter.sort(Features.of(talk)) == Job.VOICE, "Talking: " + Sorter.sort(Features.of(talk)));
    }

    private static void chopper() {
        Chopper.Result result = Chopper.chop(threeBursts());
        check(result.pieces.length == 3, "Three syllables became " + result.pieces.length + " pieces");
        int[] expected = {0, (int) (0.25 * RATE), (int) (0.5 * RATE)};
        for (int i = 0; i < 3; i++)
            check(Math.abs(result.pieces[i].onset - expected[i]) <= Cleaner.frames(12),
                    "Piece " + i + " onset " + result.pieces[i].onset);
        check(result.boom.length <= Cleaner.frames(Chopper.BOOM_MS + Chopper.PRE_ROLL_MS), "Boom cut too long");
        check(result.tick.length <= Cleaner.frames(Chopper.TICK_MS + Chopper.PRE_ROLL_MS), "Tick cut too long");
        check(Chopper.chop(tone(0.8, 220, 0.5)).pieces.length == 1, "A steady tone was chopped");
    }

    private static void snapper() {
        // 120 BPM makes 6000-frame steps.
        check(Arrays.equals(Snapper.steps(new int[] {0, 12_000, 12_500, 24_100}, 120, 0.5f), new int[] {0, 2, 3, 4}),
                "Nearest steps, collisions pushed later");
        check(Arrays.equals(Snapper.steps(new int[] {0, 6_000, 7_440}, 120, 0.62f), new int[] {0, 1, 2}),
                "Swing moves odd steps later");
        check(Snapper.steps(new int[] {0, 8 * 16 * 6000 + 10}, 120, 0.5f)[1] == -1, "Past eight bars is dropped");
        check(Snapper.bars(0) == 1 && Snapper.bars(15) == 1 && Snapper.bars(16) == 2 && Snapper.bars(40) == 4
                && Snapper.bars(127) == 8 && Snapper.bars(400) == 8, "Phrase lengths round to 1, 2, 4 or 8 bars");
    }

    private static void analysisRoundTrip() {
        float[] sound = threeBursts();
        Analysis analysis = Analysis.of(sound);
        Properties stored = new Properties();
        analysis.write(stored);
        Analysis back = Analysis.read(stored, sound.length);
        check(back != null && back.job == analysis.job && back.pieces.length == analysis.pieces.length, "Round trip");
        for (int i = 0; i < back.pieces.length; i++) check(back.pieces[i].onset == analysis.pieces[i].onset, "Onset " + i);
        check(back.boom.start == analysis.boom.start && back.tick.length == analysis.tick.length, "Cuts");
        check(Analysis.read(stored, 100) == null, "Pieces past the sound were accepted");
        stored.setProperty("analysis", "0");
        check(Analysis.read(stored, sound.length) == null, "A stale analysis was accepted");
        check(Analysis.read(new Properties(), sound.length) == null, "A missing analysis was accepted");
    }

    private static void arranger() {
        Map<Job, Arranger.Sound> none = new EnumMap<>(Job.class);
        Beat empty = Arranger.build(1, null, none);
        check(empty.trackCount() == 1 && empty.track(0).thump && empty.track(0).samples == null, "Nothing caught: thump only");

        // One voice makes a whole beat: three cut drums plus the phrase on the grid.
        float[] words = threeBursts();
        Map<Job, Arranger.Sound> sounds = new EnumMap<>(Job.class);
        sounds.put(Job.VOICE, new Arranger.Sound("20260925-143000-001", words, Analysis.of(words)));
        Beat beat = Arranger.build(7, 120, sounds);
        check(beat.bpm == 120 && beat.trackCount() == 4, "Voice beat has " + beat.trackCount() + " tracks");
        check(beat.track(0).job == Job.BOOM && beat.track(0).thump && beat.track(0).label.startsWith("cut from"), "Cut boom");
        check(beat.track(1).job == Job.SNAP && beat.track(2).job == Job.TICK, "Cut snap and tick");
        Beat.Track phrase = beat.track(3);
        check(phrase.job == Job.VOICE && phrase.length() == 16, "Phrase length " + phrase.length());
        check(phrase.hits(0) && phrase.hits(2) && phrase.hits(4) && !phrase.hits(1) && !phrase.hits(3),
                "Syllables every 250 ms land on steps 0, 2 and 4 at 120 BPM");

        // A real catch replaces its cut, and the same inputs always give the same beat.
        float[] tss = noise(0.08, 0.5, 19);
        sounds.put(Job.TICK, new Arranger.Sound("20260925-143000-002", tss, Analysis.of(tss)));
        Beat again = Arranger.build(7, 120, sounds), twice = Arranger.build(7, 120, sounds);
        check("catch".equals(again.track(2).label), "Real tick used");
        for (int t = 0; t < again.trackCount(); t++)
            for (int s = 0; s < 16; s++)
                check(again.track(t).hits(s) == twice.track(t).hits(s), "Same inputs gave a different beat");

        java.util.Set<String> booms = new java.util.HashSet<>();
        for (int number = 1; number <= 40; number++) {
            Beat numbered = Arranger.build(number, null, sounds);
            check(numbered.bpm >= Style.BOUNCE.minBpm && numbered.bpm <= Style.BOUNCE.maxBpm, "Tempo " + numbered.bpm);
            StringBuilder pattern = new StringBuilder();
            for (int s = 0; s < 16; s++) pattern.append(numbered.track(0).hits(s) ? 'x' : '.');
            booms.add(pattern.toString());
        }
        check(booms.size() >= 4, "Beat numbers barely change the kick: " + booms.size());

        rejected(new Runnable() { public void run() { new Beat(200, 0.5f, 55); } });
        rejected(new Runnable() { public void run() {
            Beat.Track.hits(Job.TICK, "", null, 0, 0, new boolean[16], 1f, false, Beat.Voicing.NONE);
        } });
        rejected(new Runnable() { public void run() {
            Beat.Track.hits(Job.TICK, "", new float[1], 0, 1, new boolean[12], 1f, false, Beat.Voicing.NONE);
        } });
        rejected(new Runnable() { public void run() {
            Beat.Track.hits(Job.TICK, "", new float[10], 5, 10, Arranger.pattern("x..............."), 1f, false, Beat.Voicing.NONE);
        } });
    }

    private static void state() throws IOException {
        File directory = Files.createTempDirectory("beats-state").toFile();
        File file = new File(directory, "state.properties");
        BeatState missing = BeatState.load(file);
        check(missing.volume == BeatState.DEFAULT_VOLUME && missing.beat == Arranger.FIRST_BEAT
                && missing.sounds.isEmpty() && missing.tempos.isEmpty(), "Missing file defaults");
        BeatState saved = new BeatState();
        saved.volume = 35;
        saved.beat = 12;
        saved.sounds.put(Job.VOICE, "20260925-143000-123");
        saved.sounds.put(Job.TICK, "20260925-143000-456");
        saved.tempos.put(12, 104);
        saved.save(file);
        BeatState loaded = BeatState.load(file);
        check(loaded.volume == 35 && loaded.beat == 12 && "20260925-143000-123".equals(loaded.sounds.get(Job.VOICE))
                && "20260925-143000-456".equals(loaded.sounds.get(Job.TICK)) && loaded.tempos.get(12) == 104, "Round trip");
        Files.write(file.toPath(), "volume=900\nbeat=abc\nsound.VOICE=../../etc\ntempo.5=500\n".getBytes("UTF-8"));
        BeatState cleaned = BeatState.load(file);
        check(cleaned.volume == 100 && cleaned.beat == Arranger.FIRST_BEAT && cleaned.sounds.isEmpty()
                && cleaned.tempos.isEmpty(), "Bad values");
        Files.write(file.toPath(), "volume=65\nvoice=20260925-141821-958\nbpm=92\n".getBytes("UTF-8"));
        BeatState first = BeatState.load(file);
        check("20260925-141821-958".equals(first.sounds.get(Job.VOICE)) && first.tempos.get(1) == 92,
                "First-version state carried over");
    }

    private static void jar() throws IOException {
        File directory = Files.createTempDirectory("beats-jar").toFile();
        final Jar jar = new Jar(directory);
        long millis = 1_790_000_000_000L;
        String first = jar.nextId(millis);
        check(Jar.isValidId(first), "ID format: " + first);
        float[] sound = tone(0.2, 330, 0.8);
        jar.saveRaw(first, sound);
        Properties about = new Properties();
        about.setProperty("job", "VOICE");
        jar.saveSound(first, sound, about);
        String second = jar.nextId(millis);
        check(!second.equals(first), "Duplicate ID handed out");
        jar.saveSound(second, sound, about);
        check(jar.soundIds().equals(Arrays.asList(second, first)), "Newest first: " + jar.soundIds());
        float[] loaded = jar.loadSound(first);
        check(loaded.length == sound.length, "Loaded length");
        near(sound[100], loaded[100], 1.0 / 32000, "Loaded sample");
        check(new File(directory, "raw/" + first + ".wav").isFile(), "Raw catch kept");
        String[] leftovers = new File(directory, "sounds").list();
        for (String name : leftovers) check(!name.endsWith(".tmp"), "Temporary file left: " + name);
        rejected(new Runnable() { public void run() {
            try { jar.saveRaw("../escape", new float[1]); } catch (IOException unexpected) { throw new AssertionError(unexpected); }
        } });
    }
}
