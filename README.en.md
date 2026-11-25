<div align="center">

# HyperMount

English | [简体中文](README.md)

A controller app for **swapping SystemUI builds with automatic rescue** — HyperOS-style interface for Xiaomi devices

![Android](https://img.shields.io/badge/Android-15%2B%20(API%2035)-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-Required-D32F2F)
![License](https://img.shields.io/badge/License-AGPL--3.0-blue)

<br>

Built around one loop: **prepare a rescue build → swap in the build under test → auto-roll-back on crash → keep using your phone**. Swap in a SystemUI build that might crash without fear — if it breaks, the last working build comes back automatically. No more black screens or frozen SystemUI.

</div>

---

## ℹ️ At a glance

| Item | Value |
| --- | --- |
| **Current version** | `1.4.8` |
| **Minimum OS** | Android 15 (API 35) |

---

## ✨ Features

### Rescue flow
- **Rescue build**: pick a SystemUI build you know works and keep it as the fallback — roll back to it when things break
- **Test build**: pick the build you want to try and swap it in to observe
  - Picking a file launches MT Manager directly (authentic version first, coexist version as fallback)
  - If neither is installed you get a clear "MT Manager not found" message — it never falls back to other file pickers
  - A leftover `apk.1` extension from downloaders is restored to `apk` automatically
  - After copying, the package name is verified — anything that is not `com.android.systemui` is rejected
- **Start / Stop**: one tap to take over, stop any time

### Automatic recovery
- **Crash threshold**: how many crash log entries accumulate before rolling back to the rescue build (adjustable)
- **Freeze detection**: runaway log growth and stack overflow count as frozen too — no need to wait for the crash count
  - Such a crash can flood tens of thousands of lines in one go at a very low rate; counting crashes never catches it, this does
- **Restart timeout**: how many seconds to wait for SystemUI to restart after a swap (adjustable)
- **Volume-key rescue**: Volume + / Volume − roll back directly — works even when the screen is untouchable
- **System rescue override**: raises the system's own crash-reboot limit so the system doesn't kill SystemUI before the rescue runs
  - Force-enabled and non-configurable while **Enhanced rescue** is on — otherwise the system hits its limit first and automatic rescue never gets a chance
  - In Logs + rescue mode (without Enhanced rescue) the crash limit is set to **30**, wider than the system default of 12, so the volume keys can still be pressed in time

### Volume keys
- Detects the device's volume keys automatically and actually tests each one for real events (declared ≠ pressable)
- Tested once, remembered forever — no re-testing on every run

### Home (standalone testing)
- Runs its own test flow without the module daemon — works for crash-testing other apps too
- Flow: install the test apk → wait the configured time → capture crash logs only → on crash, reinstall the rescue apk automatically
- **No package check** — the app under test doesn't have to be SystemUI; the only thing blocked is "test apk and rescue apk are the same file"
- **Auto-replace**: turn it off and testing still works, minus the roll-back-to-rescue step after a crash
- The Home page and the System UI page keep separate apks — they don't interfere with each other
- **Config**: opens as a bottom sheet — target package, wait time (0 to 20 s), capture time (5 to 60 s)
- Run logs stream live into the status card, with the rescue count right there
- Logs land where Settings → Log directory points (shown when the run mode includes logs; tap the card to change the path)
- The target package is independent of the test / rescue apk package names — when testing a plugin, crashes count under the host, so fill in anything
- Every slider supports tapping its title to type an exact value
- **Crash logs**: a dedicated crash-record page
- **Debug log**: optional, records every step

### Check for updates
- Checked once in the background at launch; a new version shows an update pill next to the version number on Home, one tap to download
- Settings → About → "Check for updates" re-checks manually; the row greys out while checking so it can't be double-tapped
- An "Auto check" switch on the same card turns off the launch-time check
- **Update channel**: Stable or Internal beta — switch to beta to try new builds, switch back when done
- Results are remembered — losing connection or restarting the app never loses the update notice

### Interface
- Miuix style with dark mode; the top bar is a collapsing large title
- **Three bottom tabs**: System UI / Home / Settings — switched only via the bottom bar; horizontal swipes no longer turn pages, so no accidental jumps
- **Theme**: one card holds theme mode (follow system / light / dark) · Monet dynamic color · seed color (with palette style and color spec)
- **Blur strength**: how much the glass controls blur, adjustable with live effect
- **Predictive back gesture**: previews the previous screen as you swipe; when off, dragging doesn't move the page — it pops on release, clipped to the system corner radius
- **Bottom-bar refraction**: off turns the bar into a frosted capsule without refraction

### About
- Entry at the very bottom of Settings, opens a second-level page
- App version, module version, minimum OS
- **Third-party licenses**: list of open-source libraries and their licenses, on its own second-level page

---

## 🧩 Source & Build

This repository is the **complete source** of every release: no closed components, no packing shells, no code fetched at runtime. Every binary shipped in a release maps to source you can read here.

| Path | Contents |
| --- | --- |
| `app/` | Android client (Kotlin / Compose) |
| `module/main/` | Magisk module (`customize.sh` / `service.sh` / `action.sh`, scripts and default config) |
| `module/src/` | C source of the module daemons and CLI (`actiond.c` / `watchd.c` / `ctl.c`) |
| `module/bin/build-bin.sh` | Cross-compiles `module/src` into the arm64 executables in `module/main/bin/` |

The binaries in `module/main/bin/` are committed so machines without an NDK can still produce a complete module package (the daemon has no shell fallback — dropping it drops the feature). They are built from the same-named `.c` files in `module/src/`; rebuild them yourself and compare byte for byte.

> Release packages contain build artifacts only, never source. The source of truth is this repository.

---

## ⚠️ Disclaimer

This tool exists to recover a SystemUI you broke yourself. It swaps system components — use at your own risk: **always prepare a working rescue build before swapping in a test build**

---

## 📄 License

GNU Affero General Public License v3.0

The glass and background effects are ported from [Sonify](https://github.com/XiaoZhi0729/Sonify), whose liquid-glass files come from
[NexioSchedule](https://github.com/HaoZai000/NexioSchedule) (AGPL-3.0). Third-party notices are listed in the in-app
"Third-party licenses" page; each dependency keeps its own license.
