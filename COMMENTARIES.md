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

Startup removes the retired `sulogs.db`, legacy migration APKs, cached update-note Markdown files, abandoned migration/flash/install staging directories, and the retired `/cache/magisk.log`. Failed flash preparation removes its staging directory immediately, and the temporary OTA `bootctl` payload is deleted in a `finally` block. Network cache and functional installation output are retained because deleting them would increase network use or remove information needed during an active operation.

## Locales

Release resources contain English, lowercase English (`en-Latn-US-lower`), Russian, Simplified Chinese, and Traditional Chinese. The lowercase variant duplicates English text with every displayed character converted to lowercase; it is separate from the normal English locale and also covers the hidden-app stub. Missing strings in the other retained locales were completed so builds do not silently fall back to English. Android version and theme overlays are not locale packs and remain present.

## Installed version display

An inactive legacy Home state previously converted `R.string.not_available` itself to text, displaying its decimal Android resource ID. It now resolves the resource value before display. Native installed-version reporting remains `versionString (versionCode)` and keeps version code `30700` for this build line.

## Theme selection

The app no longer follows the system theme, uses Android dynamic colors, or exposes named theme presets. The persisted mode is normalized to Light or Dark, including old installations whose previous value meant system/default. Primary and secondary accents are stored independently and applied as separate overlays, so changing one never resets the other. Both accents default to pink.

## Reisenless identity and startup

The primary app ID is `io.sevcator.reisenless`. Hidden installs still use generated package IDs, and the stub retains its fixed loader namespace because it is rewritten during hiding. User-facing Magisk branding is Reisenless, while low-level Magisk protocol, binary, database, and source identifiers remain unchanged for compatibility.

The startup color is stored independently from the light/dark and accent selections. Pink is the default. Pink, purple, blue, green, amber, red, and teal use the white Reisen silhouette; white uses the black silhouette to preserve contrast. The original root-level image is copied into the Android drawable resources and removed so there is a single packaged source of truth.
