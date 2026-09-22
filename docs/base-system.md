# Verified Android base

This repository customizes an already working Android installation. It does not
include firmware images or an automatic flashing script.

The installed base is CipherOS 7.0 for `r1`, Android 16 / SDK 36, fingerprint
`rabbit/cipher_r1/r1:16/BP2A.250605.031.A2/eng.ubuntu:userdebug/test-keys`.
It uses stock v0.8.293 boot/DTBO components with the CipherOS system, system_ext,
product and vendor images on slot A. Full Android boot and the visible launcher
were verified before this project's customization began.

The verified CipherOS download was
`CipherOS-7.0-ALHENA-cipher_r1-20250623-0753-BETA-OFFICIAL-VANILLA.zip`:

```text
SHA-256  9ba81b11e9bce06dd604204fcdb2d3d43998931066edfb339a16aaa78e705eb0
SHA-1    08592581bbb1f5d3320cab1596fb7f00e8f286cc
```

The publisher's displayed 40-character checksum was labeled SHA-256 but matched
SHA-1. ZIP integrity, payload SHA-256, and extracted partition hashes were checked.
This is an OTA payload archive, not a fastboot-update ZIP with `android-info.txt`.

Two device-specific findings matter for later troubleshooting:

- Bootloader slot fallback made earlier failed attempts appear inconsistent.
  Check the actual active slot and boot outcome; a successful partition write is
  not a successful OS boot.
- USB `0e8d:20ff` with only a HID interface matched the vendor's charger-mode
  configuration. Unplugging and powering on normally produced a full Android
  boot; the Orange State warning alone was not evidence of a boot loop.

This ROM reports locked/green properties to Android even though the kernel
command line reports Orange State on the owner-unlocked device. That makes
`adb remount` reject the device. The helper installer uses a reversible ext4
remount after checking the kernel command line, and verifies every changed byte.

Sources: [CipherOS R1](https://cipheros.org.in/devices/r1),
[official Rabbit firmware](https://github.com/rabbit-hmi-oss/firmware/releases),
[community flashing reference](https://github.com/TurboTheTurtle/rabbit-r1-firmware/blob/main/docs/flashing-guide.md).
