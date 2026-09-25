# Beats: sound catcher design

Status: steps 0 to 2 are built, as of 2026-09-25. Steps 3 to 5 are planned.

## In plain words

The R1 becomes a pocket beat toy. You and your partner hold the side button and say
made-up words into it. The R1 cleans each sound up, works out what job it should do,
and drops it into a beat that's already playing. It cuts drums out of your words, snaps
the syllables onto the beat, and builds a little song that starts quiet, builds up,
drops, and takes a break. You spin the wheel to hear other beats made from the same
sounds. When you like one, you tap Keep and it saves a song file. Everything runs on
the R1 with no internet.

It's built in five small steps, and each one gets played with on the R1 before the
next one starts.

## Goals and non-goals

Goals:

- Catch short sounds, mostly two people saying made-up words and repeating them, and
  turn them into a beat that sounds good without any music skill.
- Keep one control rule everywhere: tap a thing, then spin the wheel to change it.
- Keep the sound kit small: five moves, each something Timbaland, Ye, or Fred again..
  actually does.
- Work fully offline, and never lose a caught sound, even across reflashes.

Not in v1:

- Internet features.
- Playing with the screen off.
- A library of saved beats.
- Voices pitch-corrected to sing in tune.
- Effects beyond the five moves.

## Where it lives

Beats is a card in the Home card stack (`beats`, lime, four-pad glyph), right after
**recorder**. Selecting the card opens `BeatsPage`, a `HardwarePage` inside
`HomeActivity`'s window. It follows the Timer, Translator, Gallery and Settings pages,
and the host draws the back button and clock header above it.

### Side button routing

A page can claim the raw side button by returning true from
`HardwarePage.ownsButton()`. While such a page is open, `HomeActivity` sends the helper's
DOWN and UP edges to `buttonDown` and `buttonUp` instead of `ButtonGestures`. So inside
Beats:

- Holding the button catches a sound instead of opening the recorder overlay.
- The 5-press refresh and 8-press power-off don't apply, because tapping along to a beat
  would trigger them.
- A click acts on release, without the 320 ms wait for a possible double press.

The page that received a DOWN also receives its UP. Pausing, losing focus, a helper
disconnect or a page change cancels the press through `buttonCancel`. Quick settings and
the recorder overlay keep the normal gesture path while they're open. Everywhere else in
the launcher, the button behaves exactly as before.

## How you play it

| Input | Action |
| --- | --- |
| Side button, press and hold | Catch a sound. Recording starts at the press and the beat fades out at the same moment. It stops at 10 s. |
| Side button, click (under 450 ms) | Play or stop. A press already silenced the beat, so a click while playing leaves it stopped; a click while stopped starts it. The audio recorded during a click is discarded. |
| Catch pad, press and hold | The same catch, by touch. A short tap shows "Hold to catch a sound." |
| Tap a chip | Focus it: beat number (the default), tempo, or volume. Tapping **play** toggles playback. |
| Wheel | Change the focused chip. Wheel up lowers the value and down raises it, like the Timer page. A new beat number takes effect once the wheel has been still for 250 ms. |
| Select key | Play or stop. |

While a catch is held, the pad pulses on every beat at the current tempo, strongest on
the bar line, so words can be chanted in time while the beat itself is quiet. After a
catch, the new sound plays straight away. Leaving the page stops playback. The
beat also stops after 10 minutes with no input, and the screen stays on only while
playing.

Planned screens beyond step 1:

- Sound (moves, job, swap, remove, toss).
- Grid (16 squares per row).
- The full beat view with up to 8 rows and the song-part bar.

See the mockups at <https://claude.ai/artifact/CiugzCy2W56W6P9rbiBRoZ> (private).

## Sounds, jobs and spots

Jobs:

- **BOOM**: short, low sounds. Plays the first 250 ms, low-passed at 1.5 kHz, over the
  hidden thump.
- **SNAP**: short mid-range sounds. High-passed at 200 Hz and cut at 180 ms.
- **TICK**: short bright sounds, like "t", "s" and "k". High-passed at 3 kHz and cut at
  90 ms.
- **TUNE**: steady pitched sounds. Tuned to the key; plays melodies and chords.
- **VOICE**: talking and anything long. Syllables are snapped to the beat.

**Hidden thump.** A BOOM hit also plays a short synthesized thump. It's a sine that
falls from 2.5 times its pitch in about 30 ms and decays 60 dB over 180 ms. Its pitch is
the song's root note: 100 to 200 Hz on the built-in speaker, 50 to 100 Hz otherwise.
This keeps the boom audible on the R1's small speaker. Step 1 plays the thump alone on
the pattern `x..x..x...x.x...`.

**Spots** (step 3):

- A beat holds at most 8 spots, and it always has a BOOM, a SNAP and a TICK.
- When one of those jobs is missing, it's cut from the newest voice's syllables.
- When the spots are full, a new sound replaces, in order: a cut spot with the same job,
  the oldest spot with the same job, then the oldest VOICE or TUNE spot.
- It never removes the last spot of a drum job.
- Every catch stays in the jar until it's tossed.
- A spot's pattern comes from a hash of the beat number, the spot, and the job. So
  catching a new sound never reshuffles the other rows.

## How it sounds good

1. **Catch.** 48 kHz mono 16-bit AudioRecord. The source preference is `UNPROCESSED`,
   then `VOICE_RECOGNITION`, then `MIC`. This R1 doesn't report unprocessed support, so
   it uses voice recognition. The recorder is created when Beats opens and started on the
   press.
2. **Clean** (`Cleaner`).
   - Drop 40 ms from the head and 80 ms from the tail, where the button's own click is.
   - Remove DC with a 20 Hz one-pole high-pass.
   - Trim from 5 ms before the first 5 ms window within 30 dB of the loudest window, to
     20 ms after the last window within 40 dB.
   - Fade in over 2 ms and out over 15 ms, then set the peak to -1 dBFS.
   - Reject catches that peak below -40 dBFS or keep less than 60 ms of sound: "Didn't
     hear much. Try closer."
   - Warn "Too loud. Back off a bit." when more than 0.5% of samples are clipped.
   - A catch that ends abruptly keeps up to about 15 ms of the high-pass's ring-out.
3. **Drop in fast.** The cleaned sound plays within a second of release. Analysis runs
   in the background from step 2 on.
4. **Analyze and sort** (`Features`, `Pitch`, `Sorter`).
   - Features: duration, power-weighted centroid, band shares, attack, decay, and YIN
     pitch at 16 kHz.
   - Only frames within 20 dB of the loudest set the centroid, and only loud frames count
     toward how pitched a sound is. Otherwise the hiss in pauses skews both.
   - YIN accepts its deepest dip under 0.35 when nothing crosses 0.15.
   - Sounds of 400 ms or less go to a drum job: BOOM under 400 Hz with over 25% below
     150 Hz, TICK over 3.5 kHz, SNAP otherwise.
   - Zero crossings are measured but not used, because a quiet mic's hiss crosses zero
     constantly.
   - Longer sounds go to TUNE when at least 70% of the loud frames are pitched within 0.5
     semitones. Everything else goes to VOICE.
   - The first real catch (4.4 s of two voices) came out as VOICE with 14 pieces and a
     1,047 Hz centroid. It took 120 to 220 ms on the Mac.
5. **Chop and snap** (`Chopper`, `Snapper`).
   - Split at envelope dips at least 9 dB deep, into pieces at least 80 ms long.
   - Pick cut-drum candidates from the pieces.
   - Snap each piece's onset to the nearest swung 16th, pushing collisions to the next
     free step. The phrase repeats every 1, 2, 4 or 8 bars.
   - Cut drums use their own voicing:
     - BOOM is pitched down 5 semitones, low-passed at 1.2 kHz, and limited to 250 ms,
       over the thump.
     - SNAP is high-passed at 250 Hz and limited to 180 ms.
     - TICK is high-passed at 3.5 kHz and limited to 90 ms.
     - Each hit fades out before its limit or its stretch ends.
   - Voices are never pitch-shifted into the key.
6. **Fit the key** (step 3, TUNE only).
   - The root is one of C, D, E, F, G or A.
   - BOUNCE and SOUL use minor pentatonic; RAVE uses natural minor.
   - A TUNE sound is shifted by at most 2 semitones, then played at scale degrees.
   - Pads use minor triads.
7. **Arrange** (step 3). Styles, song form, and voice turns, below.
8. **Play** (`Engine`).
   - Sample-accurate, swung sequencing.
   - 24 voices with choke and linear resampling.
   - Master chain: speaker-mode high-pass (100 Hz on the speaker, 25 Hz otherwise), then
     a 2:1 glue compressor at -12 dB, then tanh soft clip, then a -1 dBFS peak limiter,
     then smoothed volume.
   - Stop fades over 10 ms.

## Styles and song form (step 3)

| Style (after) | BPM | Swing | BOOM | SNAP | TICK | Voice defaults | Extras |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BOUNCE (Timbaland) | 92 to 104 | 62% | Syncopated | 2 and 4, plus ghosts | 16ths, with 32nd rolls at part ends | none | Off-beat TUNE stabs |
| SOUL (Ye) | 84 to 94 | 56% | Boom-bap | 2 and 4 | 8ths | Pitch +3 | Intro muffle at 60 |
| RAVE (Fred again..) | 124 to 132 | 50% | Four on the floor | 2 and 4 | Off-beat 8ths | Space 40 | Pump on |

Song form with 4 or more spots: INTRO (4 bars, once per play), BUILD (4), DROP (8),
BREAK (4), then back to BUILD. With fewer than 4 spots, a single 4-bar LOOP plays.

- **INTRO:** one voice, the TUNE chord and sparse TICK, with the mix muffled at 70.
- **BUILD:** adds SNAP and TICK. The muffle opens to 0 across the part, and the last bar
  rolls.
- **DROP:** every spot. The two active voices take turns phrase by phrase.
- **BREAK:** no BOOM or TICK. One voice is chopped with Space 60 and the other plays
  whole.

At most 2 voices are active in a part, and they only overlap in BREAK.

## Moves (step 4)

| Move (after) | Range | Effect |
| --- | --- | --- |
| PITCH (Ye) | -12 to +12 semitones | Resampling, so higher is also faster |
| CHOP (Fred again..) | off, 2, 4, 8 | Cut into pieces; each hit plays the next piece. On voice rows it switches to piece-per-hit mode. |
| FLIP (Timbaland) | off, on | Plays the sound in reverse |
| SPACE (Fred again..) | 0 to 100 | Reverb send from 0 to 0.8 |
| MUFFLE (Fred again..) | 0 to 100 | Low-pass cutoff from 18 kHz down to 250 Hz |

## Code

Pure Java units live in `com.kevtrinh.rabbitphone.beats`. `scripts/check.py` refuses
Android imports there.

| Unit | Job |
| --- | --- |
| `Wav` | 16-bit PCM WAV codec with a symmetric scale, so a round trip keeps the level |
| `Cleaner` | Clean stage and rejection reasons |
| `BeatButton` | Press, then click or hold. No multi-press. Event times decide late holds. |
| `Beat` | Immutable tempo, swing, root and tracks of whole 16-step bars |
| `Engine` | Sequencer, voices, thump and master chain. `render` doesn't allocate, and new beats swap in at bar lines. |
| `Job`, `Style` | Jobs, plus the BOUNCE pattern bank (six kick, six snare, six hat and four tune patterns) with its 92 to 104 BPM range and 62% swing |
| `Fft`, `Biquad` | Radix-2 FFT, and Butterworth low- and high-pass coefficients |
| `Pitch`, `Features`, `Sorter` | Pitch tracking, measurements, and the job guess |
| `Chopper`, `Snapper` | Syllable pieces with drum cuts, and pieces on the grid |
| `Analysis` | Job, features, pieces and cuts, stored in the sound's `.properties` and redone when the version changes |
| `Arranger` | The newest sound per job, with missing drums cut from the newest voice (or tune). The beat number picks the tempo and each row's pattern through a stable hash, so a new catch never reshuffles other rows. |
| `BeatState` | Volume, beat number, newest sound per job, and tempos set by hand per beat, stored as `.properties`. First-version files carry over. |
| `Jar` | Raw and cleaned WAVs plus metadata, written to a synced temporary file and then renamed. `load` analyses stored sounds that lack a current analysis. |

Android glue:

| Unit | Job |
| --- | --- |
| `BeatsPage` | UI, focus and wheel handling, both buttons, audio focus, lifecycle, and saving only on real changes |
| `CatchMic` | Recorder source choice, press-to-release capture, level updates, and the 10 s limit |
| `BeatSpeaker` | Low-latency float stereo AudioTrack on an urgent-audio thread. The buffer starts at 4 bursts and grows one burst per underrun, up to 16. Routing is tracked for speaker mode. |

Threads:

- The main thread handles UI, input and the helper heartbeat. It never runs cleaning or
  rendering.
- A single worker cleans, saves and loads.
- The audio thread renders.

## Storage and backup

- Everything lives in `/sdcard/Android/data/com.kevtrinh.rabbitphone/files/beats/`:
  - `raw/<id>.wav`: the untouched capture, kept even when the catch was rejected.
  - `sounds/<id>.wav` and `sounds/<id>.properties`: the cleaned sound and what's known
    about it.
  - `state.properties`: the current beat.
  - IDs look like `yyyyMMdd-HHmmss-SSS`.
- `scripts/pull_beats.py` copies that folder into
  `/Volumes/1tb/r1-firmware/beats-backup/<date>/`, outside Git. Recordings are never
  committed.
- To restore by hand, push the files back, then
  `chown -R <app uid>:ext_data_rw` and `restorecon -RF` on the
  `/data/media/0/Android/data/...` path. Pushed files otherwise get `storage_file` labels
  that SELinux blocks the app from reading. `restore_beats.py` (step 5) will do this.
- The signing key is backed up at `/Volumes/1tb/r1-firmware/keys/local-build.keystore`.
  It matches the installed app (SHA-256 `950136…7b70`). A different key would force an
  uninstall, which deletes app data.

## Errors and edge cases

| Situation | Behavior |
| --- | --- |
| Mic permission missing | Requested on the first hold: "Allow the mic to catch sounds, then hold again." |
| Mic busy | "The mic is busy. Try again in a moment." |
| Quiet or tiny catch | "Didn't hear much. Try closer." The raw file is still kept. |
| Clipped catch | Kept, with "Too loud. Back off a bit." |
| 10 s limit | A haptic, then the catch finishes. The release that follows does nothing. |
| No catch yet and play pressed | "Catch a sound first." |
| Output failure | "Sound output isn't available." |
| Audio focus lost | Stop. |
| Storage write fails | "Couldn't save that sound. Storage may be full." The sound still plays this session. |
| Unreadable state file | Defaults are used, and the file isn't overwritten unless something changes. |
| Page leaves or focus is lost mid-catch | The catch is discarded and the microphone released. |

## Testing

`tests/BeatsTest.java` runs from `scripts/check.py`. It covers 16 groups:

- WAV round trip and rate check.
- Cleaning: click guard, onset within 12 frames, and normalization.
- Rejections.
- The button: click, hold, late hold, eight fast presses, and cancel.
- Exact step frames with and without swing.
- Bar-line swaps with a tempo change.
- Stop fade and ceiling.
- Voicing limits and filters.
- Pitch within 1%.
- Sorting synthetic kick, hat, snare, hum and talking.
- Chopping three syllables to within 12 ms.
- Snapping with swing, collisions and the eight-bar cap.
- Stored-analysis round trip.
- One voice making a whole beat, deterministic beats, and varied kicks across beat
  numbers.
- State parsing.
- Jar IDs, order and path safety.

`scripts/taste_test.py` runs the analysis over real catches on the Mac. For example,
point it at a `pull_beats.py` backup's `sounds` folder, or at `raw` with `--raw`. It
prints each sound's measurements and can render a preview with `--render out.wav --beat N`.
Recordings stay outside Git.

On the R1, Beats analyses a little noise and renders two silent seconds on its worker when
it opens, so the JIT is warm. The first second of playback then took 566 µs per 5.3 ms
burst instead of 6,651, and steady playback of four rows takes about 850 µs, with no
underruns.

## Build order

0. **Prep** (done). The signing key is backed up, the toolchain link points at
   `/Volumes/1tb/r1-firmware/toolchain`, and the check script runs the Beats tests.
1. **Hear it** (built, waiting on hands-on checks). The card, page, catch, clean, jar,
   state, engine, speaker mode, button routing, volume and tempo, and the backup script
   are in. The owner still needs to check these with the real button and microphone:
   - The first syllable survives in 10 of 10 catches.
   - No button click is audible.
   - The boom is audible at a normal media volume.
   - 10 minutes of play has no glitches.
   - Eight fast clicks do nothing.
   - It's fun.
2. **Sort and snap** (built, waiting on hands-on checks). Pitch, features, sorter,
   chopper, snapper, cut drums, BOUNCE patterns, beat numbers on the wheel, and the flash
   while catching are in. Still to do:
   - Label real catches to measure the sorter.
   - Check that words sound on the beat.
   - Taste check.
3. **Song:** 8 spots, SOUL and RAVE, song form, voice turns, key fit and chords, and
   pump.
4. **Tinker:** Sound and Grid screens, moves, and job changes.
5. **Keep:** song export to Music and `restore_beats.py`.

## Premortem: likely failures and fixes

| If it failed because... | Fix |
| --- | --- |
| Everything was built before anyone heard it | Five playable steps |
| Looped talking sounded off-beat | Syllables snapped to 16ths, plus the flash while catching (step 2) |
| The first word was clipped (a hold only registers after 450 ms) | Record from the press |
| Every sound started with the button click | Head and tail guards |
| The boom vanished on the small speaker | A thump pitched for the speaker, plus the speaker high-pass |
| 8 quick presses powered off the R1, or play felt slow | Raw button routing with no multi-press |
| A reflash or a new key wiped the sounds | Pull script, key backup, raw audio kept |
| Wrong job guesses couldn't be fixed | Job change on the Sound screen (step 4) |
| No volume control, dead battery | Volume chip, screen on only while playing, 10-minute auto-stop |
| Real voices failed where synthetic tests passed | Real-sound set (step 2) |
| The build broke after a folder move | The toolchain link is repointed, and paths are regenerated from its contents |
| A blocked main thread let the helper release the button | No analysis or rendering on the main thread |
| Glitches from garbage collection or CPU load | Allocation-free render and an adaptive buffer. Measured about 600 µs per 5.3 ms burst in step 1. |

## Later ideas

- Words that sing in tune.
- An optional online helper.
- A photo attached to each sound.
- Playing with the screen off.
- A library of saved beats.
