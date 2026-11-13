# fpsunlock — capture the game's own FPS decisions
#
# The game logs its graphics/FPS reasoning to a UE4 log file under its private
# data dir, not to logcat. The module tails that file from inside the game's
# process and re-emits the interesting lines with the 'fpsunlock' tag.
#
# Usage:
#   .\scripts\hdr-check.ps1              # snapshot of what has been captured
#   .\scripts\hdr-check.ps1 -Follow      # live tail while you toggle settings
#   .\scripts\hdr-check.ps1 -Restart     # force-stop the game first, then follow
#
# Workflow for the HDR question:
#   1. .\scripts\hdr-check.ps1 -Restart
#   2. Launch PUBG, go to Settings -> Graphics
#   3. Switch Graphics to HDR, open the Frame Rate row
#   4. Watch for the InitRealSupportFPS line and read ExpandFPS

param(
    [switch]$Follow,
    [switch]$Restart,
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

$game = "com.tencent.ig"

if ($Restart) {
    Write-Host "force-stopping $game ..." -ForegroundColor Cyan
    Adb shell "am force-stop $game" | Out-Null
    Write-Host "clearing logcat ..." -ForegroundColor Cyan
    Adb logcat -c
    Write-Host ""
    Write-Host "Now launch PUBG and open Settings -> Graphics." -ForegroundColor Green
    Write-Host "Switch to HDR, then open the Frame Rate row." -ForegroundColor Green
    Write-Host ""
}

$pattern = "GAME |fpsunlock: applying|build spoof|getSupportedModes|InitRealSupportFPS|GetMaxFPSLevel|SetFPS|ExpandFPS|MaxFPSInMatch|bluehole|InitFPSFTSwitch"

if ($Follow) {
    Write-Host "following (Ctrl+C to stop) ..." -ForegroundColor Cyan
    Adb logcat -s fpsunlock:V | Select-String -Pattern $pattern
    exit
}

# --- snapshot --------------------------------------------------------------
$dump = Adb logcat -d -s fpsunlock:V

function Section($title, $rows) {
    Write-Host ""
    Write-Host "=== $title ===" -ForegroundColor Cyan
    if ($rows) { $rows | Select-Object -Last 20 | ForEach-Object { Write-Host $_ } }
    else { Write-Host "  (nothing captured)" }
}

Section "Module lifecycle" ($dump | Select-String -Pattern "applying |build spoof|fpsprobe: watching")
Section "Display hooks"    ($dump | Select-String -Pattern "hooked Display|getSupportedModes ->|geometry")
Section "Game FPS decisions" ($dump | Select-String -Pattern "GAME ")

# --- interpretation --------------------------------------------------------
Write-Host ""
Write-Host "=== what to look for ===" -ForegroundColor Green
Write-Host "  InitRealSupportFPS ... FPS:<n>, ExpandFPS:<m>"
Write-Host "    FPS      = supported cap for the current quality"
Write-Host "    ExpandFPS = beta/expand cap. This is the one that matters for HDR."
Write-Host ""
Write-Host "  ExpandFPS 120 at HDR  => click 120; GSC_FPS:DoClickFPS takes the"
Write-Host "                           expand branch, which has NO quality restriction."
Write-Host "  ExpandFPS 0 or 90     => the expand path is closed for this profile/quality."
Write-Host "                           Try rotating 'active' profile (ROG8 / REDMAGIC9P)."
Write-Host ""
Write-Host "  SetFPS ... StrFPS: 120  => t.MaxFPS actually set. This is the real win."
Write-Host "  clamp to MaxFPSInMatch  => server room limit; not fixable client-side."
