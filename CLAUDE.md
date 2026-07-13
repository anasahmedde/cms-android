# cms-android — Rules

"DGX Player" — Java digital-signage player (core logic in `FullScreenPlayerActivity`). Devices poll the FastAPI backend (heartbeat 30s, layout 10s), play content via Media3 ExoPlayer (single / split / grid layouts) with offline fallback, and self-update from the public S3 bucket `dgx-release-bucket` via `version.json`.

## Operational warnings

- **The live fleet (v7.0.4) has the STAGING API URL baked in** — an APK's base URL decides which backend real devices talk to. Any URL change is a fleet-wide event; treat it as a release decision, never a casual edit.
- **The authoritative branch is `release-1.1`** — GitHub `staging` and `main` are stale (Feb 2026). Base work on `release-1.1` until branches are normalized (goal: release-1.1 → staging → main, then follow the standard flow).
- The signing keystore (`dgx-player.jks`) exists only on one machine with no backup. Before any release: back up / escrow the keystore. Losing it permanently breaks fleet self-update.
- Fleet state is invisible: the heartbeat carries no app version. When touching the heartbeat, add version telemetry.

## Git flow (server-enforced)

- Never push directly to `staging` or `main` (blocked by rulesets, no admin bypass).
- Flow: `feature/*` | `fix/*` | `chore/*` branch → PR → `staging`; releases: PR `staging → main` (the only head branch main accepts — required `enforce-branch-flow` check).
- Descriptive branch names; never reuse throwaway branches (`temp*` is banned); delete after merge. Conventional commits. One logical change per PR.
- A release PR must include: `versionCode` + `versionName` bump, signed APK uploaded to `dgx-release-bucket`, `version.json` updated, keystore backed up. Never commit APKs, keystores, or passwords to git (a committed APK and a plaintext password in README are known debt — remove on touch).

## Engineering rules

- No hardcoded environment URLs in source — single config point, environment chosen at build time.
- Every HTTP helper has explicit connect/read timeouts and a failure path (several known helpers have none).
- Polling/background loops must survive the activity lifecycle (known bug: enrollment polling permanently dies after pause/resume — fix on touch, don't replicate the pattern).
- Bitmap decoding must be sampled to the target view size (known OOM risk in grid mode).
- Playback changes must cover **all three layout modes** (single, split, grid) AND the offline cached-layout path — a feature that only works online or only in single mode is not done.
- Don't grow the God-activity: new subsystems go in their own classes. Delete dead code (TV time-sharing mode, unused OkHttp) when touching its area.
- No silent failures: log errors with context; a device that can't reach the server must keep playing cached content, never black-screen.

## Definition of Done

- [ ] Works in single, split, and grid layouts
- [ ] Works offline from the cached layout
- [ ] Survives pause/resume and reboot (autostart path)
- [ ] Backend + dashboard touchpoints updated (device routes, WS status events)
- [ ] Verified on a real device/emulator against staging before release
