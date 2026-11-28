<div align="center">

# HyperMount

English | [简体中文](README.md)

A controller app for **swapping SystemUI builds with automatic rescue** — HyperOS-style interface for Xiaomi devices

![Android](https://img.shields.io/badge/Android-15%2B%20(API%2035)-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-Required-D32F2F)
![License](https://img.shields.io/badge/License-AGPL--3.0-blue)

<br>

Built around one loop: **prepare a rescue build → swap in the build under test → auto-roll-back on crash → keep using your phone**. Swap in a SystemUI build that might crash without fear — if it breaks, the last working build comes back automatically. No more black screens or frozen SystemUI.

The same install → watch-for-crash → roll-back loop also lives on the Component page, so any apk can be tested this way.

</div>

---

## ℹ️ At a glance

| Item | Value |
| --- | --- |
| **App version** | `1.5.0-hotfix1` |
| **Module version** | `1.6.0-hotfix1` |
| **Minimum OS** | Android 15 (API 35) |
| **Bottom tabs** | System UI / Component / Settings |

---

## ✨ Features

### System UI (first tab)

- **Status card**: guarding / idle, PID, total rescues, live run output
- **Rescue build**: pick a SystemUI build you know works and keep it as the fallback
- **Test build**: pick the build you want to try and swap it in to observe
  - Picking a file launches MT Manager directly (authentic version first, coexist version as fallback)
  - If neither is installed you get a clear "MT Manager not found" message — it never falls back to other file pickers
  - A leftover `apk.1` extension from downloaders is restored to `apk` automatically
  - After copying, the package name is verified — anything that is not `com.android.systemui` is rejected
- **Interface snapshots**: capture the build installed right now; rename, use as rescue, export or undo
  - Undo deletes the snapshot you just took, with no second confirmation
- **Start / Stop**: one tap to take over, stop any time
  - If the volume keys were never verified you are stopped first — rescue depends on them
- **Device**: OS build and model

### Config (gear icon on the System UI page)

- **Run mode**: fully disabled / logs only / logs + rescue
- **Log at boot**: start writing logs as soon as the phone boots
- **Start rescue at boot**: begins guarding once boot settles, with no time limit
- **Always-on rescue**: keeps guarding even when the app is closed
  - Duration is continuously draggable from 1 h to 6 h, snapping at 2 / 4 / 6 h, with "unlimited" at the far right
  - Trigger rule is yours to set: how many SystemUI restarts inside how many seconds enter the daemon (5–60 s / 2–5 restarts)
  - A persistent notification keeps the daemon alive through backgrounding and screen-off
- **Rescue logic**
  - **Volume key check**: a dedicated page tests each key for real events (declared ≠ pressable)
  - **Enhanced rescue**: swaps SystemUI back once crashes hit the threshold, no volume keys needed (threshold 1–10 entries)
  - **System rescue override**: raises the system's own crash-reboot limit so it doesn't kill SystemUI before the rescue runs
    - Force-enabled and non-configurable while Enhanced rescue is on
    - In Logs + rescue mode the limit is set to **30**, wider than the system default of 12, so the volume keys can still be pressed in time

### Component (second tab)

- Runs its own install → watch-for-crash → roll-back flow without the module daemon — works for crash-testing other apps too
- **Status card**: testing / idle, last result and when it ran, whether auto-recover is on, total roll-backs, live run output
- **Auto-recover**: rolls back on crash; turn it off and it still captures logs
- **Rescue component**: pick one from the component snapshots, or leave it empty to roll back to the system build
- **Test component**: the apk you want to install and watch
- **Component snapshots**: capture, rename, use as rescue, export or undo
- **Roll back component**: skips crash detection entirely — restores the preinstalled build immediately, SystemUI restarts once
- **Start test**: installs the test component and captures the crash buffer for the configured time
- **Config** (gear icon): capture time and log size limit
  - The watched package is fixed to the SystemUI process — components run inside it, so another package name would capture nothing
- **No package check** — the only thing blocked is "test and rescue are the same file"
- Fully independent from the System UI page: each keeps its own apks, and a running test never disturbs the other side
- Every slider supports tapping its title to type an exact value

### Check for updates

- Checked once in the background at launch; a new version shows an update pill next to the version number, one tap to download
- Settings → About card → "Check for updates" re-checks manually; the row greys out while checking so it can't be double-tapped
- An "Auto check" switch on the same card turns off the launch-time check
- **Update channel**: Stable or Internal beta — switch to beta to try new builds, switch back when done
- Results are remembered — losing connection or restarting the app never loses the update notice

### Interface & theme (top-right of Settings)

- Miuix style with dark mode; the top bar is a collapsing large title
- **Three bottom tabs**: System UI / Component / Settings — switched only via the bottom bar; horizontal swipes no longer turn pages, so no accidental jumps
- **Theme mode**: follow system / light / dark
- **Monet color**: picks the primary color from the wallpaper
- **Seed color**: choose your own primary color, with palette style and color spec
- **iOS bottom bar**: swaps in a floating glass capsule bar
- **Bottom-bar refraction**: only meaningful for the iOS bar; off turns it into a frosted capsule without refraction
- **Blur strength**: glass blur from 0% to 200%, applied live, with a one-tap reset
- **Predictive back gesture**: previews the previous screen as you swipe; when off, dragging doesn't move the page — it pops on release, clipped to the system corner radius

### Settings

- **Language**: follow system / 简体中文 / English — module-side prompts and the action console follow it too
- **Log directory**: tap for the system folder picker, long-press to type a path
  - When the picker can't resolve a real path you're told to type it instead of failing silently
- **Cache size**: limit draggable from 100 MB to 1 GB with no steps, "unlimited" at the far right; select and delete everything in one go
- **MCP service**: expose the app to AI clients, off by default
  - Enable switch, LAN access, access password, endpoint, access token, custom port and custom token
- **About card**: About / Check for updates / Auto check / Update channel
- **Developer options**: its own card at the very bottom of the page, kept in release builds too
  - App log level, app log content (the content row collapses when logging is off)
  - Open OOBE: replay the onboarding without clearing app data
- Cold start returns to the tab you were last on

### Onboarding (OOBE)

- Six steps: Welcome / Permissions / Terms / Basic settings / Volume keys / Done
- The permissions page reports each item live: notifications / storage / ROOT / rescue module / autostart — tap the one that's missing
  - Autostart and storage can only be granted in system settings; status is re-checked when you come back
- Basic settings edits run mode, log directory and language right there
- Volume keys are tested on the spot — you can't continue if they don't register
- Onboarding only replays when its content actually changed; ordinary releases never drag existing users back

### About

- Entry on the Settings → About card, opens a second-level page
- Developer and code assistance, app version, module version
- **Donate**: right below the module version
- **Update cards**: the app card downloads and installs in place; the module card only downloads the zip
- Website / GitHub repo / AGPL v3.0 / Third-party licenses
- **Porting notes**: the projects the glass and glow effects reference, listed on their own page and not counted as dependencies

---

## 🧩 Source & Build

This repository is the **complete source** of every release: no closed components, no packing shells, no code fetched at runtime. Every binary shipped in a release maps to source you can read here.

| Path | Contents |
| --- | --- |
| `app/` | Android client (Kotlin / Compose) |
| `module/main/` | Magisk module (`customize.sh` / `service.sh` / `action.sh`, scripts and default config) |
| `module/main/language/` | Module-side Chinese and English strings (install prompts, action console, `module.prop` description) |
| `module/src/` | C source of the module daemons and CLI (`actiond.c` / `watchd.c` / `ctl.c`) |
| `module/build-bin.sh` | Cross-compiles `module/src` into the arm64 executables in `module/main/bin/` |

The binaries in `module/main/bin/` are committed so machines without an NDK can still produce a complete module package (the daemon has no shell fallback — dropping it drops the feature). They are built from the same-named `.c` files in `module/src/`; rebuild them yourself and compare byte for byte.

> Release packages contain build artifacts only, never source. The source of truth is this repository.

---

## ⚠️ Disclaimer

This tool exists to recover a SystemUI you broke yourself. It swaps system components — use at your own risk: **always prepare a working rescue build before swapping in a test build**

---

## 📄 License

GNU Affero General Public License v3.0

The glass and background effects are ported from [Sonify](https://github.com/XiaoZhi0729/Sonify), whose liquid-glass files come from
[NexioSchedule](https://github.com/HaoZai000/NexioSchedule) (AGPL-3.0); the onboarding glow shader is taken verbatim from
[HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) (AGPL-3.0). Third-party notices are listed in the in-app
"Third-party licenses" page; each dependency keeps its own license.
