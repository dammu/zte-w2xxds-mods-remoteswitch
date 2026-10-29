# Build + install RemoteSwitch, restart the two hooked processes, and make sure the first
# physical F9 press afterwards is not lost.
#
# Why the extra step: system_server caches an *unstable* proxy to uSmartLauncher's provider.
# After we kill uSmartLauncher that proxy is dead; the next F9 press makes PhoneWindowManager's
# ContentResolver.call() return null silently (and only then drop the proxy), so the first press
# does nothing. We trigger that first call ourselves with an injected KEYCODE_LAUNCHER_SWITCH (307).
# If system_server had no stale proxy (e.g. nobody pressed F9 since boot) the injected key is a real
# press and toggles the mode; we detect that and inject once more to toggle back.
#
# Run: pwsh -File deploy.ps1 [-NoBuild]   (needs adb on PATH and a rooted device with the module enabled)
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$uri = 'content://com.zte.usmartlauncher.defaulthome'

function Call($method) { (adb shell "content call --uri $uri --method $method" | Out-String).Trim() }
function Mode() { $s = Call 'rs_status'; if ($s -match 'mode=(\w+)') { $Matches[1] } else { 'unknown' } }

if (-not $NoBuild) { & "$root\build.ps1" }
# --no-incremental: with remoteswitch.apk.idsig beside the apk adb tries an incremental install, which hangs
adb install -r -t --no-incremental --user 0 "$root\remoteswitch.apk" | Select-Object -Last 1

Write-Host "restarting hooked processes (kill, not force-stop)"
adb shell "su -c 'for p in `$(pidof com.zte.usmartlauncher) `$(pidof cm.komect.aqb.android.hjqbind); do kill `$p; done'" | Out-Null
Start-Sleep -Seconds 6
$ping = Call 'rs_ping'
if ($ping -notmatch 'ok=true') { throw "hook not answering after restart: $ping" }
Write-Host "hook alive: $ping"

$before = Mode
Write-Host "mode before warm-up: $before"
adb shell input keyevent 307 | Out-Null       # burns the stale proxy, or acts as a real press if there was none
Start-Sleep -Seconds 3
$after = Mode
if ($after -ne $before) {
    # a real short press in remote mode leaves it (with the "last session" preset one in tablet mode enters it)
    Write-Host "no stale proxy: the injected key switched $before -> $after, restoring $before"
    if ($before -eq 'remote') { Call 'rs_enter_remote' | Out-Null } else { Call 'rs_exit_remote' | Out-Null }
    $after = Mode
    if ($after -ne $before) { throw "mode did not come back: $before -> $after" }
} else {
    Write-Host "mode unchanged ($after)"
}
# a real press in tablet mode only opens the floating UI: close it again
if ($after -eq 'tablet' -and ((adb shell "dumpsys window | grep mCurrentFocus") -match 'OverlayActivity')) {
    adb shell input keyevent 111 | Out-Null
}
# Installing killed our remote-mode HOME (ChooserActivity); the ZTE desktop task is what is left on top,
# and in remote mode the Home key is swallowed, so nothing would bring ours back by itself.
adb shell "dumpsys window | grep mCurrentFocus" | Select-String 'InstallSuccess' | ForEach-Object {
    adb shell input keyevent 4 | Out-Null       # ZTE's "install succeeded" page sits on top after adb install
    Start-Sleep -Seconds 1
}
if ($after -eq 'remote') {
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
    Write-Host "remote mode: reopened the remote desktop (it brings up the floating UI)"
}
Write-Host "deploy done; next physical F9 press will be handled"
