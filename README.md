<div align="center">

# fpsunlock

**120/144/165 FPS unlock for PUBG Mobile — via device identity, nothing else.**

An LSPosed module that makes the game's own native device table report a flagship
profile, which is the entire mechanism the frame-rate gate is built on.

<br>

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![API](https://img.shields.io/badge/API-26%2B-blue?style=flat-square)
![LSPosed](https://img.shields.io/badge/LSPosed-93%2B-6C4AB6?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)
![Build](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)

</div>

---

## What it does

PUBG Mobile decides which frame-rate options to show by calling a **native** method
that resolves the device from `android.os.Build`. There is no whitelist in the Lua
layer to patch — the decision happens below it, in C++, from device identity.

fpsunlock overwrites those `Build` fields inside the game's process before the engine
initialises. The native table then returns a flagship's capability, and the game
offers the higher frame rates on its own.

```
Build.MODEL      → SM-S928B
Build.MANUFACTURER → samsung
Build.SOC_MODEL  → SM8650
        │
        ▼
gameInstance:GetDeviceMaxFPSByDeviceLevel(quality)  →  120 / 144 / 165
        │
        ▼
ExecuteCMD("t.MaxFPS", "...")   ← the actual limiter
```

**No memory patching. No game-file edits. No partition writes.** Every change is a
field overwrite in RAM, scoped to the game's own process, gone when it exits.

---

## Verified behaviour

Tested on a live device with the module active:

| Graphics preset | Frame rate | Result |
|---|---|---|
| Smooth | **120** | stable |
| HDR | **90** | stable |
| Ultra HD | **60** | stable |
| Smooth + Ultra Extreme | **120** | fine-tune slider unlocked, 144/165 tiers selectable |

The fine-tune slider and the 144/165 step ladders appear once `Ultra Extreme` is
selected with `Smooth` — that is the game's own logic (`GSC_FPSFT.lua`), not something
the module fakes. The module only makes the native table return a high enough value
for those UI paths to activate.

> **Frame rate is capped by your panel.** Rendering 165 on a 120 Hz display produces
> 165 frames that get composited to 120. The option appears; the extra frames do not.

---

## Install

**Requirements:** rooted device with **LSPosed** (Zygisk or Riru). The module cannot
load without root — there is no non-root path.

```bash
# 1. Install
adb install -r app-release.apk

# 2. Enable
#    LSPosed Manager → Modules → fpsunlock → enable
#    Scope is pre-populated; keep the game you actually play.

# 3. Restart the game
adb shell am force-stop com.tencent.ig
```

The spoof is applied **at process start**. After any config change, force-stop the
game — a running instance keeps the identity it launched with.

---

## Configuration

Three-tier resolution, first hit wins, winner always logged:

| Priority | Source | Use |
|---|---|---|
| 1 | `/data/local/tmp/fpsunlock.json` | live override, no rebuild |
| 2 | `assets/config.json` in the APK | shipped defaults |
| 3 | compiled-in | corruption-proof fallback |

```bash
adb push my-config.json /data/local/tmp/fpsunlock.json
adb shell am force-stop com.tencent.ig
```

### Profiles

| Profile | Model | SoC | Screen | Notes |
|---|---|---|---|---|
| `S24U` ⭐ | `SM-S928B` | SD 8 Gen 3 | 1440×3120 | default; phone-native |
| `ROG8` | `ASUS_AI2401` | SD 8 Gen 3 | 1080×2400 | 165 Hz capable |
| `REDMAGIC9P` | `NX769J` | SD 8 Gen 3 | 1116×2480 | 165 Hz capable |
| `SM_X910` | `SM-X910` | SD 8 Gen 2 | 1848×2960 | Tab S9 Ultra — **tablet SKU**, expect tablet layout |

```json
{ "active": "ROG8" }
```

### Tiers

Progressive identity surface. `L2` ships by default.

| Tier | Fields | Why |
|---|---|---|
| `L1` | `MANUFACTURER` `BRAND` `MODEL` `DEVICE` `PRODUCT` | The unlock. Smallest possible diff. |
| `L1.5` | + screen geometry | The game ships `PhysicalScreenWidth/Height` to the server on match join; a 1080p panel claiming 1440p is the cheapest contradiction to remove. |
| `L2` ⭐ | + `SOC_MODEL` `SOC_MANUFACTURER` | Matches the 4.4+ chipset rule directly. |
| `L3` | + `HARDWARE` `BOARD` `FINGERPRINT` | Nothing in the script layer reads `FINGERPRINT`. Native consistency only. Off by default. |

**Never touched at any tier:** `VERSION.*`, `SDK_INT`, `SERIAL`, IMEI/ANDROID_ID, GPU
strings, reported RAM, `/proc/*`. Spoofing RAM or GPU makes the engine pick render
paths the real hardware cannot honour — that is a stutter generator, not an unlock.

---

## Build

```bash
git clone https://github.com/burakdmrbkr/fpsunlock.git
cd fpsunlock

./gradlew :app:assembleDebug        # Java only — no NDK required
./gradlew :app:assembleRelease      # R8 minified
```

Native prop layer is opt-in and needs the NDK:

```bash
git submodule add https://github.com/bytedance/bytehook \
  app/src/main/cpp/third_party/bytehook
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"
./gradlew :app:assembleDebug -Pnative=true
```

---

## Verify

```powershell
.\scripts\deploy.ps1 -Logs     # build → install → force-stop targets → tail
.\scripts\verify.ps1           # summarise what actually applied
```

Read the game's own log lines — they are the ground truth, not the menu:

| Line | Meaning |
|---|---|
| `applying ... model=SM-S928B` | identity spoof armed |
| `InitRealSupportFPS ... FPS:120, maxLevel:8` | native table accepted it |
| `SetFPS ... StrFPS: 120` | **the win** — `t.MaxFPS` set |
| `clamp to MaxFPSInMatch` | server room limit — expected, not a bug |
| `limit to 90 by bluehole120` | BLUEHOLE region — identity cannot fix it |

Probe mode (`"probe": true`) logs what the game reads without changing anything.

---

## How it was worked out

The gate was mapped by reading a 4.7.0 client Lua dump — 27,557 files — rather than
trusting blog posts. Findings that shaped the design:

- **No model whitelist exists in Lua.** The only device list in the tree
  (`SuperFrameScaleFactorDownConfig.WhiteListDeviceModel`) is 20 budget devices on a
  *50 fps downscale* path. Spoofing into it would lower your cap.
- **The ladder tops out at level 8 = 120.** `Level2FpsString` has a `8 → "120"` branch
  and nothing above it. 144/165 are reachable only through the fine-tune path, which
  requires `FPSFineTuneSwitch` and `Ultra Extreme + Smooth`.
- **`t.MaxFPS` is the limiter.** Making the button read "120" achieves nothing on its
  own; `SetFPS` → `ExecuteCMD("t.MaxFPS", …)` is what matters.
- **`MaxFPSInMatch` is server-authoritative.** In rooms with a `FrameLimit`, the server
  wins. Not fixable client-side, and trying to is cheating rather than unlocking.

Full analysis, file-by-file reference list, and the design rationale live in
[`PLAN.md`](PLAN.md).

---

## Project layout

```
app/src/main/java/dev/nextrounlock/fpsunlock/
  HookEntry.java       entry point, per-package dispatch, self-test
  Config.java          3-tier config resolution, no network
  Profile.java         tiered identity model + validation
  BuildSpoofer.java    the unlock
  DisplaySpoofer.java  refresh rate (6 hooks) + screen geometry
  Probe.java           observe-only diagnostics
  NativeLoader.java    optional .so extraction/load
  XLog.java            dual-sink, rate-limited logging
app/src/main/cpp/
  propshield.cpp       bytehook PLT hooks, allowlist-only
scripts/
  deploy.ps1  verify.ps1
```

---

## Risks

Device spoofing violates PUBG Mobile's ToS and can get an account banned. The
anti-cheat (`libanogs.so`) reads `ro.product.*` through `__system_property_get` while
the game UI reads `Build` fields — at L1/L2 those two views differ. That asymmetry is
inherent to this entire class of module, including the Magisk and Zygisk ones. The
optional native layer closes the gap at the cost of a resident hook surface.

Use on hardware you own. Don't use it in ranked play against people who haven't opted in.

---

<div align="center">
<sub>MIT licensed. Built for one device, documented for anyone.</sub>
</div>
