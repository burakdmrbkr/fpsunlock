# fpsunlock — verify the spoof actually applied
#
# Reads the module's own log lines plus the game's GSC_FPS / SetFPS lines,
# which are the ground truth for whether the unlock worked.
#
# Usage:
#   .\scripts\verify.ps1                # snapshot of recent buffer
#   .\scripts\verify.ps1 -Clear -Live    # clear, then follow

param(
    [switch]$Clear,
    [switch]$Live,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

if (-not $Serial) {
    $lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if ($lines.Count -eq 0) { throw "No device attached." }
    $Serial = ($lines | Select-Object -First 1).Split()[0]
}

function Adb { & $adb -s $Serial @args }

if ($Clear) {
    Adb logcat -c
    Write-Host "logcat cleared" -ForegroundColor Cyan
}

$filter = "fpsunlock|GSC_FPS|logic_setting_graphics|InitRealSupportFPS|GetMaxFPSLevel|BLUEHOLE120"

if ($Live) {
    Write-Host "following (Ctrl+C to stop)..." -ForegroundColor Cyan
    Adb logcat -s fpsunlock:V LSPosed-Bridge:V | Select-String -Pattern $filter
    exit
}

# --- snapshot --------------------------------------------------------------
$dump = Adb logcat -d -s fpsunlock:V LSPosed-Bridge:V

$sections = [ordered]@{
    "Module lifecycle"  = $dump | Select-String -Pattern "fpsunlock\].*(zygote init|applying|ready|self-test)"
    "Build spoof"       = $dump | Select-String -Pattern "build spoof:|Build=MANUFACTURER"
    "Display spoof"     = $dump | Select-String -Pattern "hooked Display\.|getSupportedModes ->|screen geometry"
    "Warnings/errors"   = $dump | Select-String -Pattern "WARN|ERROR|write failed|NO Build fields"
}

foreach ($k in $sections.Keys) {
    Write-Host ""
    Write-Host "=== $k ===" -ForegroundColor Cyan
    $rows = $sections[$k]
    if ($rows) {
        $rows | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "  (nothing)"
    }
}

# --- game-side ground truth ------------------------------------------------
Write-Host ""
Write-Host "=== game FPS decisions ===" -ForegroundColor Cyan
$gameLines = Adb logcat -d | Select-String -Pattern "GSC_FPS|logic_setting_graphics:SetFPS"
if ($gameLines) {
    $gameLines | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "  (none — the game has not reached the graphics settings menu yet)"
}

Write-Host ""
Write-Host "How to read this:" -ForegroundColor Green
Write-Host "  - 'applying ... model=SM-S928B' => identity spoof armed"
Write-Host "  - 'InitRealSupportFPS ... FPS:120, maxLevel:8' => native table accepted it"
Write-Host "  - 'SetFPS ... StrFPS: 120' => t.MaxFPS actually set to 120 (the real win)"
Write-Host "  - 'clamp to MaxFPSInMatch' => server room limit, not fixable client-side"
Write-Host "  - 'limit to 90 by bluehole120' => BLUEHOLE region, identity cannot fix it"
