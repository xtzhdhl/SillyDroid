# SillyDroid

Personal build mirror for SillyDroid (the Android standalone build of SillyTavern): a fork of
[jialmaster/SillyDroid](https://github.com/jialmaster/SillyDroid) that packages official SillyTavern
releases into an installable APK and ships in-app updates.

- Upstream host app: `jialmaster/SillyDroid`
- Upstream tavern: `SillyTavern/SillyTavern`
- Single goal of this repo: **the phone reliably sees new versions, upgrades in place, and never loses data.**

## How the update chain works

```
New SillyTavern release / new commit in this repo
        │
        ├─ track-upstream-tavern.yml (daily, 02:00 UTC = 10:00 UTC+8)
        │     └─ official tag != last built tag → dispatch a build
        │
        ├─ sillydroid-upstream-apk.yml (builds the APK, publishes a GitHub Release)
        │     └─ produces *.apk + *.apk.sha256 + *.update.json
        │
        ├─ publish-fork-meta.yml (on build success: assemble latest.json and commit it to master)
        │     └─ also purges the jsDelivr cache and verifies the CDN picked it up
        │
        └─ App start / "Check for updates"
              └─ read latest.json → compare versionName → prompt download → verify SHA256 → in-place install
```

### Endpoints the app reads

```
https://cdn.jsdelivr.net/gh/xtzhdhl/SillyDroid@master/.github/fork-meta/latest.json
```

That URL is a compile-time constant in the APK (`SILLYDROID_LATEST_RELEASE_METADATA_URL`).
The client tries the same file on several mirrors in order, so a single blocked or stale CDN no
longer breaks update checks:

1. `cdn.jsdelivr.net` (with a 10-minute cache-buster bucket, to dodge the 12-hour edge cache)
2. `fastly.jsdelivr.net`
3. `gcore.jsdelivr.net`
4. `raw.githubusercontent.com` (no CDN; used to double-check a "no update" answer)

### Scheduled jobs

| Workflow | Schedule (UTC+8) | What it does |
| --- | --- | --- |
| `track-upstream-tavern` | daily 10:00 | compares the official SillyTavern tag with the last built tag, dispatches a build when they differ, republishes `latest.json` when it lags behind, files an issue when this fork falls behind upstream host code |
| `sync-upstream-host` | daily 12:00 | merges upstream host commits into this fork, then pushes (which triggers a rebuild); on conflict it aborts and files an issue |
| `keepalive` | 1st and 16th, 11:00 | commits a heartbeat file so GitHub never disables the scheduled workflows (see below) |
| `publish-fork-meta` | triggered | assembles `latest.json`, commits it, purges and verifies the CDN |

### Manual actions

- **Check upstream and build now**: Actions → `track-upstream-tavern` → Run workflow.
- **Rebuild the APK only**: Actions → `tavern-upstream-apk` → Run workflow (`tavern_tag` = `auto`).
- **`latest.json` did not update**: Actions → `publish-fork-meta` → Run workflow (`release_tag` empty = newest `sillydroid-v*`).
- **Code changes**: push to `master`; changes under `android-tavern/**`, `scripts/**`, `sillydroid-build-config.json`
  or `.github/workflows/sillydroid-upstream-apk.yml` trigger a build automatically.

## Data safety (data / extensions are never overwritten)

User data and the extracted Tavern payload live in **separate directories**:

| Content | Path | On upgrade |
| --- | --- | --- |
| Characters, chats, lorebooks, settings (`data/`) | `files/android-tavern/data/server/data` | untouched |
| Third-party extensions (`extensions/`) | `files/android-tavern/data/server/extensions` | untouched |
| Server plugins (`plugins/`) | `files/android-tavern/data/server/plugins` | untouched |
| Root `config.yaml` (contains API keys) | `files/android-tavern/bootstrap/server/config.yaml` | skipped while extracting |
| Tavern source / node_modules | `files/android-tavern/bootstrap/server` | re-extracted (overlay) |

SillyTavern runs with `--dataRoot` pointing at the persistent directory, and
`public/scripts/extensions/third-party` plus `plugins` are symlinked back to it. Extraction never follows
those symlinks, and `config.yaml` is skipped, so an update cannot clobber user data.

Two additional guards:

1. **Snapshot before extraction** (`TavernUserDataGuard`): before any server asset refresh, `data/`,
   `extensions/` and `plugins/` are hardlink-snapshotted into
   `files/android-tavern/data/server/backups/<timestamp>/` (last 2 kept). Hardlinks cost no extra space;
   deleting a snapshot only removes the links.
2. **Assertion before extraction** (`HostRuntime.assertUserDataOutsideServerDirectory`): if the data root
   ever ends up inside the extraction target, the update aborts with an explicit error instead of silently
   overwriting data.

The only operations that really lose data are outside "in-place upgrade": uninstalling, clearing app data,
or switching to a build with a different signature/applicationId.

## Syncing upstream host fixes

The APK is built from **this** repository. Fixes made in `jialmaster/SillyDroid` do not appear here by
themselves, so `sync-upstream-host.yml` runs daily:

1. checks whether upstream `master` HEAD is already contained in this `master`, and exits if so;
2. otherwise `git merge upstream/master`;
3. on success it pushes, which triggers a rebuild and a new release;
4. on conflict it runs `git merge --abort` and files an issue listing the conflicted files — **the phone
   keeps running the previous version, `latest.json` is untouched**.

Three files are protected so an automatic merge cannot break the fork:

| File | Protection |
| --- | --- |
| `.github/workflows/sillydroid-site-release-state.yml` | always keeps the fork's no-op version (upstream's version POSTs release state to `sd.jlmaster.online`) |
| `README.md` | always keeps this repository's version |
| `sillydroid-build-config.json` | pins `buildType=debug` and `tavernVersion=latest`; every other field is taken from upstream |

Note: a `merge=ours` gitattribute only applies when both sides changed the file, so the workflow restores
these paths explicitly after merging (see `.gitattributes`).

### When upstream and SillyTavern both update

**One build covers both — there is nothing to choose.**

- The **tavern version** always comes from the newest official `SillyTavern/SillyTavern` release
  (`build.tavernVersion=latest`, resolved at build time by `resolve-tavern-build-plan.sh`). Whatever version
  upstream happened to build is irrelevant.
- The **host code** comes from this repository, which now includes upstream's latest commits.

So "upstream fixed a bug + official released 1.19.1" produces a single build:
`versionName = 1.0.1.xx+tavern.1.19.1`, containing both.

The only caveat: if an upstream host change depends on the behaviour of an older tavern build, pairing it
with the newest tavern may be incompatible. That shows up at build or startup time and gets fixed here.

If both pushes happen on the same day, builds are serialized by a shared concurrency group (no
cancellation); worst case is one extra build, and the later release wins.

## Scheduled workflows and GitHub's 60-day rule

GitHub disables **all** scheduled workflows in a public repository after 60 days without repository
activity. Upstream releases are months apart, so relying on "bot commits when a new version appears" would
eventually trip that rule.

`keepalive.yml` commits `.github/fork-meta/heartbeat.txt` on the 1st and 16th of each month, keeping the
activity gap at 15 days or less. The heartbeat path is outside the APK build trigger paths, so it never
starts a 330 MB build.

## Privacy and security

### Outbound requests

| Purpose | Destination | Data sent |
| --- | --- | --- |
| Update check | `cdn.jsdelivr.net` / `fastly.jsdelivr.net` / `gcore.jsdelivr.net` / `raw.githubusercontent.com` | HTTP headers only (UA, IP) — no chats, no config |
| APK download | GitHub Release asset over HTTPS, SHA256 verified before install | same |
| Extension / plugin install | `github.com` / `gitlab.com` (URLs you provide) | same |
| Chat inference | the API you configure inside SillyTavern | chat content (native SillyTavern behaviour) |
| Crash / log upload | **none** — `SILLYDROID_CRASH_LOG_UPLOAD_URL` is empty in this build and the uploader short-circuits | none |

No analytics, no device fingerprinting, no startup beacon.

### Where data lives

- `files/android-tavern/data/server/{data,extensions,plugins}` — characters, chats, lorebooks, extensions.
- `files/android-tavern/bootstrap/server/config.yaml` — API keys in **plaintext** (SillyTavern's own design).
- `android:allowBackup="false"` — excluded from cloud backup and `adb backup`.
- App-private directory; other apps cannot read it on Android 10+. The system Files app can reach it only
  through the SAF provider, with your explicit grant.

### Real risks, by severity

1. **Third-party extensions / server plugins** — they are full JS/Node programs and can read the data
   directory and reach the network. Only install repositories you trust.
2. **Debug builds are debuggable** — with USB debugging enabled and an untrusted computer attached, `adb`
   and `run-as` can read app-private data. Turn developer options off when done.
3. **"Allow external access" exposes the tavern to your LAN** — enable basicAuth if you need it, otherwise
   leave it off.
4. **The debug keystore in this repo is public** — signatures are not a trust boundary here. Anyone with
   push access could publish a same-signature APK your phone would install over this one; keep the
   collaborator list empty and enable 2FA.
5. **Cleartext traffic** — `usesCleartextTraffic=true` exists to reach LAN LLM servers over `http://`; it
   also means same-network devices could sniff plain HTTP. Use HTTPS for anything that leaves your LAN.
6. **Public build logs** — release signing material is injected from Actions secrets only (debug builds do
   not read it); logs contain variable names, not values.

### Two extra protections in the client

- **Log redaction on export/upload** (`HostLogSecretRedactor`): secrets are masked **while writing the
  exported zip**, so the on-device log stays untouched. Covers `sk-`, `sk-ant-`, `AIza…`, `ghp_…`,
  `Bearer …`, `Authorization:`, `api_key=`, `token=`, `secret=`, `password=`, `?key=`. Limitation: it is a
  heuristic — custom key formats can slip through and image attachments are not scrubbed.
- **Signature check before install** (`AppUpdateApkSignature`): compares the APK's package name and signing
  certificate SHA-256 with the installed app. On mismatch the installer is not launched and an explicit
  message explains why, instead of letting the system show a vague "App not installed". Unreadable
  signatures are allowed through so the system can decide.

## Troubleshooting: no update prompt

1. Settings → About shows `APK version` / `build type` / `payload version`, and any check failure reason.
   The build type must match `buildType` in `latest.json` (this repo only publishes **debug**, package id
   `com.jm.sillydroid.debug`).
2. If it says "already up to date", check what the metadata actually says:

   ```bash
   curl -s "https://cdn.jsdelivr.net/gh/xtzhdhl/SillyDroid@master/.github/fork-meta/latest.json?ts=$(date +%s)"
   ```

   Compare `release.versionName` with the installed version. If it is still old, look at the Actions runs of
   `track-upstream-tavern` / `tavern-upstream-apk` / `publish-fork-meta`.
3. Only versions this repo has **built** appear in `latest.json`. When upstream releases something but no
   build has happened yet, the phone waits for the next daily check plus one build (~6-10 minutes).
4. **jsDelivr caches the branch-to-commit mapping for up to 12 hours.** Right after a release, some
   networks still read the previous `latest.json` ("already up to date"). `purge.jsdelivr.net` clears the
   file cache but not every edge's branch resolution. Workarounds: install the APK from the Release page
   directly (no data loss), or wait for the cache to expire. Builds from `1.0.1.69` onward also re-check
   against `raw.githubusercontent.com`, so this no longer matters for them.

## Repository layout

- `android-tavern/` — Android host (Kotlin), Tavern runtime wrapper, updater.
- `scripts/` — build plan, rootfs / dependency pack / server source / APK packaging.
- `.github/workflows/` — tracking, sync, build, publish, keepalive.
- `.github/fork-meta/latest.json` — metadata the app reads (written by workflows, do not edit by hand).

## License

See `LICENSE` and `THIRD_PARTY_NOTICES.md` for upstream licensing.
