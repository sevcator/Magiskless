# Reisenless engineering commentaries

This file records non-obvious project decisions. It is stored at the repository root and is not included in any Android resource, asset, native payload, or release package.

## Runtime logging

Runtime diagnostic logging is disabled in the Android app, the root service, the daemon, Zygisk, and magiskinit. Disabled native levels are rejected before Rust or C++ formats their messages, except for fatal error paths that must still enforce process-exit semantics. Command-line tools keep their direct output because it is part of their interface rather than retained runtime logging. The app has no Logs screen, log repository, log database, HTTP logger, Timber dependency, or log export action. Superuser notifications remain available because they are user-facing access signals rather than retained logs.

The `logging` column remains in the Magisk policy database schema and is always written as zero. Removing the column would make existing installations and upstream-compatible databases unsafe to migrate. No runtime path reads or acts on the value.

The DenyList logcat reader is not a diagnostic logger. It consumes Android process-start events when the Zygisk path is unavailable, so removing it would break DenyList enforcement. Its own diagnostic output remains disabled by the global native sink.

## App hiding and restoration

Hiding and restoration are package migrations exposed only from Settings. A migration passes its previous package explicitly, validates the source and target against one-time markers in the root database, installs and launches the replacement before uninstalling the old package, rolls back newly installed packages if launch fails, clears the markers and migration extras before relaunching, and deletes its staging directory in a `finally` block. A target can finish an authenticated pending migration after launch extras are lost, but it never imports preference extras unless they match the database markers. This avoids referrer-dependent behavior, forged configuration imports, forged uninstall requests, relaunch loops, orphaned hidden packages, and cached APK residue.

If the optional test package is installed, migration repackages it from the current namespace to the target namespace. Both APKs roll back together on failure, and the target removes both old packages only after it starts with root access. This works for hide, re-hide, and restore operations without leaving a stale hidden test package.

## Cleanup policy

Startup removes the retired `sulogs.db`, legacy migration APKs, cached update-note Markdown files, and abandoned migration/flash/install staging directories. Failed flash preparation removes its staging directory immediately, and the temporary OTA `bootctl` payload is deleted in a `finally` block. The app does not open a root shell merely to remove a log file that Reisenless no longer creates. Network cache and functional installation output are retained because deleting them would increase network use or remove information needed during an active operation.

## Locales

Release resources contain English, Russian, Simplified Chinese, Traditional Chinese, and Japanese. Japanese was retained from the upstream Magisk resources. The separate lowercase-English locale was removed; Reisenless-owned English and Russian interface text is written in lowercase directly. Android version and theme overlays are not locale packs and remain present.

## Installed version display

An inactive legacy Home state previously converted `R.string.not_available` itself to text, displaying its decimal Android resource ID. It now resolves the resource value before display. Release version names are the source commit identifier and version codes are derived from the build timestamp, keeping Android's integer version ordering while tying each artifact to its source.

## Theme selection

The app no longer follows the system theme, uses Android dynamic colors, or exposes named theme presets. The persisted mode is normalized to Light or Dark, including old installations whose previous value meant system/default. Primary and secondary accents are stored independently, selected with RGB editors, and applied as separate overlays, so changing one never resets the other. The primary accent defaults to purple and the secondary accent defaults to pink. The legacy resource-based UI selects the closest bundled overlay while retaining and previewing the exact RGB value.

## Reisenless identity and startup

The primary app ID is `io.sevcator.reisenless`. Hidden installs still use generated package IDs, and the stub retains its fixed loader namespace because it is rewritten during hiding. User-facing Magisk branding is Reisenless, while low-level Magisk protocol, binary, database, and source identifiers remain unchanged for compatibility.

There is no separate startup-color preference. The platform splash uses a pink background and the same pink-to-purple Reisen artwork used by the launcher, home screen, hidden-app stub, and installer surfaces. The drawable resource is the single packaged source of truth.

## Startup lifecycle

The platform splash is installed before `Activity.onCreate`, and the final activity theme plus accent overlays are established before view inflation. UI creation no longer waits for the root Binder service; root-backed operations connect lazily. Shell initialization has a bounded timeout so a missing or incompatible installed daemon cannot leave the application permanently on its logo.

## Runtime and repository scope

The main native binary remains because it is the superuser daemon, module mount engine, Zygisk coordinator, boot-stage dispatcher, and settings database endpoint. Only its redundant `--install-module` CLI command was removed; module installation in the application remains. The separate resetprop executable target was removed, while the embedded resetprop applet used by modules and the daemon remains.

The standalone test APK, downloaded third-party test modules, Cuttlefish scripts, and emulator-matrix CI jobs are not release inputs and were removed. CI now performs one release native build and one release APK build, then uploads the APK.

## Boot-integrated Udonge

Udonge is a first-party Reisenless boot payload, not an installable module. `build.py` compiles its DEX and ABI-specific Zygisk libraries into a reproducible `udonge.bin`; the image patcher stores that payload in the boot image, and `magiskinit` exposes it to the root daemon. The daemon installs a versioned runtime below `/data/adb/udonge` before ordinary modules are applied and injects Udonge into Zygisk through a reserved internal entry that is never mounted, scripted, or shown as a user module.

The enabled marker, keybox sources, and refresh request live in Udonge state rather than Magisk module metadata. Keybox downloads accept HTTPS sources only, are rate-limited to one automatic attempt per day, and select the structurally valid candidate with the most certificate entries. This is an input-quality check, not proof that Google has not revoked a keybox; no client-side implementation can guarantee server-side strong-integrity acceptance. Runtime files and TEE payloads are replaced only when the embedded Reisenless version changes, and DEX transfer is limited to the Google Play services attestation process to avoid per-process IPC and repeated flash writes.

Runtime installation verifies every architecture-specific payload before activation, keeps the previous runtime until the replacement is active, and restores it after an interrupted swap. A Zygisk rejection marker disables further Udonge injection attempts until a newer verified runtime clears it, preventing a bad payload from being retried on every boot. Disabled installations skip post-fs-data work entirely.

The service has a stale-lock-safe single-instance guard, atomically replaces keybox and TEE runtime files, and records the supervising TEE process instead of scanning every process command line. Certification restarts Google Play services only once per embedded Udonge version. DEX bytes travel over the private Zygisk companion socket; API 23-25 fallback files use the target application's private code cache and are deleted immediately, so no world-readable `/data/local/tmp` artifact remains. AVD image patching and live emulator setup include the same boot-owned payload as ordinary image patching.
