# fpsunlock — deploy + observe
#
# Usage:
#   .\scripts\deploy.ps1                  # build, install, restart targets
#   .\scripts\deploy.ps1 -Build:$false     # install existing APK only
#   .\scripts\deploy.ps1 -Logs            # install then tail logcat

param(
    [bool]$Build = $true,
    [switch]$Logs,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$gradle = Join-Path $env:LOCALAPPDATA "GradleDist\gradle-8.7\bin\gradle.bat"
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"

# --- device selection ------------------------------------------------------
if (-not $Serial) {
    $lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
    if ($lines.Count -eq 0) { throw "No device attached. Connect the phone and retry." }
    if ($lines.Count -gt 1) {
        Write-Warning "Multiple transports for the same device. Pass -Serial. Candidates:"
        $lines | ForEach-Object { Write-Warning "  $($_.Split()[0])" }
        $Serial = ($lines | Select-Object -First 1).Split()[0]
        Write-Host "Using $Serial"
    } else {
        $Serial = $lines[0].Split()[0]
    }
}
Write-Host "device: $Serial" -ForegroundColor Cyan

function Adb { & $adb -s $Serial @args }

# --- build -----------------------------------------------------------------
if ($Build) {
    Write-Host "building..." -ForegroundColor Cyan
    & $gradle -p $root :app:assembleDebug --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
}

if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

# --- install ---------------------------------------------------------------
Write-Host "installing..." -ForegroundColor Cyan
Adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

# --- restart targets -------------------------------------------------------
# A spoof applied at process start cannot affect an already-running game.
# Force-stop is required after every deploy.
$targets = @(
    "com.tencent.ig",
    "com.pubg.imobile",
    "com.tencent.igfit",
    "com.aida64",
    "ru.andr7e.deviceinfohw"
)

foreach ($pkg in $targets) {
    $present = Adb shell "pm path $pkg" 2>$null
    if ($present -match "package:") {
        Adb shell "am force-stop $pkg" | Out-Null
        Write-Host "  force-stopped $pkg"
    }
}

Write-Host ""
Write-Host "Installed. Next steps:" -ForegroundColor Green
Write-Host "  1. Open LSPosed -> Modules -> fpsunlock -> enable"
Write-Host "  2. Confirm scope includes the game package"
Write-Host "  3. Launch the game"
Write-Host "  4. Run .\scripts\verify.ps1 to check it applied"

if ($Logs) {
    Write-Host ""
    Write-Host "tailing logcat (Ctrl+C to stop)..." -ForegroundColor Cyan
    Adb logcat -c
    Adb logcat -s fpsunlock:* LSPosed-Bridge:* AndroidRuntime:E
}
