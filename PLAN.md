# PUBG Mobile 120 FPS Unlock — LSPosed Module Build Plan

Working name: **`fpsunlock`** (package `dev.nextrounlock.fpsunlock`)
Type: Java-only LSPosed/Xposed module (phase 3 adds one small `.so`)
Goal: make PUBG Mobile's graphics menu expose **Ultra Extreme (120 FPS)** with the smallest possible identity diff.

---

## 1. Scope definition — what "just enough" means

"Just enough" is an explicit constraint, not a vibe. The module has three escalating spoof tiers and ships with the lowest one active.

| Tier | Fields touched | Rationale |
|---|---|---|
| **L1 (default)** | `Build.MANUFACTURER`, `BRAND`, `MODEL`, `DEVICE`, `PRODUCT` | Verified as the set that feeds `GetDeviceMaxFPSByDeviceLevel`. Changes the *name* of the device, nothing about its capability. |
| **L1.5** | L1 + display size/density spoof | `PhysicalScreenWidth/Height` ship to the server on match join (`ingame.lua:598`). An S24 Ultra is 1440×3120; a 1080p phone reporting 1080×2400 while claiming `SM-S928B` is the cheapest contradiction to remove. |
| **L2** | L1.5 + `Build.SOC_MODEL`, `Build.SOC_MANUFACTURER` (API 31+) | Matches `Client.GetLowerSOCModelAboveAndroid12`. On by default per decision. |
| **L3 (opt-in, off by default)** | L2 + `HARDWARE`, `BOARD`, `FINGERPRINT` | Nothing in the Lua reads `FINGERPRINT`; this is purely native/ACE consistency. Highest surface, lowest proven value. |

Never touched, at any tier: `Build.VERSION.*`, `SDK_INT`, `SERIAL`, IMEI/ANDROID_ID, `ro.debuggable`/`ro.secure`, GPU renderer strings, `/proc/cpuinfo`, thermal or clock state. Anything in that list either breaks the process, trips integrity checks unrelated to FPS, or is a lie the engine will act on and degrade performance for.

Consequence to accept up front: **L1 alone is not self-consistent.** A Xiaomi fingerprint with a Samsung model is detectable by anything that cross-checks. That's the deliberate trade — it is what the ecosystem ships, and it is what the FPS gate actually reads.

---

## 2. Research summary (what we're building on)

### 2.1 Existing modules

| Repo | Mechanism | What it gets right | What it's missing |
|---|---|---|---|
| `rushiranpise/Game-Unlocker` (163★) | `IXposedHookLoadPackage` → `XposedHelpers`-style reflection into `Build` statics; per-package model table | Minimal, 1 class, MIT | No refresh-rate spoof, no config, single field set, one model per game (`IN2020`, `LE2123`…, all stale 2021-era) |
| `rifsxd/process-hook` (121★) | Same + `deviceInfo` profile struct + `Display.getRefreshRate` hook; profiles pulled from a GitHub JSON at class-init | Clean profile abstraction, refresh-rate hook | Network fetch in a hooked process (bad: fails offline, adds a detectable syscall), only `getRefreshRate`, no `getSupportedModes` |
| `popeye0013/FPSLite` | Magisk `system.prop` (`ro.product.model=CPH2653`) | Zero code | System-wide, needs reboot, breaks every other app's identity, visible to `resetprop` scanners |
| `JUANIMAN/fpsUnlocker`, `arpiitjaiswal/Max-FPS-Unlocker` | Magisk `system.prop` (iQOO 12 Pro / 13) | Trivial | Same as above |
| `AlirezaParsi/COPG`, `yadavnikhil03/GameUnlocker` | Zygisk + PLT hook on `__system_property_get` + `GLES20.glGetString` | Only approach that fools native-side prop reads | Root-module install, no LSPosed path, heavy |

**Decision:** fork the *shape* of `process-hook` (profile struct + per-package dispatch) without its network fetch, and take `Game-Unlocker`'s minimal field discipline. Add the missing refresh-rate surface. Keep the native prop hook as an optional phase-3 drop-in, since that's the one real gap in every Xposed-only module.

### 2.2 How the 120 FPS gate actually works

**Verified against the 4.7.0 client Lua (27,557 files), not inferred.** This replaces the earlier second-hand account.

The FPS ladder is `{15,20,25,30,40,60,90,120}` → levels `1..8` (`GSC_FPS.lua:6-15`). There is **no model whitelist table in Lua**. The gate is not a list of approved phones anywhere in the script layer. What exists is a native chain:

```
GSC_FPS:InitRealSupportFPS()            -- renders the 8 buttons
  └─ gameInstance:GetDeviceMaxFPSByDeviceLevel(quality)   ← NATIVE (C++)
  └─ gameInstance:GetExpandDeviceMaxFPSByDeviceLevel(q)   ← NATIVE
GSC_FPS:GetMaxFPSLevel()                -- decides which buttons survive
  └─ same two natives
  └─ min(., 90) if CustomTab ~= Battle
  └─ min(., 90) if BLUEHOLE region and cvar t.BLUEHOLE120 == 0
  └─ MainCity clamp to 60/40/30 by quality
logic_setting_graphics.SetFPS()         -- applies the choice
  └─ ExecuteCMD("t.MaxFPS", StrFPS)     ← the actual engine limiter
  └─ clamp to uGameState.MaxFPSInMatch  ← SERVER-SET, per-room
```

Consequences, in order of importance:

1. **`GetDeviceMaxFPSByDeviceLevel` is native.** No Lua edit reaches it. Everything the script layer knows about "can this device do 120" originates in C++ from device identity. This is exactly why `Build` field spoofing works and exactly why nothing else in Lua needs touching.
2. **`t.MaxFPS` is the real limiter**, not the menu. Setting the menu to level 8 only matters because `SetFPS` writes `t.MaxFPS=120`. If a module only makes the menu render "120" without this path running, nothing changes — the game still runs at the previous `t.MaxFPS`.
3. **`GetDeviceMaxSupportLevel` is a separate native** (quality ceiling, not FPS). A device can pass the FPS gate and still cap quality.
4. **`Client.GetTCDeviceLevel()` is the device tier** the UI keys on (`util.IsHighTCDevice` = level ≥2 and TC ≥8). Also native.
5. **`SuperFrameScaleFactorDownConfig.WhiteListDeviceModel`** is the *only* model list in the whole script tree — 20 budget devices (TECNO/Infinix/Realme/itel) for a 50 fps downscale path, gated on `TCDeviceLevel == 5 or 6`. **Unrelated to 120 fps.** Don't mistake it for the whitelist; spoofing into it would *lower* the cap.
6. **Region clamp is real**: `t.BLUEHOLE120` cvar + `PublishRegionMacros.IsBlueholeOperationRegion()`. In BLUEHOLE operation regions, 120 is hard-clamped to 90 in **two independent places** (`GSC_FPS:GetMaxFPSLevel` and `GraphicHelperUtil.GetMaxFPSForQuality`). For a GLOBAL client this branch is dead code. If you're on a BLUEHOLE/BGMI-lite build, the module cannot fix it by identity — the cvar is the gate.
7. **`MaxFPSInMatch` is server-authoritative.** `GameStateBase:OnRep_MaxFPSInMatch` does `ExecuteCMD("t.MaxFPS", MaxFPSInMatch)` and `SetFPS` clamps down to it. In a match with `battle_custom_cfg.FrameLimit` set, the server wins. Nothing client-side beats this, and trying to is unambiguously cheating rather than unlocking.
8. **Fingerprint surface is much larger than `Build.MODEL`.** `data_device_os.lua:57` assembles ~50 fields — `DeviceModel`, `DeviceMake`, `DeviceName`, `GPUFamily`, `GLRender`, `CpuHardware`, `Memory`, `DeviceRamMB`, `ExactDeviceLevel`, `DeviceGradeLevel`, `PhysicalScreenWidth/Height` — and `ingame.lua:598-599` ships that struct to the server on match join (`MatchHandler.send_on_match_req(501, 0, {10080}, DeviceOSInfo.InfoList)`). So the *server* sees a coherent device profile regardless of what the settings menu showed.
9. **ACE** (`libanogs.so`) separately reads `ro.product.*` via `__system_property_get`. Combined with #8: the menu reads `Build` fields, the server gets `Build`-derived identity *plus* hardware facts, and ACE gets props. Three views. Identity spoofing aligns the first two partially and never the third without phase 3.

**Net design impact:** the earlier plan's premise — "spoof `Build` fields, menu unlocks, done" — is correct but incomplete. `Build.MODEL` is necessary (it's what feeds the native table) and `Build.MANUFACTURER`/`BRAND` are needed for `GetDeviceMake`. But `L1` alone leaves `GPUFamily`, `CpuHardware`, `Memory`, screen size and `TCDeviceLevel` honest, so #8's payload will show a 1080p phone claiming to be an S24 Ultra. That is the inconsistency ACE scores, and it was invisible from the outside.

### 2.2.1 What this means for the tier ladder

Revised, informed by the above:

| Tier | Fields | Verdict from Lua |
|---|---|---|
| **L1** | `MANUFACTURER`, `BRAND`, `MODEL`, `DEVICE`, `PRODUCT` | **Necessary and sufficient for the menu.** `GetDeviceMaxFPSByDeviceLevel` keys off these. |
| **L2** | + `SOC_MODEL`, `SOC_MANUFACTURER` | **Keep**, contradicts nothing in Lua, and matches what `Client.GetLowerSOCModelAboveAndroid12` would read. |
| **L3** | + `HARDWARE`, `BOARD`, `FINGERPRINT` | Lower value than assumed: nothing in the script layer reads `FINGERPRINT`. It's only for native/ACE consistency. |
| **New: L1.5** | + `Display` size/density spoof | **Higher value than L3.** `PhysicalScreenWidth/Height` are in the server payload and an S24 Ultra is 1440×3120. A 1080p device reporting 1080×2400 while claiming `SM-S928B` is a one-line contradiction. |

### 2.3 Candidate target profiles

Verified model strings for devices on the 120 FPS list. `device`/`product` are the codenames; only needed at L3.

| Profile | MODEL | MANUFACTURER / BRAND | device / product | Notes |
|---|---|---|---|---|
| **`S24U`** (default) | `SM-S928B` | `samsung` / `samsung` | `e3q` / `e3qxxx` | SD 8 Gen 3. Phone-native, no tablet side effects. `WhiteListDeviceModel` does **not** contain it, which is correct — that list is low-end. |
| `SM_X910` | `SM-X910` | `samsung` / `samsung` | `gts9uwifi` / `gts9uwifixx` | Galaxy Tab S9 Ultra, SD 8 Gen 2. Ships but not default. **Tablet SKU** → tablet layout, different sensitivity curve, and `PhysicalScreenWidth` ~1848 which will not match any phone. |
| `ONEPLUS12` | `CPH2581` | `OnePlus` / `OnePlus` | `waffle` / `CPH2581` | SD 8 Gen 3. |
| `REDMAGIC9P` | `NX769J` | `nubia` / `nubia` | `NX769J` / `NX769J` | Gaming phone. |
| `ROG8` | `ASUS_AI2401` | `asus` / `asus` | `AI2401` / `WW_AI2401` | — |
| `IQOO12P` | `V2324A` | `vivo` / `iQOO` | `V2324A` / `V2324A` | Verify codename against a device DB before use. |

Default is **`S24U`** per the decision below. `SM_X910` ships as a JSON swap for the tablet case.

**Caveat that matters:** a spoofed *model* does not make a slow SoC render 120 fps. `GetDeviceMaxFPSByDeviceLevel` will return 120 and `t.MaxFPS` will be set to 120 — the engine will then try to hit a frame budget the GPU can't meet, and you get 120-cap behaviour at 70 real fps plus heat. Phase 0 gates on the real panel rate and a real frame-time measurement.

---

## 3. Design

### 3.1 Architecture

```
LSPosed (root)
  └─ fpsunlock.apk
       ├─ assets/xposed_init            → dev.nextrounlock.fpsunlock.HookEntry
       ├─ assets/config.json            → target profiles + active selection
       ├─ HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit
       │    ├─ package allowlist check  (only PUBG SKUs + debug apps)
       │    ├─ Profile.resolve(pkg)     → ResolvedProfile
       │    ├─ BuildSpoofer.apply()     → static field overwrite
       │    ├─ DisplaySpoofer.apply()   → refresh-rate surface
       │    ├─ [L2+] SystemPropsSpoofer.apply() → reflection into android.os.SystemProperties
       │    └─ [phase 3] NativeLoader   → dlopen libpropshield.so (PLT hook)
       └─ res/…  no launcher activity; module is headless (v1)
```

No network. No UI in v1. Everything resolved synchronously in `handleLoadPackage`, which runs before the game's `Application.onCreate` and before `System.loadLibrary("UE4")`.

### 3.2 Java identity layer (L1–L3)

```java
// Build.class static fields, via reflection. Field IDs must be resolved AFTER
// the class is initialized (Build.<clinit> runs on first touch, so touch it first).
Class<?> build = android.os.Build.class;   // forces <clinit>
for (Map.Entry<String,String> e : profile.buildFields().entrySet()) {
    Field f = build.getDeclaredField(e.getKey());
    f.setAccessible(true);
    f.set(null, e.getValue());            // static final String — works on ART
}
```

Ordering constraints discovered from the reference modules:

1. Touch `android.os.Build` before writing — `Build.<clinit>` reads `SystemProperties` and will overwrite anything set first.
2. Set `BRAND`/`MANUFACTURER` before `FINGERPRINT` if L3 is on, so a crafted fingerprint composes correctly.
3. Every field write is wrapped individually; a missing `SOC_MODEL` on pre-API-31 must not abort the rest.
4. Keep `XposedBridge.log` lines prefixed `[fpsunlock]` for `logcat` grepping, and emit the resolved profile once per process (`pid=`).

**Known limitation, documented in-code:** static field overwrite changes the field value, not the compiled constant. Any code path that inlined the constant at *its* build time will still see the original. In practice the game is compiled against the public SDK stub (non-constant) and reads the field, so this is fine — but the log line exists so we can prove it on-device.

### 3.3 Display / refresh-rate layer

Full surface, not just `getRefreshRate` (this is the main upgrade over the reference modules):

| Hook | Signature | Action |
|---|---|---|
| `android.view.Display#getRefreshRate` | `float` | return `targetHz` |
| `android.view.Display#getMode` | `Display.Mode` | return a mode whose `refreshRate == targetHz` |
| `android.view.Display#getSupportedModes` | `Display.Mode[]` | return array where the highest mode is `targetHz` |
| `android.view.Display#getSupportedRefreshRates` | `float[]` (hidden) | return `{targetHz}`, only hooked if `XposedHelpers.findMethodExact` finds it |
| `android.view.Display#getDisplayInfo` | `DisplayInfo` (hidden) | mutate `refreshRate` + `supportedModes` fields in place |
| `android.view.WindowManagerImpl#addView` (optional, "force" flag) | — | set `params.preferredRefreshRate = targetHz` before `super` |

`Display.Mode` construction: prefer `Display.Mode(int,int,int,float)`, which is `@hide` — reach it with `getDeclaredConstructor`, and on failure fall back to mutating `mRefreshRate` on the object returned by `super.getMode()`. Both paths get a `try/catch` and a one-time log so a ROM that blocks one still works via the other.

`DisplayInfo` mutation is reflection on `refreshRate` (float) and `supportedModes` (`Display.Mode[]`); guarded because `DisplayInfo` field names have been stable since API 17 but the class is not API surface.

**Do not** hook `Surface.setFrameRate` or the frame-rate-override APIs. That changes compositor scheduling, not the game's limiter, and is the fastest way to get a black screen.

### 3.4 Phase 3 — native prop layer

Built in v1, **disabled by default** per decision. Value is narrower than originally assumed: the Lua never reads `FINGERPRINT`, so this layer exists only to make ACE's view (props) match the game's view (`Build` fields), not to unlock anything. If ACE-side consistency isn't a goal, it can stay off forever without affecting the feature.

- `app/src/main/cpp/propshield.cpp`, built with CMake, linked against `libbytehook` (bytehook/AAPM, MIT).
- Hook `__system_property_get` and `__system_property_read_callback`, filter an allowlist of exact keys: `ro.product.model`, `ro.product.manufacturer`, `ro.product.brand`, `ro.product.device`, `ro.product.name`, `ro.build.fingerprint` (L3 only), `ro.soc.model`/`ro.soc.manufacturer` (L2 only).
- Load path: `initZygote(StartupParam)` in `IXposedHookZygoteInit` gives `param.modulePath`. Extract `lib/arm64-v8a/libpropshield.so` from the APK into a `0700` dir under the target's own cache at first fork, then `System.load(path)` from `handleLoadPackage`. Never `System.loadLibrary` by bare name (won't resolve from the module APK in a foreign process).
- Same mechanism `yadavnikhil03/GameUnlocker` and COPG use; orthogonal to the Java layer, toggled in config.

### 3.4.1 Lua provenance

Findings in §2.2 come from a 4.7.0 client Lua dump. Every file carries a `-- By @XThrlen | Channel @SRC_HUB` header — this is a **repacked/modded distribution**, not a clean Play Store extraction. Two consequences:

- The logic observed is very likely stock (headers are added above unmodified bodies, and the code is internally consistent with 4.5/4.6/4.7 version dirs present), but it is not cryptographic proof of the shipping binary's contents.
- The dump should be treated as a *map of the gate*, not as a target for edits. Patching game files is a much larger anti-cheat surface than identity spoofing and is out of scope by the "just enough" constraint.

**Reference file list** (paths relative to `ShadowTrackerExtra/Content/Lua/`), for re-verification after each game update:

| File | What it establishes |
|---|---|
| `client/slua/umg/NewSetting/GraphicsNew/Comps/GSC_FPS.lua` | The 8-level FPS ladder, `InitRealSupportFPS`, `GetMaxFPSLevel`, the `t.BLUEHOLE120` clamp |
| `client/slua/umg/NewSetting/GraphicsNew/GraphicHelperUtil.lua` | `GetMaxFPSForQuality`, `FPSNum2Level`, second BLUEHOLE clamp, region helpers |
| `client/slua/logic/setting/logic_setting_graphics.lua` | `SetFPS` → `ExecuteCMD("t.MaxFPS", …)`, `MaxFPSInMatch` clamp, `LockFPSMod4High` |
| `client/logic/data/data_device_os.lua` | The ~50-field identity payload sent server-side |
| `client/ingame/ingame.lua:598` | `send_on_match_req(501, 0, {10080}, DeviceOSInfo.InfoList)` — the exfil point |
| `client/logic/client_evo_config/SuperFrameScaleFactorDownConfig.lua` | The only model list in Lua, and it is the low-end 50 fps path — **not** the 120 gate |
| `GameLua/GameCore/Framework/GameStateBase.lua` | `OnRep_MaxFPSInMatch` server-authoritative clamp |
| `client/slua/config/ClientMacros/PublishRegionMacros.lua` | BLUEHOLE vs GLOBAL region resolution |

### 3.5 Config

`assets/config.json`, read once per process, parsed with `org.json` (already in the framework — zero deps):

```json
{
  "version": 1,
  "active": "S24U",
  "tier": "L2",
  "refresh": { "enabled": true, "hz": 120.0, "forceWindow": false },
  "native": { "enabled": false },
  "packages": {
    "com.tencent.ig":            "S24U",
    "com.pubg.imobile":          "S24U",
    "com.pubg.krmobile":         "S24U",
    "com.rekoo.pubgm":           "S24U",
    "com.vng.pubgmobile":        "S24U",
    "com.tencent.tmgp.pubgmhd":  "S24U",
    "com.pubg.newstate":         "S24U",
    "com.aida64":                "S24U",
    "ru.andr7e.deviceinfohw":    "S24U"
  },
  "profiles": {
    "S24U": {
      "manufacturer": "samsung", "brand": "samsung", "model": "SM-S928B",
      "device": "e3q", "product": "e3qxxx",
      "soc_model": "SM8650", "soc_manufacturer": "QTI",
      "hardware": "qcom", "board": "pineapple",
      "screen": { "width": 1440, "height": 3120, "density": 450 },
      "fingerprint": "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXBA:user/release-keys"
    },
    "SM_X910": {
      "manufacturer": "samsung", "brand": "samsung", "model": "SM-X910",
      "device": "gts9uwifi", "product": "gts9uwifixx",
      "soc_model": "SM8550", "soc_manufacturer": "QTI",
      "hardware": "qcom", "board": "kalama",
      "screen": { "width": 1848, "height": 2960, "density": 320 }
    }
  }
}
```

Resolution order, logged explicitly at startup so there's never ambiguity about what ran:
1. `/data/local/tmp/pubg_unlock.json` (adb-pushed override, best-effort — SELinux may deny on some ROMs; log the `EACCES` and fall through)
2. `assets/config.json` inside the module APK, opened as a zip from `modulePath`
3. compiled-in `DEFAULT_PROFILE`

`null` package value = explicitly excluded (useful for A/B against the debug app).

### 3.6 Package allowlist

`com.tencent.ig`, `com.pubg.imobile` (BGMI), `com.pubg.krmobile`, `com.rekoo.pubgm`, `com.vng.pubgmobile`, `com.tencent.tmgp.pubgmhd` (Peacekeeper Elite), `com.pubg.newstate`. Deliberately **not** `com.tencent.tmgp.pubgm` variants I can't confirm, and not a wildcard on `com.tencent.*` — scoping to exact packages is half of "just enough".

Debug scopes (kept in the JSON, off by default): AIDA64 (`com.aida64`), Device Info HW (`ru.andr7e.deviceinfohw`). These are how you verify the spoof landed without opening the game.

### 3.7 Self-test

A `BootReceiver`-free, headless approach: when the module hooks its **own** package (`dev.nextrounlock.fpsunlock`, added to scope manually), it dumps real-vs-spoofed values to logcat. Plus a `--dump` style check via AIDA64. No UI to build, nothing to keep in sync.

---

## 4. Project layout

```
nextrounlock/
├─ PLAN.md
├─ settings.gradle
├─ build.gradle                     # AGP 8.5.x via plugin marker
├─ gradle.properties                # android.useAndroidX=true, jvmargs
├─ local.properties                 # sdk.dir=… (gitignored)
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml        # <meta-data xposedmodule/xposedminversion/xposedscope>
│     ├─ assets/
│     │  ├─ xposed_init             # dev.nextrounlock.fpsunlock.HookEntry
│     │  └─ config.json
│     ├─ java/dev/nextrounlock/fpsunlock/
│     │  ├─ HookEntry.java          # IXposedHookLoadPackage + IXposedHookZygoteInit
│     │  ├─ Config.java             # loader + 3-tier resolution
│     │  ├─ Profile.java            # struct + buildFields(tier) + validation
│     │  ├─ BuildSpoofer.java
│     │  ├─ DisplaySpoofer.java
│     │  ├─ SystemPropsSpoofer.java # L2+, best-effort reflection
│     │  ├─ NativeLoader.java       # phase 3
│     │  └─ Log.java                # [fpsunlock] prefix, rate-limited
│     └─ cpp/                       # phase 3 only
│        ├─ CMakeLists.txt
│        └─ propshield.cpp
└─ scripts/
   ├─ deploy.ps1                    # adb install -r + force-stop targets + logcat tail
   └─ verify.ps1                    # greps logcat for the resolved profile
```

`app/build.gradle` essentials:

```gradle
android {
    namespace 'dev.nextrounlock.fpsunlock'
    compileSdk 34
    defaultConfig {
        applicationId 'dev.nextrounlock.fpsunlock'
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName '0.1.0'
        ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }   // phase 3
    }
    buildFeatures { buildConfig true }
    packaging { jniLibs { useLegacyPackaging true } }
}
dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
    // phase 3: implementation 'com.bytedance:bytehook:1.1.x' (or vendored static lib)
}
```

Manifest needs no activity, no permission, no `android:exported` surface — just the four `xposed*` meta-data entries and `xposedminversion=93` for LSPosed.

Build without Android Studio (matches what you're installing):

```powershell
$env:ANDROID_HOME = "…\Android\Sdk"
# local.properties: sdk.dir=C\:\\…\\Android\\Sdk
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"     # phase 3 only
gradle assembleDebug            # or gradlew once wrapper is generated
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 5. Phases

### Phase 0 — environment & device truth (before any code)

- [ ] SDK/NDK/platform-tools installed, `gradle assembleDebug` produces a signed-by-debug-key APK from an empty module skeleton.
- [ ] Record baseline: device model, `getprop ro.product.model`, panel max refresh rate (`adb shell dumpsys display`), PUBG version, current max frame-rate option in-game.
- [ ] Confirm the panel is genuinely 120 Hz. If not: the feature is cosmetic and the plan should say so before phase 2.
- [ ] LSPosed installed, game launches with LSPosed active (ACE isn't already killing the process).
- [ ] Record which client this is: `Client.GetPublishRegion()`. If it resolves to BLUEHOLE, the `t.BLUEHOLE120` clamp (§2.2 item 6) applies and the module cannot fix it by identity — stop and reassess. GLOBAL/KR/TW/VNG are fine.

### Phase 1a — probe mode (one deploy, before the real spoof)

A `probe: true` flag installs pass-through logging (no substitution) on `Build` field reads, `SystemProperties.get`/`getInt`, `Display.getMode`/`getSupportedModes`/`getRefreshRate`/`getDisplayInfo`, and `GLES20.glGetString`. One launch into the graphics menu tells us which of these the native layer actually touches, and whether the display hooks in phase 2 are even on the critical path. ~150 lines, no risk, and it makes phase 1's acceptance test meaningful rather than assumed.

### Phase 1 — L1/L2 identity spoof (the core)

- [ ] `HookEntry` + `Config` + `Profile` + `BuildSpoofer`, `S24U` profile, `tier=L2`.
- [ ] Deploy, force-stop game, relaunch.
- [ ] **Acceptance A:** AIDA64 (scoped) reports `SM-S928B` / `samsung` / `SM8650`. Proves hook load path + field overwrite.
- [ ] **Acceptance B:** in-game Graphics menu, FPS row shows **120** enabled (level 8) with Graphics=Smooth, i.e. `GSC_FPS:InitRealSupportFPS` logged `FPS:120, maxLevel:8`. This is the *only* proof the native table accepted the identity — the button rendering is native-gated, not Lua-gated.
- [ ] **Acceptance C:** logcat shows exactly one `[fpsunlock] applied profile=S24U tier=L2 pid=…` line, no exceptions.
- [ ] **Acceptance D:** `GSC_FPS:GetMaxFPSLevel` log shows `LimitedFPS:120 -> level 8` and `ExpandFPSByDeviceLevel` at 120.

### Phase 2 — display layer

- [ ] `DisplaySpoofer` with all six hooks, `Display.Mode` dual construction path.
- [ ] **Acceptance A:** after selecting 120 and entering a match, `t.MaxFPS` is 120 — verify via `logic_setting_graphics.SetFPS` log line `StrFPS: 120` and no `clamp to MaxFPSInMatch` line. **This is the real acceptance test**; the menu rendering 120 proves nothing about the limiter.
- [ ] **Acceptance B:** in-game FPS holds near 120 during free-look, doesn't downshift.
- [ ] If `SetFPS` shows `StrFPS` clamped below 120, the cause is one of: `MaxFPSInMatch` (server, unfixable), `LockFPSMod4High` (game mode, unfixable), or `GetDeviceRecommendFPS` returning low (identity-linked, revisit phase 1).
- [ ] L1.5 check: confirm `PhysicalScreenWidth/Height` in the payload match the spoofed profile, else revert screen spoof.

### Phase 3 — native prop layer (built, disabled)

- [ ] `libpropshield.so` + `initZygote` extraction + allowlisted key hook.
- [ ] **Acceptance:** prop-reading debug app scoped through the module returns spoofed values; game still launches; no ACE violation in logcat.
- [ ] Leave `native.enabled=false` unless consistency is specifically wanted.

### Phase 4 — hardening / polish

- [ ] Rate-limited logging, config validation with sane defaults on any parse error (never crash the game process).
- [ ] `deploy.ps1` / `verify.ps1`.
- [ ] `README` with the profile table and the tier ladder.

---

## 6. Test matrix

| Case | Expected |
|---|---|
| Global (`com.tencent.ig`) | Menu shows 120; `SetFPS` logs `StrFPS: 120`; frames near 120 |
| BGMI (`com.pubg.imobile`) | same, unless the build is BLUEHOLE → clamped to 90 |
| Non-target app (e.g. Chrome) | untouched, no hooks logged |
| Debug app scoped | spoofed values visible in its device page |
| Config missing / malformed | module falls back to compiled default, logs, game still runs |
| Offline launch | identical behaviour (no network in design) |
| Game update | re-verify; the native FPS table and `GetDeviceMaxFPSByDeviceLevel` behaviour are the things that can change |
| Match with `battle_custom_cfg.FrameLimit` | `t.MaxFPS` clamped to server value — expected, not a bug |

Diagnostics: `adb logcat -s LSPosed-Bridge:* | Select-String "fpsunlock|GSC_FPS|SetFPS"` — the game's own `GSC_FPS:` and `logic_setting_graphics` lines are the ground truth and cost nothing to read. Also `dumpsys gfxinfo`, in-game FPS display, 120 fps screen recording.

---

## 7. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| ACE sees `Build` fields ≠ real props | account risk | L1/L2 keeps the diff name-only; `FINGERPRINT` untouched until L3. Note the server already gets a coherent hardware profile via `send_on_match_req(501,…)` regardless — this risk predates the module for anyone who runs an unlocker |
| Server-side `DeviceOSInfo` payload shows honest GPU/RAM/screen alongside a spoofed model | inconsistency signal | L1.5 screen spoof; GPU/RAM are **not** spoofed by design (spoofing RAM/GPU lies to the engine and degrades performance) |
| `MaxFPSInMatch` server clamp | 120 unreachable in some rooms | not fixable client-side; document it and don't try |
| BLUEHOLE region `t.BLUEHOLE120` | hard 90 cap | phase 0 region check; not fixable by identity |
| Game update changes the native FPS table | module silently stops working | config-driven profile swap; re-verify after each patch |
| `static final` write blocked on a future ART | spoof no-ops | per-field error logging; AIDA64 acceptance test catches it immediately |
| `Display.Mode` hidden ctor removed | `getMode` hook fails | field-mutation fallback path |
| 60 Hz panel or weak SoC | `t.MaxFPS=120` but real fps lower, plus heat | phase 0 gate + real frame-time measurement |
| LSPosed scope misconfigured | nothing happens | module logs its own PID + resolved profile on every launch |

---

## 8. Decisions (locked)

1. **Default profile: `S24U` (`SM-S928B`).** Phone-native, SD 8 Gen 3, no tablet side effects. `SM_X910` ships as a JSON swap.
2. **Tier default: `L2`** — L1 fields + `SOC_MODEL`/`SOC_MANUFACTURER`, plus L1.5 screen spoof. `FINGERPRINT` stays off.
3. **Phase 3 native layer: built in v1, `native.enabled=false`.**
4. **Target: `compileSdk 34` / `targetSdk 34`, `minSdk 26`.** Android 14 (API 34) confirmed.

Probe mode is **in**, as phase 1a — see §5.
