# System font probe and recovery

`FontProbe.java` runs through `app_process` with normal Android graphics APIs.
It loads each padded file through `Font.Builder` and `CustomFallbackBuilder`,
requests regular/bold/upright/italic styles and variation settings, then requires
nonempty Latin and digit Canvas rasters. Unsupported axes on a static font may
report false. No proprietary font is included in this repository or the APK.
The raw process first initializes the system Typeface map through Android 16's
hidden `loadPreinstalledSystemFontMap()` entry point, because it has not received
normal app initialization. This method is verified against
[AOSP Android 16 Typeface.java](https://android.googlesource.com/platform/frameworks/base/+/android16-release/graphics/java/android/graphics/Typeface.java#1663).
Bootstrap and rendering failures print their stack trace and exit with status 1.

Compile without connecting to a device:

```sh
python3 scripts/install_system_font.py build-probe
```

On the previously verified Android 16 R1 with independent root ADB, prepare a
new private transaction. This pulls exact backups and metadata and runs the
probe on `/data`; it does not change system files or stop Android:

```sh
python3 scripts/install_system_font.py prepare \
  --font evidence/theme/stock-fonts/PowerGroteskRegular.otf \
  --transaction evidence/system-font-01
```

Review `transaction.json`, `probe-output.txt` and the numbered `.before`/`.after`
files. Retain the entire ignored transaction directory outside Git. Apply stops
the framework, checks that fonts are no longer mapped, and writes only the five
recorded existing inodes without truncation. It verifies lengths, hashes,
ownership, permissions and SELinux labels and returns both mounts to read-only
before restarting Android:

```sh
python3 scripts/install_system_font.py apply --transaction evidence/system-font-01
python3 scripts/install_system_font.py restore --transaction evidence/system-font-01
```

After an interrupted apply or restore, reconnect ADB and run **restore with the
same transaction**, without starting Android manually. The durable journal can
recover partially written files only when their bytes match that transaction's
old/new payloads; unrelated changes are rejected. Failures after stopping leave
Android stopped and attempt both read-only remounts. If ADB is disconnected,
read-only recovery cannot be guaranteed until reconnecting. Keep the host and
device powered throughout this operation. UI and reboot checks remain necessary
after a successful apply; apps with their own bundled fonts keep those fonts.
