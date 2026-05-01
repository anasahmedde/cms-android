# DGX Player — APK Update Guide

## Overview

The app checks for updates automatically on every launch. When a newer version is available on S3, it shows an "Update Available" dialog. The user taps **Update**, the APK downloads, and Android prompts to install it.

---

## Prerequisites (One-Time Setup)

### 1. AWS CLI installed and configured
```bash
aws configure --profile dgx
# AWS Access Key ID:     → your key
# AWS Secret Access Key: → your secret
# Default region:        → us-east-2
# Output format:         → json
```

### 2. S3 bucket exists and is public
Bucket: `dgx-release-bucket` (region: `us-east-2`)

Bucket policy (set once in AWS Console → S3 → Permissions → Bucket Policy):
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicRead",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::dgx-release-bucket/*"
    }
  ]
}
```

### 3. Phone setup (one-time)
- Settings → Apps → Special App Access → Install Unknown Apps → **DGX Player** → Allow
- Or: Settings → Biometrics and Security → Install Unknown Apps → allow for your browser/Files app

---

## How to Release a New Version

Follow these 6 steps every time you want to push an update.

---

### Step 1 — Bump the version in `app/build.gradle`

Open [app/build.gradle](app/build.gradle) and increment both values:

```gradle
defaultConfig {
    versionCode 6        ← must be strictly greater than current installed version
    versionName "6.0.1"  ← human-readable label
}
```

> **Rule:** `versionCode` must always go up by at least 1. Never reuse a number.

---

### Step 2 — Build the signed Release APK

In Android Studio:
```
Build → Generate Signed Bundle / APK → APK → Release → Finish
```

Output file:
```
cms-android/app/release/app-release.apk
```

> Always use the **same keystore** you created initially (`dgx-player.jks`).
> If you lose the keystore, users will have to uninstall and reinstall manually.

---

### Step 3 — Get the SHA-256 checksum

```bash
sha256sum ~/dgx-latest/git/cms-android/app/release/app-release.apk
```

Copy the hash — you'll need it in Step 4.

---

### Step 4 — Update `version.json`

Edit [s3-setup/version.json](s3-setup/version.json):

```json
{
  "versionCode": 6,
  "versionName": "6.0.1",
  "apkUrl": "https://dgx-release-bucket.s3.us-east-2.amazonaws.com/releases/app-v6.0.1.apk",
  "checksum": "PASTE_SHA256_HERE",
  "releaseNotes": "What changed in this version",
  "forceUpdate": false
}
```

> Set `"forceUpdate": true` to block the app until the user installs the update.

---

### Step 5 — Upload to S3

```bash
# Rename APK with version number
cp ~/dgx-latest/git/cms-android/app/release/app-release.apk \
   ~/dgx-latest/git/cms-android/app/release/app-v6.0.1.apk

# Upload APK
aws s3 cp ~/dgx-latest/git/cms-android/app/release/app-v6.0.1.apk \
    s3://dgx-release-bucket/releases/app-v6.0.1.apk --profile dgx

# Upload version.json (this triggers the update for all devices)
aws s3 cp ~/dgx-latest/git/cms-android/s3-setup/version.json \
    s3://dgx-release-bucket/version.json --profile dgx
```

---

### Step 6 — Verify it's live

```bash
curl https://dgx-release-bucket.s3.us-east-2.amazonaws.com/version.json
```

Expected output:
```json
{
  "versionCode": 6,
  "versionName": "6.0.1",
  ...
}
```

---

## How the App Receives the Update

| Trigger | What happens |
|---------|-------------|
| App opens | Checks S3 immediately |
| Every 6 hours (background) | WorkManager checks S3 silently |

When `versionCode` in S3 > installed `versionCode`:
1. Dialog appears: **"Update Available — v6.0.1"**
2. User taps **Update**
3. APK downloads via system DownloadManager (progress shown)
4. SHA-256 checksum is verified
5. Android install prompt appears
6. User taps **Install**
7. App restarts automatically with new version

---

## Version History

| Version | Code | Date | Notes |
|---------|------|------|-------|
| 1.0.0 | 1 | 2026-03-24 | Initial release |
| 2.0.0 | 2 | 2026-03-24 | Test update |
| 3.0.0 | 3 | 2026-03-24 | Test update |
| 4.0.0 | 4 | 2026-03-24 | Fix crash on Android 13+ (RECEIVER_EXPORTED) |
| 5.0.0 | 5 | 2026-03-24 | Verify fix |
| 6.0.1 | 6 | 2026-03-31 | Reports device name/ID search, temperature modal name fix |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| No update dialog appears | Check `versionCode` in S3 is greater than installed version |
| "Access Denied" from S3 | Re-apply the bucket policy in AWS Console |
| Checksum mismatch error | Re-run `sha256sum` and update `version.json` with correct hash |
| "Install unknown apps" blocked | Phone Settings → allow install from DGX Player |
| App crashes on update tap | Ensure you are on v4+ (fixed `RECEIVER_EXPORTED` bug) |

---

## Quick Reference — Full Release Command Block

```bash
# 1. After building in Android Studio:
sha256sum ~/dgx-latest/git/cms-android/app/release/app-release.apk

# 2. Edit s3-setup/version.json with new versionCode, versionName, apkUrl, checksum

# 3. Upload
VERSION=7.0.4
cp ~/dgx-latest/git/cms-android/app/release/app-release.apk \
   ~/dgx-latest/git/cms-android/app/release/app-v${VERSION}.apk

aws s3 cp ~/dgx-latest/git/cms-android/app/release/app-v${VERSION}.apk \
    s3://dgx-release-bucket/releases/app-v${VERSION}.apk --profile dgx

aws s3 cp ~/dgx-latest/git/cms-android/s3-setup/version.json \
    s3://dgx-release-bucket/version.json --profile dgx

# 4. Verify
curl https://dgx-release-bucket.s3.us-east-2.amazonaws.com/version.json
```
