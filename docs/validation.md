# Validation

Tested against the installed Rabbit R1, CipherOS 7.0 / Android 16 (SDK 36),
stock v0.8.293 kernel, portrait 480 × 640 at 190 dpi.

## Evidence established

- Native APK build and v2/v3 signature verification pass.
- Fifteen gesture-state cases pass, including duplicate edges, hold vs click,
  single/double/five/eight presses, cancellation, delayed holds and delayed
  separated clicks. Tests do not invoke actual shutdown or record media.
- Native C builds for AArch64 Android API 26 with strict warnings. Parser,
  duplicate power-driver and heartbeat tests pass with address/undefined-behavior
  sanitizers on the host.
- Raw events injected through the actual wheel input device navigate the home
  screen and scroll the app list without changing media volume.
- Raw power-driver events open the selected item. Three Home → Camera → Home
  cycles reconnect correctly with unique FIFO leases.
- Camera wheel directions were read back as front `0`, rear `180`, and privacy
  `90` after returning home.
- Android's vibrator service records Rabbit Phone's haptic requests. The user's
  tactile experience still requires a hands-on check.
- Phone, Messages, Music and Settings launch into separate tasks and return to
  the default Rabbit Phone home; no call or message was sent.
- A real reboot completed with Android reporting `sys.boot_completed=1`; the
  init service reached `running`, and its debug PID matched the helper PID.
- Stopping the UI process for longer than the heartbeat lease released the input
  grabs. A raw PMIC power-button event then put Android to sleep; resuming the UI
  and waking it restored controls.
- Terminating the helper caused init to restart it, and the foreground app
  automatically reconnected to the replacement process.

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
- SIM calling/SMS, carrier registration, GPS fixes, and standby battery life
  have not been certified by these checks.
- USB `0e8d:20ff` with a HID-only interface matched the firmware's charger mode
  during installation. It did not prove an Android boot failure.
