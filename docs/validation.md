# Validation

Tested against the installed Rabbit R1, CipherOS 7.0 / Android 16 (SDK 36),
stock v0.8.293 kernel, portrait 480 × 640 at 190 dpi.

## Evidence established

- Native APK build and v2/v3 signature verification pass. The current 0.5.0
  build is 131,475 bytes with SHA-256
  `df5e52da140398d046caff6f422fa8239309a0eb1624d29c2e46589cc5dc3689`.
- Fifteen gesture-state cases pass, including duplicate edges, hold vs click,
  single/double/five/eight presses, cancellation, delayed holds and delayed
  separated clicks. Tests do not invoke actual shutdown or record media.
- Native C builds for AArch64 Android API 26 with strict warnings. Parser,
  duplicate power-driver and heartbeat tests pass with address/undefined-behavior
  sanitizers on the host.
- Sixty-eight Python cases pass: 6 Contacts resource-patch cases, 8 bundled-font
  cases, 34 tracked-APK/session cases, 7 theme boot/restore cases, 9 Assistant
  profile transactions and 4 helper-update/rollback cases.
- Raw events injected through the actual wheel input device navigate the home
  screen and scroll the app list without changing media volume.
- Raw power-driver events open the selected item. Three Home → Camera → Home
  cycles reconnect correctly with unique FIFO leases.
- Camera wheel directions were read back as front `0`, rear `180`, and privacy
  `90` after returning home.
- Android's vibrator service records Rabbit Phone's haptic requests. The user
  also confirmed that scrolling vibrates with USB unplugged.
- Phone, Messages, Music and Settings launch into separate tasks and return to
  the default Rabbit Phone home; no call or message was sent.
- A real reboot completed with Android reporting `sys.boot_completed=1`; the
  init service reached `running`, and its debug PID matched the helper PID.
- Stopping the UI process for longer than the heartbeat lease released the input
  grabs. A raw PMIC power-button event then put Android to sleep; resuming the UI
  and waking it restored controls.
- Terminating the helper caused init to restart it, and the foreground app
  automatically reconnected to the replacement process.
- With USB unplugged, the user confirmed wheel navigation, side-button
  selection, and hold/release local voice-note recording all work.

## Custom recorder evidence

- Raw DOWN/UP events through the discovered PMIC power driver opened the custom
  recorder in both Home and the built-in Camera. The same private hardware lease
  remained active through hold and release; no Activity handoff was required.
- Android reported the microphone running during each hold and stopped after
  release. Each test produced exactly one private AAC/MPEG-4 note at 44.1 kHz mono,
  lasting 5.37 seconds in Home and 5.51 seconds in Camera. Saved feedback and
  elapsed time were visible at the real 480 × 640 display size.
- Device screenshots show the live microphone meter, recording controls, saved
  actions and playback state with the verified private Power Grotesk font.
  Starting another hold during playback returned to the timer and live meter.
- Launching Settings during the second hold stopped the microphone and removed
  the unfinished take in both hosts. All deliberately created test audio was
  removed, and the note-directory hash snapshot returned to its original state.
- Independent source review covered permission retry, partial-file finalization,
  playback callbacks, input routing, focus/pause/refresh teardown and library
  selection. It caught and verified fixes for playback replacing the new recorder
  screen and a queued library-scroll callback reading a cleared view.

The checks used `scripts/verify_recorder.py --record-test`, once normally and once
with `--camera`. Ignored `evidence/recorder/home/` and `camera/` retain screenshots
and structured receipts. They do not certify microphone quality, speaker sound,
the 60-second cap on hardware, or a new unplugged physical-gesture check.

## Standby and other-app assistant route

- The installed ROM reports support for long-power while non-interactive. The
  reversible Assistant profile selects `RecorderAssistActivity` and Android's
  `LONG_PRESS_POWER_ASSISTANT` behavior; no emergency/SOS setting was changed.
- Starting with no Rabbit foreground lease, a raw PMIC DOWN from sleeping
  keyguard launched the recorder through SystemUI. It dismissed the current
  non-credential keyguard through Android's API, consumed a one-shot passive
  handoff marker, started the microphone, and saved 3.10 seconds of valid AAC
  after the original UP. The same route from awake Settings saved 3.12 seconds.
- Both runs stopped microphone access on release, reconnected ordinary foreground
  controls, returned to Settings on **Done**, and retained normal short-power
  sleep. Screenshots verify the custom recording and saved views at 480 × 640.
  Both runs removed their own audio and restored the initial note-file snapshot.
- A direct launch from the unprivileged shell UID was rejected with a permission
  denial for `ACCESS_VOICE_INTERACTION_SERVICE`. SystemUI holds that permission
  and successfully launched the Activity through the actual power gesture.
- Native host checks cover initially held/released keys, staggered duplicate
  drivers, repeat/debounce behavior, terminal isolation, a 65-second observation
  deadline, exact marker parsing and PING-only observer commands. The observer
  never grabs either input stream; Android receives its original release.
- The helper update verified the existing startup hash, retained the prior
  binary, and used the already-installed init service. Root and product stayed
  read-only and SELinux stayed enforcing.
- After a real reboot, the helper binary matched the compiled build, the startup
  file matched its saved hash, and the helper PID matched init's service PID.
  The Assistant role/setting journal still matched the device. A new standby
  hold saved 2.98 seconds of valid AAC, returned to Settings, and preserved short
  power-button sleep; its audio was removed afterward.
- During separate intentional short holds, opening Settings or stopping the
  helper stopped microphone access and left no saved or partial take. Restarting
  the init service restored the helper. No previous note files changed.

The assistant checks are `scripts/verify_assistant_recorder.py --record-test`
and its `--from-app` variant. Receipts and screenshots stay in ignored
`evidence/recorder/assistant-standby/` and `assistant-app/`. PIN-authenticated
unlock and microphone/speaker quality have not been physically certified.

## Playback volume fix (0.4.2)

- The silent-playback report was traced to `STREAM_MUSIC` on the speaker at
  0/15. Android showed the app's media players starting but muted by stream and
  port volume. On-device decoding confirmed that existing notes contained
  nonzero PCM; no existing audio was exported or transcribed.
- Media volume was restored to 5/15. A saved note then produced an active,
  unmuted player routed to the speaker. AudioFlinger reported an active app track
  with finite gain and `PortMuted=false`, rather than the previous zero gain.
  This verifies Android's output path; subjective loudness was not measured.
- Saved and library views now show the current media level and explicit off
  state, with −/+ controls. Device checks verified one-step touch adjustments,
  actual wheel selection plus side-button activation, unchanged hardware lease,
  and that Play does not secretly raise zero volume.
- A short deliberate test take verified the Saved view controls and that the
  volume selection survives playback completion. The test take was deleted;
  hashes of all pre-existing notes remained unchanged. Exiting stopped playback.
- Build/signature checks, native sanitizer cases, all 15 gesture cases and
  all 68 Python cases passed. No helper, assistant profile or system font was
  changed for this fix; microphone access is idle after verification.

Reproducible checks are `scripts/probe_note_signal.py --analyze-notes` and
`scripts/verify_playback.py --play-note`. Receipts, audio-service snapshots and
UI captures remain local under ignored `evidence/playback/`.

## Reel-to-reel craft and preference persistence (0.5.0)

- The recorder was rebuilt around the official Magic Recorder reference: paired
  red outline reels, a large Power Grotesk elapsed counter, microphone level
  ticks, playback position and compact native transport icons. All artwork is
  drawn locally; the stock reference bitmap is not included in the app or repo.
- Separate on-device recording and playback frames show both reel positions
  changing. Playback reads actual MediaPlayer position/duration; recording uses
  real elapsed time and microphone amplitude. The saved reel phase was identical
  before and after playback. No random waveform or synthetic level is displayed.
- With Android animations disabled, the microphone still recorded and the reels
  remained static across timed captures. The original animation setting was
  restored. Inactive/hidden/detached views stop their frame callbacks; requests
  are capped at 30 per second and extrapolation is bounded to 100 ms.
- Independent review covered overlay timers, media completion, lifecycle cleanup,
  focus/selection retention and reduced motion. Actual 480 × 640 captures show
  the saved-page transport and volume controls fully visible.
- A UI-selected speaker volume of 12/15 survived app force-stop/reopen and an
  actual reboot, with `volume_music_speaker=12` and matching recorder text. A
  separate UI-selected zero level survived the same checks and displayed
  **Media volume off** after reboot. Each completed test restored its current
  baseline; the user's newer 10/15 choice was preserved after the zero test.
- Volume persistence uses Android's existing per-output-device setting. No
  competing app preference or startup default was added. The verifier waits for
  the finite boot-theme job before reopening the UI and declines to overwrite
  external volume changes or disturb an active recording.
- All deliberate microphone test takes were deleted; existing note hashes were
  preserved. Receipts and intermediate images are in ignored `evidence/craft/`.

Run `scripts/verify_volume_persistence.py --change-volume --reboot` and its
`--zero` variant for preference checks. `scripts/capture_recorder_demo.py
--record-test --from-standby` creates a short screen-video demonstration after
the recorder is foreground, exports no audio track, and removes its test note.

## Theme evidence established

- The private source font identifies as Power Grotesk Regular 1.100 and matches
  the expected SHA-256. It remains outside Git and the Rabbit Phone APK; only
  ignored local replacement APKs contain it for the owner's device.
- The `FontProbe` run rasterized Android Latin text, digits, requested styles
  and variation-axis cases from both staged font allocations, then reported
  `PROBE_OK` before the system-font transaction became eligible to apply.
- System font transaction `system-font-02` applied with no pending writes. It
  verified the two font files and three font configuration files against the
  device-bound journal, preserved inode metadata, and returned `/` and
  `/product` to read-only under enforcing SELinux.
- Resource lookups verified all 98 requested Android system colors. App overlay
  receipts verified Calendar 58, Contacts 42, Cipher Messaging 7 and F-Droid 5
  color resources; Contacts also uses the reviewed orange FAB asset.
- Normal same-certificate PackageInstaller sessions applied the verified private
  Power Grotesk file to the bundled text-font entries in Notes, Recorder, Gallery
  and Weather while preserving icon fonts and app data.
- The Contacts v2 replacement preserved package data, resource IDs and layout
  structure while adding explicit text colors in four compiled XML layouts and
  changing two reviewed ARSC style values, nine bytes total. Its main explanatory
  text is now visibly warm white and readable.
- SystemUI was installed through Android's supported staged PackageInstaller
  path. The update first recorded `READY` without claiming installation. After
  a real reboot, finalization required `APPLIED`, a changed boot ID, and the exact
  prepared APK hash, signing certificate and version. No `/system` APK or SELinux
  policy was edited; the original and replacement use the matching AOSP
  development certificate already trusted by this build.
- A real post-theme reboot completed with Android booted, Rabbit Phone holding
  Home, and the hardware helper running. The reboot receipt also read back
  primary `#ffff5a1f`, background `#ff0a0a09`, small-clock size `68.0dip`,
  line spacing `1.0`, four app palettes and five font/config files. The finite
  theme service was stopped after its one-shot work, SELinux remained enforcing,
  and both system mounts were read-only.
- The captured keyguard image at ignored `evidence/theme/audit/lock.png` shows
  the genuine Android lock screen with Power Grotesk, warm-white hours and orange
  minutes. The compact-clock setting was checked with notification visibility
  temporarily hidden and then restored. Normal unlock works; no PIN is configured,
  so PIN behavior is not certified.

## Visual app audit

All 20 activities exposed by Rabbit Phone's launcher opened after the theme and
reported their own expected package in front. That establishes launchability and
main-surface rendering; it is not a full CRUD, call, SMS or media certification.

| Surface | Verified result | Boundary |
| --- | --- | --- |
| Rabbit Phone Home, All apps, Utilities, camera and theme preview | Main surfaces render at 480 × 640 with Power Grotesk and the Rabbit palette | Photo capture remains an explicit user test; custom recorder evidence is listed above |
| Android lock screen | Actual keyguard screenshot shows Power Grotesk, warm-white hours, orange minutes, compact layout and the lower Rabbit wallpaper; normal unlock works | No PIN is configured, so PIN behavior was not tested |
| Phone/Contacts | Phone keypad, toolbar, main and empty-state text, orange account/import actions and FAB were visually readable without placing a call | The blank Create contact form exposed zero `EditText` fields with the app color overlay disabled; contact creation remains unverified without claiming a proven root cause |
| Messages | An actual raw wheel-down event followed by one PMIC side-button press opened Messages | No SMS was composed or sent |
| Notes | Power Grotesk is confirmed in the app | Its blue action is Flutter-owned and intentionally remains blue |
| Recorder, Gallery and Weather | Their bundled text fonts now use verified Power Grotesk | No recording or media was created; Weather condition imagery remains intact |
| Calendar, F-Droid, Settings, Clock, Compass and Music | Main surfaces opened with their applicable palette/font layers | Editing, downloads and playback were not exercised |
| Chrome, Files, Aurora Store and Cipher Camera | Main surfaces and controls opened with the system typography and their supported theme layers | Web content, store artwork and camera imagery retain their own appearance; no camera media was saved |
| Web content and Cloak | The already-installed `com.android.webview` was reselected, after which Cloak opened successfully | No browser was installed; remote page design remains outside the theme contract |

Per-device receipts and screenshots are retained in ignored `evidence/`. Public
source does not include those logs, identifiers, signing keys, or media.

## Screen-fill correction in 0.11

The launcher now uses 24 px side margins on the 480×640 R1 display, with 432 px
cards and feature pages. Home has a larger clock and rabbit, and Quick Settings
uses the screen height with matching slider and dismissal touch regions. This
corrects the earlier inset interpretation of the demo; device density remains
190 and physical resolution remains 480×640.

The signed build and host checks passed, including native sanitizers, 15 gesture
cases, 15 timer-state groups, card navigation and 68 Python tests. All 20
active-card device assertions passed with the larger timer bounds and 16 px
next-card cue. Quick Settings brightness/volume changes, reopening, app-restart
persistence and restoration passed. Saved notes remained unchanged and the
microphone stayed idle. No hardware helper, media backend or system-profile
changes are part of this sizing correction.

Final screenshots were inspected on the device for Home, the catalog, active
Timer, both Timer setup modes, Translator, Gallery grid/viewer, Recorder
library/detail/ready, Quick Settings and Settings. Settings subtitles and bottom
controls fit without clipping. The installed APK matched the signed build hash;
all ten existing notes, media volume and display settings were preserved. The
visual pass opened existing media details without playback or capture.

## Important boundaries

- Runtime haptics, microphone quality and physical feel are separate from a
  driver advertising capabilities. Do not report them as physically verified
  solely from API success.
- The eight-click shutdown dispatch is covered in host state tests. Repeated
  shutdowns are not part of routine automated UI tests.
- Camera capture/save and subjective playback quality need explicit user-driven
  media checks. Only the announced recorder check creates and plays test audio;
  other automated navigation checks avoid incidental media.
- The theme is not a pixel-identical rabbitOS clone. Apps with hard-coded colors,
  Flutter rendering, imagery or web content can retain their own visuals.
- Rabbit Phone provides no Rabbit cloud assistant or service. The five-press
  refresh updates only the local interface.
- SIM calling/SMS, carrier registration, GPS fixes, and standby battery life
  have not been certified by these checks.
- USB `0e8d:20ff` with a HID-only interface matched the firmware's charger mode
  during installation. It did not prove an Android boot failure.
