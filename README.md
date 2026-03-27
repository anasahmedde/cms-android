# CMS Android

An Android video player application for the Digix CMS platform. Devices register with the backend, download assigned content from S3, and play it continuously according to the configured layout.

## Overview

The app runs on Android devices (phones, tablets, signage screens) and:

- Polls the backend for content updates and layout changes
- Downloads assigned videos and images from AWS S3
- Plays content in single or multi-screen grid layouts
- Supports sequential playlist mode for single-screen devices
- Reports online status, temperature, storage, and play counts back to the backend
- Handles live layout switches (single ↔ grid) without restart

---

## Features

### Playback Modes

| Mode | Description |
|------|-------------|
| `single` | One video/image plays full-screen, looping |
| `single` + sequential | Multiple videos play in user-defined order, looping |
| `split_h` | 2 videos side by side (horizontal split) |
| `split_v` | 2 videos stacked (vertical split) |
| `grid_3` | 3 videos — 2 on top, 1 full-width below |
| `grid_4` | 4 videos in 2×2 grid |
| `grid_1x4` | 4 videos stacked vertically (1 column × 4 rows) |

### Grid Position Awareness
Videos are placed in the exact slot configured in the CMS (not packed left). Empty slots stay empty — the device only plays slots that have content assigned.

### Content Sync
- Polls `GET /device/{mobile_id}/videos/downloads` for presigned S3 URLs
- Downloads missing files to local storage
- Detects layout changes and switches modes cleanly (full codec/surface teardown to prevent BufferQueue deadlocks)
- Supports `download_status` flag from backend to trigger re-sync

### Live Updates
- Polls server every ~30 seconds for metadata and layout changes
- Applies rotation/fit mode changes live without restarting playback
- Handles grid → single and single → grid transitions safely

### Reporting
- Heartbeat: online status, temperature
- Storage: total/used/available bytes
- Play counts: daily and monthly

---

## Architecture

```
FullScreenPlayerActivity.java
├── fetchLayoutModeAndMetadata()   — initial load on startup
├── pollServerForUpdates()         — periodic polling loop
├── playMixedPlaylist()            — single mode: all assigned content in sequence
├── playLocalPlaylistOrToast()     — video-only playlist with ExoPlayer
├── playGridVideos()               — multi-slot grid playback
├── switchToSingleMode()           — safe teardown + reinit for single mode
├── switchToGridMode()             — safe teardown + reinit for grid mode
└── smartSyncVideos()              — download missing files from S3
```

---

## Setup

### Prerequisites
- Android Studio (latest stable)
- Android device or emulator running API 26+
- CMS Backend accessible from the device network

### Build

1. Clone the repository
2. Open in Android Studio
3. Configure the backend URL in the app (or via environment/build config)
4. Build and run on device

### Device Registration
On first launch the app registers itself with the backend using the device's `mobile_id` (Android ID). The backend then assigns content to that device ID via the CMS frontend.

---

## Key Files

| File | Description |
|------|-------------|
| `FullScreenPlayerActivity.java` | Main activity — all playback, polling, layout logic |
| `res/layout/activity_full_screen_player.xml` | Root layout (FrameLayout container) |
| `AndroidManifest.xml` | Permissions: internet, storage, wake lock |

---

## Dependencies

- **ExoPlayer (androidx.media3)** — video playback
- **OkHttp / HttpURLConnection** — API polling and S3 downloads
- **Android BitmapFactory** — image display

---

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `dev` | Active development — all changes pushed directly here |
| Feature/fix work is committed directly to `dev` (no PRs for Android) |

---

## Known Behaviour

- **Empty grid slots** render as black (no content, no player initialized for that slot)
- **Sequential mode** requires the backend to return videos with correct `grid_position` values; the app sorts by `grid_position` and plays in that order
- **Codec safety**: switching layouts always fully releases existing `MediaCodec` instances and `Surface` objects before creating new ones, preventing Qualcomm `BufferQueue` deadlocks
