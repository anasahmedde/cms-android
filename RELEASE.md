# DGX Player — Release Signing & Fleet Update

## Signing (v9.0.0+)

Release builds are signed automatically from a **gitignored** `keystore.properties`
in the repo root (never committed). Create it once on the release machine:

```properties
storeFile=/Users/anasahmed/.dgx-keys/dgx-release.jks
storePassword=<keystore password>
keyAlias=key0
keyPassword=<key password>
```

The keystore is the fleet signing key (cert SHA-256
`29:2B:F6:0F:B2:11:23:A8:69:C9:61:F8:5D:60:D8:B5:B3:FA:49:1C:1C:94:CC:B2:B6:A3:0E:72:41:89:81:02`).
It is escrowed at `~/.dgx-keys/dgx-release.jks`. **Losing it permanently breaks
fleet self-update — keep the backup.** The password currently sits in the public
git history (README) and should be rotated with `keytool -storepasswd` /
`-keypasswd` (rotation does NOT invalidate already-published APK signatures).

Build the signed release APK:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Verify before shipping:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-release.apk
# Signer #1 SHA-256 must equal 292bf60f...898102 (else the fleet won't update)
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app-release.apk | head -1
# versionCode must be strictly greater than the live version.json
```

## Fleet update (S3 `dgx-release-bucket`)

The full 6-step process is in [APK_UPDATE_GUIDE.md](APK_UPDATE_GUIDE.md). In short:

1. Upload the signed APK: `releases/app-v<version>.apk`
2. Update `s3-setup/version.json` (versionCode, versionName, apkUrl, `checksum` =
   `shasum -a 256 app-release.apk`, releaseNotes) and upload it to the bucket root.
3. Devices poll `version.json`, download, verify the SHA-256, and prompt to install.

**Staged rollout (do this — the fleet is ~15k screens):** publish the APK object
first, but point `version.json` at the new version for a small pilot only, or keep
`forceUpdate:false` and update a handful of devices manually. Confirm they render
templates and play content, soak for a day, then flip the fleet-wide `version.json`.

⚠️ Publishing `version.json` is the production cutover — it is outward-facing and
hits real screens. Do it deliberately, never as part of a routine merge.

## What v10.0.0 adds

- **Video in template media zones**: a `media` zone bound to a video (external
  URL or presigned S3) now plays inline — a muted, looping ExoPlayer streamed
  from the URL. Only the FIRST video zone gets a decoder (extra video zones
  fall back to their background box) so total decoders stay at two (main
  playlist + one zone), clear of the Qualcomm multi-decoder traps. QR zones
  stay image-only. Players MUST call `TemplateRenderer.release()` on overlay
  swap/clear/destroy — wired in the activity.
- **Solid zone backgrounds render** (was gradient/image only) — pairs with the
  backend folding `style.bg_color` into resolved content.
- **Fixes**: enrollment polling now resumes after pause/resume (a screen added
  in the dashboard while the app was backgrounded no longer stays on the
  "Not Enrolled" screen until a kill); template zone geometry uses the TARGET
  orientation's dimensions, so a landscape template on a portrait device no
  longer draws zones swapped.

Signed v10 checksum: `8f1aff6c708089ccae7507297e8945fe81944f6f4301ecc7f333cd072e7ddae2`
(signer cert SHA-256 `292bf60f…` — verified identical to the live fleet signer).

## What v9.0.0 added

Screen-template rendering: when a device's company has a linked template, the
player renders the template zones (text / clock / ticker / image media+qr) as an
overlay and plays the device's assigned content in the template's playlist zone;
no template linked → unchanged behavior. Also adds `app_version` to the heartbeat
so the fleet's installed versions are finally visible in the dashboard.
