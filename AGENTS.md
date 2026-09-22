# Rabbit Phone development

- Keep this scoped to the existing R1 Android installation. Do not flash ROMs,
  unlock/relock bootloaders, change kernels, wipe data, or remove system apps.
- Preserve the private signing key and per-device rollback backups outside Git.
  Never commit recordings, credentials, device identifiers, or raw device logs.
- Follow the existing native Java/C structure. There are no app dependencies or
  Gradle requirements. `app/` is normal app privilege; `hardware/` is a narrowly
  bounded root helper. Do not add a network root shell or arbitrary-command API.
- Power interception must fail open on pause, lock, client death, missing
  heartbeat, and daemon failure. Do not add permanent wake locks or disable
  SELinux. Keep timestamps, session isolation and duplicate-driver handling.
- Preserve normal call/SMS intents. Never place calls, send messages, capture
  photos, or record audio as an incidental test. Media capture needs an explicit
  user action or an explicitly announced and authorized device test.
- Run build/signature checks and meaningful gesture/native-state tests. Hardware
  changes also need focused device checks and reboot/startup evidence. Do not
  substitute injected UI keys for the physical driver's actual mapped key path.
- Check exact saved startup-file hashes before updating/restoring the helper.
  Return the system mount to read-only even when deployment fails.
- No project SCAR category is mapped here. Do not import unrelated project rules.

## Publishing identity

- Publish this repository only as `burningportra` to `burningportra/rabbit-phone`.
  Verify the authenticated account before creating repositories or pushing.
- Use `burningportra <198369936+burningportra@users.noreply.github.com>` for commits.
  Do not inherit an employer account or signing key from global Git settings.
- Signing is disabled locally until a personal signing key is explicitly chosen
  and verified. Do not turn global work-key signing back on for this repository.
