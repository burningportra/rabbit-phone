# Validation

Tested against the installed Rabbit R1, CipherOS 7.0 / Android 16 (SDK 36),
stock v0.8.293 kernel, portrait 480 × 640 at 190 dpi.

## Evidence established

- Native APK build and v2/v3 signature verification pass. The current 0.3.0
  build is 90,515 bytes with SHA-256
  `a2bd32098319bf3dfa6911ecef254e389d1318b00d7d942448541b98c082c60d`.
- Fifteen gesture-state cases pass, including duplicate edges, hold vs click,
  single/double/five/eight presses, cancellation, delayed holds and delayed
  separated clicks. Tests do not invoke actual shutdown or record media.
- Native C builds for AArch64 Android API 26 with strict warnings. Parser,
  duplicate power-driver and heartbeat tests pass with address/undefined-behavior
  sanitizers on the host.
- Fifty-five Python cases pass: 6 Contacts resource-patch cases, 8 bundled-font
  cases, 34 tracked-APK/session cases and 7 theme boot/restore cases.
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
| Rabbit Phone Home, All apps, Utilities, camera and theme preview | Main surfaces render at 480 × 640 with Power Grotesk and the Rabbit palette | Photo capture and voice-note playback remain explicit user tests |
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

## Important boundaries

- Runtime haptics, microphone quality and physical feel are separate from a
  driver advertising capabilities. Do not report them as physically verified
  solely from API success.
- The eight-click shutdown dispatch is covered in host state tests. Repeated
  shutdowns are not part of routine automated UI tests.
- Camera capture/save and voice-note playback need explicit user-driven media
  checks. Automated navigation checks avoid creating incidental photos/audio.
- The theme is not a pixel-identical rabbitOS clone. Apps with hard-coded colors,
  Flutter rendering, imagery or web content can retain their own visuals.
- Rabbit Phone provides no Rabbit cloud assistant or service. The five-press
  refresh updates only the local interface.
- SIM calling/SMS, carrier registration, GPS fixes, and standby battery life
  have not been certified by these checks.
- USB `0e8d:20ff` with a HID-only interface matched the firmware's charger mode
  during installation. It did not prove an Android boot failure.
