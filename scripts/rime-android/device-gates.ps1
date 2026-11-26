<#
.SYNOPSIS
  Runs the Rime device gates (from 0.9.0; 0.9.2 by default) over adb on one plugged-in phone and writes the results.

.DESCRIPTION
  The checks run inside the app (RimeGateActivity, guarded by the DUMP permission only the adb
  shell holds) and report through logcat. This script starts each one, waits for it, and judges:

    cold     process start to Rime's first candidates, data already copied   < 500 ms (median of 3)
    replay   the replay fixture gives the PC's candidates exactly             0 rows differ
    latency  per key through the keyboard's own session, 9-key               p95 < 50 ms, no Classic fallback
    memory   PSS and RSS with Rime and Classic loaded (plus dumpsys meminfo)  recorded, not gated
    pages    page size, libvfrime.so loads and answers                        loads
    soak     random typing, pins, deletes, partial commits for -SoakSeconds   no crash, no ANR, no lost text
    16 KB    zipalign -c -P 16 on the installed APK                           aligned

  It never changes her data: nothing is learned (the soak runs with learning off) and nothing is
  cleared. The replay fixture is a first-day user's, so run it right after installing the release (-ExpectedVersion, 0.9.2 by default), before
  she has typed with Rime. Installs only when -Apk is given.

  -Gates runs some of them only (all by default), for example the fast ones after an update:
  -Gates cold,replay,pages. Gates left out are not run and do not count as failures.

.EXAMPLE
  ./scripts/rime-android/device-gates.ps1 -OutDir C:\gates
.EXAMPLE
  ./scripts/rime-android/device-gates.ps1 -Serial R5CX... -Apk VoiceFlow-keyboard-0.9.2.apk -SoakSeconds 900
.EXAMPLE
  ./scripts/rime-android/device-gates.ps1 -Serial R5CX... -Gates cold,replay,pages
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [int]$SoakSeconds = 600,
    [string]$OutDir = ".",
    [string]$Apk = "",
    [string]$Adb = "",
    [string]$Package = "com.voiceflowkeyboard.ime",
    [string]$ExpectedVersion = "0.9.2",
    [string[]]$Gates = @("cold", "replay", "latency", "memory", "pages", "soak", "16kb")
)

$ErrorActionPreference = "Stop"

# -Gates cold,replay,pages arrives as one array; "cold,replay" in quotes as one string.
$Gates = @($Gates | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ })
$known = @("cold", "replay", "latency", "memory", "pages", "soak", "16kb")
foreach ($g in $Gates) { if ($known -notcontains $g) { Write-Host "STOP: unknown gate '$g' (known: $($known -join ', '))"; exit 2 } }
# 16 KB judges whether the library loads, which is the pages gate.
if ($Gates -contains "16kb" -and $Gates -notcontains "pages") { $Gates += "pages" }
function Test-Gate([string]$name) { return $Gates -contains $name }

function Find-Tool([string]$name, [string[]]$candidates) {
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path } }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$sdk = $env:ANDROID_HOME
if (-not $Adb) {
    $Adb = Find-Tool "adb" @(
        $(if ($sdk) { Join-Path $sdk "platform-tools\adb.exe" }),
        (Join-Path $PSScriptRoot "..\..\.tooling\android-sdk\platform-tools\adb.exe"))
}
if (-not $Adb) { Write-Host "STOP: adb not found (pass -Adb or set ANDROID_HOME)"; exit 2 }

# Runs adb and returns its output as text; stderr lines do not stop the script.
function Invoke-Adb([string[]]$arguments) {
    $all = @()
    if ($Serial) { $all += @("-s", $Serial) }
    $all += $arguments
    $saved = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $Adb @all 2>&1 | ForEach-Object { "$_" }
        $script:AdbExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    return ($out -join "`n")
}

# ---------------------------------------------------------------- the phone
$devices = (Invoke-Adb @("devices")) -split "`n" | Where-Object { $_ -match "^\S+\s+device$" } | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $Serial) {
    if (@($devices).Count -ne 1) { Write-Host "STOP: expected exactly one phone over adb, found $(@($devices).Count); pass -Serial"; exit 2 }
    $Serial = @($devices)[0]
}
$model = (Invoke-Adb @("shell", "getprop", "ro.product.model")).Trim()
$release = (Invoke-Adb @("shell", "getprop", "ro.build.version.release")).Trim()
$api = (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")).Trim()
$pageSize = (Invoke-Adb @("shell", "getconf", "PAGE_SIZE")).Trim()
Write-Host "phone      $model, Android $release (API $api), page size $pageSize"

if ($Apk) {
    Write-Host "installing $Apk"
    $installed = Invoke-Adb @("install", "-r", $Apk)
    if ($installed -notmatch "Success") { Write-Host "STOP: install failed: $installed"; exit 2 }
}
$dump = Invoke-Adb @("shell", "dumpsys", "package", $Package)
$version = [regex]::Match($dump, "versionName=(\S+)").Groups[1].Value
if ($version -ne $ExpectedVersion) { Write-Host "STOP: $Package is '$version', expected $ExpectedVersion"; exit 2 }
Write-Host "app        $Package $version"

# ---------------------------------------------------------------- one check
function Invoke-Gate([string]$action, [int]$timeoutSeconds, [hashtable]$extras = @{}, [switch]$Fresh) {
    if ($Fresh) { Invoke-Adb @("shell", "am", "force-stop", $Package) | Out-Null; Start-Sleep -Milliseconds 800 }
    Invoke-Adb @("logcat", "-c") | Out-Null
    # --activity-clear-task: the previous check's activity is still on top, and a plain start only
    # brings its task to the front ("Activity not started"), so no new check would run. Clearing the
    # task makes a new instance in the same process, which the soak's same-process check relies on.
    $start = @("shell", "am", "start", "--activity-clear-task", "-n", "$Package/.RimeGateActivity", "--es", "action", $action)
    foreach ($key in $extras.Keys) {
        $value = $extras[$key]
        if ($value -is [bool]) { $start += @("--ez", $key, $value.ToString().ToLower()) }
        else { $start += @("--ei", $key, "$value") }
    }
    $launched = Invoke-Adb $start
    if ($launched -match "Error|Exception|Activity not started|intent has been delivered") { return [pscustomobject]@{ action = $action; pass = $false; error = $launched } }
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $log = Invoke-Adb @("logcat", "-d", "-v", "raw", "-s", "VFRimeGate:I")
        if ($log -match "VFGATE-DONE") {
            $line = ($log -split "`n" | Where-Object { $_ -like "VFGATE {*" } | Select-Object -Last 1)
            if (-not $line) { return [pscustomobject]@{ action = $action; pass = $false; error = "no result line" } }
            return ($line.Substring(7) | ConvertFrom-Json)
        }
    }
    return [pscustomobject]@{ action = $action; pass = $false; error = "no answer in $timeoutSeconds s (crashed or hung?)" }
}

function Get-Pid { (Invoke-Adb @("shell", "pidof", $Package)).Trim() }

$results = [ordered]@{}

# Cold start: the first run after install copies the data; the gate is the start after that.
$first = $null
if (Test-Gate "cold") {
    $first = Invoke-Gate "cold" 180 -Fresh
    $results["cold-first-start"] = $first
    $colds = @()
    foreach ($i in 1..3) { $colds += Invoke-Gate "cold" 120 -Fresh }
    $coldMs = @($colds | ForEach-Object { $_.startToFirstCandidatesMs } | Sort-Object)
    $results["cold"] = [ordered]@{
        runs = $colds
        medianStartToFirstCandidatesMs = $coldMs[1]
        pass = (@($colds | Where-Object { -not $_.dataWasInstalled }).Count -eq 0) -and ($coldMs[1] -lt 500)
    }
}

if (Test-Gate "replay") { $results["replay"] = Invoke-Gate "replay" 300 }
if (Test-Gate "latency") { $results["latency"] = Invoke-Gate "latency" 600 }
if (Test-Gate "pages") { $results["pages"] = Invoke-Gate "pages" 120 }
$memory = $null
if (Test-Gate "memory") {
    $memory = Invoke-Gate "memory" 180
    $memInfo = Invoke-Adb @("shell", "dumpsys", "meminfo", $Package)
    $memory | Add-Member -NotePropertyName dumpsysTotalPss -NotePropertyValue ([regex]::Match($memInfo, "TOTAL PSS:\s+(\d+)").Groups[1].Value) -Force
    $results["memory"] = $memory
}

# Soak: the same process must survive it, with no crash or ANR logged.
$soak = $null
if (Test-Gate "soak") {
    Invoke-Adb @("logcat", "-b", "crash", "-c") | Out-Null
    Invoke-Adb @("logcat", "-b", "events", "-c") | Out-Null
    $pidBefore = Get-Pid
    $soak = Invoke-Gate "soak" ($SoakSeconds + 180) @{ seconds = $SoakSeconds; learn = $false }
    $pidAfter = Get-Pid
    $crashes = Invoke-Adb @("logcat", "-b", "crash", "-d")
    $anrs = (Invoke-Adb @("logcat", "-b", "events", "-d")) -split "`n" | Where-Object { $_ -match "am_anr" -and $_ -match [regex]::Escape($Package) }
    $crashLines = ($crashes -split "`n" | Where-Object { $_ -match [regex]::Escape($Package) })
    $soak | Add-Member -NotePropertyName samePid -NotePropertyValue ($pidBefore -and $pidBefore -eq $pidAfter) -Force
    $soak | Add-Member -NotePropertyName crashLines -NotePropertyValue @($crashLines).Count -Force
    $soak | Add-Member -NotePropertyName anrs -NotePropertyValue @($anrs).Count -Force
    $soak.pass = [bool]$soak.pass -and $soak.samePid -and (@($crashLines).Count -eq 0) -and (@($anrs).Count -eq 0)
    $results["soak"] = $soak
}

# 16 KB: the installed APK's native libraries must be stored page-aligned for 16 KB.
$aligned = $null
if (Test-Gate "16kb") {
$apkPath = ((Invoke-Adb @("shell", "pm", "path", $Package)) -split "`n" | Where-Object { $_ -like "package:*base.apk" } | Select-Object -First 1)
$aligned = $null
$zipalign = $null
if ($sdk -and (Test-Path (Join-Path $sdk "build-tools"))) {
    $bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1
    $zipalign = Join-Path $bt.FullName "zipalign.exe"
}
if ($apkPath -and $zipalign -and (Test-Path $zipalign)) {
    $local = Join-Path ([System.IO.Path]::GetTempPath()) ("vf-gate-" + [guid]::NewGuid().ToString("N") + ".apk")
    Invoke-Adb @("pull", $apkPath.Substring(8).Trim(), $local) | Out-Null
    $check = & $zipalign -c -P 16 -v 4 $local 2>&1 | ForEach-Object { "$_" }
    $aligned = ($LASTEXITCODE -eq 0) -and (($check -join "`n") -match "Verification succesful|Verification successful")
}
$results["16kb"] = [ordered]@{ pageSize = $pageSize; zipalignChecked = ($aligned -ne $null); aligned = $aligned
    loadsHere = $results["pages"].pass; pass = ($aligned -ne $false) -and [bool]$results["pages"].pass }
}

# ---------------------------------------------------------------- report
$summary = [ordered]@{
    phone = "$model, Android $release (API $api)"; serial = $Serial; pageSize = $pageSize; app = "$Package $version"
    ranAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"); gates = $Gates; results = $results
}
New-Item -ItemType Directory -Force $OutDir | Out-Null
$file = Join-Path $OutDir ("rime-gates-" + ($model -replace '[^A-Za-z0-9]', '') + "-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
$summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $file

Write-Host ""
Write-Host ("{0,-10} {1,-5} {2}" -f "gate", "pass", "numbers")
$lines = [ordered]@{
    cold    = "median $($results.cold.medianStartToFirstCandidatesMs) ms (first start, with the copy: $($first.startToFirstCandidatesMs) ms)"
    replay  = "$($results.replay.rows) rows, $($results.replay.differ) differ"
    latency = "p50 $($results.latency.p50Ms) / p95 $($results.latency.p95Ms) / p99 $($results.latency.p99Ms) ms over $($results.latency.keys) keys; Classic answered $($results.latency.answeredByClassic)"
    memory  = "PSS $($memory.totalPssKb) kB (dumpsys $($memory.dumpsysTotalPss) kB), RSS $($memory.VmRSSKb) kB, file-backed $($memory.RssFileKb) kB"
    pages   = "page size $($results.pages.pageSize), loaded $($results.pages.libraryLoaded)"
    soak    = "$($soak.steps) steps in $($soak.seconds) s, $(if ($null -ne $soak.violations) { @($soak.violations).Count } else { 'n/a' }) violations, crashes $($soak.crashLines), ANRs $($soak.anrs), same process $($soak.samePid)"
    "16kb"  = "zipalign -P 16: $aligned"
}
$allPass = $true
foreach ($key in $lines.Keys) {
    if (-not (Test-Gate $key)) { continue }
    $pass = [bool]$results[$key].pass
    if ($key -ne "memory" -and -not $pass) { $allPass = $false }
    $numbers = $lines[$key]
    if ($results[$key].error) { $numbers = "error: $($results[$key].error)" }
    Write-Host ("{0,-10} {1,-5} {2}" -f $key, $(if ($pass) { "yes" } else { "NO" }), $numbers)
}
Write-Host ""
Write-Host "results: $file"
$which = if ($Gates.Count -eq $known.Count) { "ALL GATES" } else { "GATES RUN ($($Gates -join ', '))" }
if ($allPass) { Write-Host "$which PASS"; exit 0 } else { Write-Host "GATES FAILED"; exit 1 }
