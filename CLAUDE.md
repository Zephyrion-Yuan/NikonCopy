# NikonCopy

Android app for Xiaomi 14 (HyperOS, Android 16 / SDK 36) that copies NEF, JPG, MP4 from a Nikon Z f camera connected via USB OTG to a user-chosen directory on the phone.

## Build

```bash
# Requires: JDK 17, Android SDK platform-35, build-tools 35.0.0
# SDK location is in local.properties (not committed)
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Install & Test

```bash
# Wired adb (phone plugged into Mac):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Wireless adb (phone has Nikon on OTG, Mac uses wifi):
# First-time: pair via Settings → Developer Options → Wireless Debugging → Pair with code
adb pair <pairing-ip>:<pairing-port>   # enter 6-digit code
adb connect 192.168.124.19:<debug-port>
adb -s 192.168.124.19:<port> install -r app/build/outputs/apk/debug/app-debug.apk
```

Xiaomi requires "USB install" enabled in Developer Options.

## Architecture

- **Kotlin + Jetpack Compose + Material 3** — `compileSdk 35`, `minSdk 30`, `targetSdk 35`
- **AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01** — pinned via `build.gradle.kts`
- **Gradle wrapper 8.10.2** — `./gradlew` bootstraps automatically

### Source tree

```
app/src/main/java/com/garag/nikoncopy/
├── MainActivity.kt              NavHost (home ↔ settings)
├── ui/
│   ├── HomeScreen.kt            Two big buttons + progress card
│   ├── SettingsScreen.kt        Destination dir, default camera dir, clear cache
│   └── theme/                   Google-blue + white Material 3
├── data/
│   ├── SettingsRepository.kt    DataStore prefs (dest/source URI, cache record)
│   └── ManifestStore.kt         Append-only text file of successfully-copied names
├── copy/
│   ├── CopyState.kt             Sealed class: Idle/Scanning/Running/Done/Failed
│   ├── CopyMode.kt              ALL / INCREMENTAL
│   ├── CopyEngine.kt            Core: two flow producers (SAF + direct PTP), enrichment
│   └── CopyService.kt           Foreground service, tries direct then SAF
├── viewmodel/CopyViewModel.kt   Binds to CopyService, exposes StateFlow
└── mtp/
    ├── PtpClient.kt             Raw PTP over USB (force-claim, bulk transfer)
    └── NikonDirect.kt           USB permission + PtpClient factory
```

### Copy flow

1. **Source**: SAF `OPEN_DOCUMENT_TREE` picker for Nikon (via `com.android.mtp` DocumentsProvider). Direct PTP path exists but is disabled on Android 16 (see below).
2. **Destination**: `MediaStore.Images.Media.insert()` when under Pictures/DCIM/Movies; SAF `createDocument` otherwise. MediaStore gives us row ownership → DATE_TAKEN updates succeed.
3. **Filter**: NEF/JPG/JPEG/MP4 by extension. Skip by name if already in destination.
4. **Incremental**: additional skip if name is in `ManifestStore` (tracks past successes).
5. **Enrichment** (per file, after copy): EXIF DateTimeOriginal via `androidx.exifinterface` → update `MediaStore.DATE_TAKEN` + attempt `Os.utimensat` for file mtime.
6. **Progress**: `channelFlow` + periodic emitter coroutine reading `AtomicLong` counters every 200ms.

## Known Android 16 restrictions

| What | Why | Impact |
|---|---|---|
| `setHiddenApiExemptions` blocked | `core-platform-api` fully denied | Can't bypass hidden-API checks |
| `Os.utimensat` reflection blocked | depends on the above | `setMtime` always fails; mtime stays at "now" |
| `Os.ioctlInt` blocked | same | `USBDEVFS_RESET` unavailable; can't evict `com.android.mtp` |
| Direct PTP path dead | above three combined | `ENABLE_DIRECT_PTP = false` in NikonDirect.kt |
| MediaStore update SecurityException | SAF-created files owned by system | Fixed by switching to `MediaStore.insert()` |

## Logcat tags

| Tag | What |
|---|---|
| `CopyEngine` | Flow lifecycle, per-file timing, DATE_TAKEN verify |
| `CopyService` | Direct vs SAF decision |
| `PtpClient` | USB endpoints, PTP commands (when direct path enabled) |
| `NikonDirect` | USB permission, device discovery |
| `MtimeUtil` | utimensat reflection status |

Filter: `adb logcat -d | grep -E 'CopyEngine|CopyService|PtpClient|NikonDirect|MtimeUtil|DATE_TAKEN'`

## Key design decisions

- **同名跳过 (skip by name)** — no rename suffixes. Idempotent re-runs.
- **Manifest-based incremental** — not timestamp-based. Interrupted copies resume; user-deleted files don't re-import.
- **MediaStore destination** for image/video MIME — we own the row, can set DATE_TAKEN. SAF fallback for non-media folders.
- **Direct PTP disabled by default** — `com.android.mtp` holds the camera's PTP session; Android 16 blocks all reflection paths to evict it. Set `ENABLE_DIRECT_PTP = true` in NikonDirect.kt for rooted/older devices.

## Repo layout

```
nikon/
├── app/                    Android app source
├── nikon_debug/            USB debug scripts, logs, temp-root tools (not part of app)
├── APP_DEV_LOG.md          Detailed development journal with experiment results
├── CLAUDE.md               This file
└── .gitignore
```
