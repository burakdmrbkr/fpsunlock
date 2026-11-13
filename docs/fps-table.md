# FPS capability table — measured on device

Source: the game's own UE4 log (`ShadowTrackerExtra.log`), decoded in-process by
`FpsProbe` (the file is XOR-0x11 obfuscated) and re-emitted to logcat.

Device: Redmi Note 13 Pro 5G (`2312DRA50G`, `SM7435` / SD 7s Gen 2, `parrot`),
1220×2712 @ 120 Hz. Module active as `S24U` (`SM-S928B`), tier L2.

## What the device table actually returned

These are the engine console variables the game sets at graphics initialisation,
read straight out of its log:

| CVar | Value | Meaning |
|---|---|---|
| `r.PUBGDeviceFPSVerySmooth` | **120** | Smooth — 120 unlocked |
| `r.PUBGDeviceFPSLow` | **120** | 120 unlocked |
| `r.PUBGDeviceFPSMid` | 90 | |
| `r.PUBGDeviceFPSHigh` | 90 | |
| `r.PUBGDeviceFPSHDR` | **90** | **HDR caps at 90** |
| `r.PUBGDeviceFPSUltralHigh` | **60** | **Ultra HDR caps at 60** |
| `r.PUBGDeviceFPSUltimateHigh` | 30 | |
| `r.PUBGDeviceFPSDef` | 30 | |
| `r.PUBGRecommendFPS` | **120** | recommendation accepted |
| `t.MaxFPS` | 90 | actual limiter at time of capture (HDR selected) |
| `t.OverrideMaxFPS` | 0 | no override active |

Every `*Expand` value is **0**:

```
r.PUBGDeviceFPSVerySmoothExpand : 0
r.PUBGDeviceFPSLowExpand        : 0
r.PUBGDeviceFPSMidExpand        : 0
r.PUBGDeviceFPSHighExpand       : 0
r.PUBGDeviceFPSHDRExpand        : 0
r.PUBGDeviceFPSUltralHighExpand : 0
r.PUBGDeviceFPSUltimateHighExpand: 0
```

## Conclusions

### 1. HDR at 120 is not reachable

`DoClickFPS` has exactly two ways to apply level 8 (120):

- `bSupport` path — enabled when `GetDeviceMaxFPSByDeviceLevel(quality) >= 120`.
  For HDR that value is **90**. Blocked.
- `bExpandSupport` path — enabled when
  `GetExpandDeviceMaxFPSByDeviceLevel(quality) >= 120`. For HDR
  `r.PUBGDeviceFPSHDRExpand` is **0**. Blocked.

Both closed. There is no third path, and neither is affected by anything the module
controls: the values come from a compiled-in CVar table, not from `Build` identity.

**`t.MaxFPS 90` in the same log confirms it** — the game set the limiter to 90,
which is why HDR still feels like 90 fps. It *is* 90 fps.

### 2. The unlock itself is confirmed working

```
r.PUBGRecommendFPS:120
r.PUBGDeviceFPSVerySmooth:120
r.PUBGDeviceFPSLow:120
```

Without the module these would be gated to the real device profile. The `Smooth`
preset genuinely reaches 120, which matches the on-device report of "smooth 120 fps".

### 3. "Ultra Extreme missing at HDR, Extreme+ shown" is correct game behaviour

`Extreme+` is the 90 FPS row. The game is showing exactly the maximum it will allow
for HDR. It is not a bug in the module and not a misconfiguration — the frame-rate
menu is reporting the truth.

### 4. Why `Smooth` shows the fine-tune slider and 165

Those UI paths (`GSC_FPSFT`) key off `GetDeviceMaxFPSByDeviceLevel(SMOOTH)`, which is
120+ here, so the ladder renders. The 144/165 steps are selectable in the UI because
they are fine-tune values, but `t.MaxFPS` is still bounded by the panel (120 Hz).

## What could change this

Only a profile whose device table has a non-zero `r.PUBGDeviceFPSHDR` **or**
`r.PUBGDeviceFPSHDRExpand` of 120 would unlock HDR at 120 — but those are engine
CVars baked into the build, not read from `Build.MODEL`. Changing the spoofed profile
will not move them.

The one lever that *would* work is writing the CVar directly
(`t.MaxFPS` / `r.PUBGDeviceFPSHDR`), which means either:
- a native hook on `ExecuteCMD`/CVar resolution (resident code patch — different risk
  class from identity spoofing, breaks per build), or
- console access (not exposed in shipping builds).

Both are out of scope for this module by design. This module spoofs identity; it does
not patch engine state.

## Verification commands

```powershell
.\scripts\hdr-check.ps1 -Restart   # force-stop, clear, follow
# then in game: Settings -> Graphics -> HDR -> Frame Rate row
```

Look for:

```
GAME ... r.PUBGDeviceFPSHDR:90
GAME ... r.PUBGDeviceFPSHDRExpand:0
GAME ... t.MaxFPS = "90"
```
