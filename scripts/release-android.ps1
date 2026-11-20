<#
.SYNOPSIS
  Builds, verifies and stages an Android release of the VoiceFlow keyboard on the vf-sync gateway.

.DESCRIPTION
  1. Builds the non-debuggable, arm64-only release APK (unless -Apk names one already built)
     and, unless -SkipTests, runs the unit tests first.
  2. Verifies it: package, versionCode and versionName; not debuggable; arm64-v8a only;
     signed by exactly one certificate whose SHA-256 is -ExpectedSigner.
  3. Copies it to -AcceptanceDir as VoiceFlow-keyboard-<versionName>.apk with a .sha256 file.
  4. Stages it on the NAS share the gateway serves (sync/README.md, "Releases"):
       releases/android/<versionCode>/<apk>, then releases/android/<versionCode>/release.json LAST.
     The gateway's rollout rule does the rest: the canary user's devices are offered it at once;
     everyone else only after every canary device has run it cleanly for 24 hours (or a force
     flag). Staging never writes flags.json.
  The NAS side refuses to stage when no canary device is configured, when the versionCode is not
  newer than every release already there (no downgrade, no reuse), or when a different file is
  already staged under that versionCode. Staging the identical file again is a no-op.

  -DryRun does steps 1-3 and prints the exact NAS script it would run, without connecting.

  The NAS is reached through -NasRunner: a Python file that runs a shell script on the NAS as root
  (`python <runner> <script> [timeout]`) and exposes HOST, USER, LATCH and password() for the upload.
  Nothing secret is passed on a command line or printed.

.EXAMPLE
  ./scripts/release-android.ps1 -DryRun -AcceptanceDir C:\vf\acceptance\android -Tooling <dir>\.tooling
.EXAMPLE
  ./scripts/release-android.ps1 -Apk C:\vf\acceptance\android\VoiceFlow-keyboard-0.8.1.apk -NasRunner <runner.py>
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$Apk = "",
    [switch]$SkipTests,
    [string]$Tooling = "",
    [string]$AcceptanceDir = "",
    [string]$NasRunner = $env:VF_NAS_RUNNER,
    [string]$ExpectedSigner = "41391299c69adb88387544f70f765f89ea73bffae399a4a1a0bad1b79f6734a1",
    [string]$Package = "com.voiceflowkeyboard.ime",
    [string]$ShareRoot = "/volume1/voiceflow",
    [string]$Owner = "1026:100",
    [string]$Container = "vf-sync",
    [string]$GatewayConfig = "/volume1/docker/vf-sync/config/config.json"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Fail([string]$message) {
    Write-Host "STOP: $message"
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message"
}

# Runs a native tool and returns its output as text. Windows PowerShell 5.1 would turn any
# line the tool writes to stderr (Gradle's notes, for one) into a terminating error under
# "Stop", so the preference is relaxed for the call only; the exit code is what counts.
function Invoke-Native([string]$exe, [string[]]$arguments, [switch]$Echo) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = & $exe @arguments 2>&1 | ForEach-Object {
            $line = "$_"
            if ($Echo) { Write-Host $line }
            $line
        }
        $script:NativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    return ($lines -join "`n")
}

# ---------------------------------------------------------------- tools
if ($Tooling -ne "") {
    $env:JAVA_HOME = Join-Path $Tooling "jdk"
    $env:ANDROID_HOME = Join-Path $Tooling "android-sdk"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    Fail "set ANDROID_HOME (or pass -Tooling <dir with jdk and android-sdk>)"
}
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1
if (-not $buildTools) { Fail "no Android build-tools under $env:ANDROID_HOME" }
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
foreach ($tool in @($apksigner, $aapt2)) {
    if (-not (Test-Path $tool)) { Fail "missing $tool" }
}

# ---------------------------------------------------------------- build
if ($Apk -eq "") {
    $gradlew = Join-Path $RepoRoot "gradlew.bat"
    if (-not $SkipTests) {
        Step "Unit tests"
        Invoke-Native $gradlew @("-p", $RepoRoot, "--console=plain", "testDebugUnitTest") -Echo | Out-Null
        if ($script:NativeExit -ne 0) { Fail "unit tests failed" }
    }
    Step "Release build (arm64, not debuggable, signed with the phones' certificate)"
    Invoke-Native $gradlew @("-p", $RepoRoot, "--console=plain", "assembleRelease") -Echo | Out-Null
    if ($script:NativeExit -ne 0) { Fail "release build failed" }
    $Apk = Join-Path $RepoRoot "app\build\outputs\apk\release\app-release.apk"
}
if (-not (Test-Path $Apk)) { Fail "no APK at $Apk" }
$Apk = (Resolve-Path $Apk).Path

# ---------------------------------------------------------------- verify
Step "Verify $Apk"
$badging = Invoke-Native $aapt2 @("dump", "badging", $Apk)
if ($script:NativeExit -ne 0) { Fail "aapt2 could not read the APK" }
$m = [regex]::Match($badging, "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'")
if (-not $m.Success) { Fail "could not read package, versionCode and versionName" }
$pkg = $m.Groups[1].Value
$versionCode = [int]$m.Groups[2].Value
$versionName = $m.Groups[3].Value
if ($pkg -ne $Package) { Fail "package is $pkg, expected $Package" }
if ($versionName -notmatch '^[0-9A-Za-z._+-]{1,40}$') { Fail "versionName '$versionName' is not allowed" }
if ($badging -match "application-debuggable") { Fail "the APK is debuggable" }
$native = [regex]::Match($badging, "native-code: (.*)")
if (-not $native.Success -or $native.Groups[1].Value.Trim() -ne "'arm64-v8a'") {
    Fail "native code must be arm64-v8a only (found: $($native.Groups[1].Value))"
}
$certs = Invoke-Native $apksigner @("verify", "--print-certs", $Apk)
if ($script:NativeExit -ne 0) { Fail "apksigner did not verify the APK" }
$digests = [regex]::Matches($certs, "certificate SHA-256 digest: ([0-9a-f]{64})") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
if (@($digests).Count -ne 1) { Fail "expected exactly one signing certificate, found $(@($digests).Count)" }
$signer = @($digests)[0]
if ($signer -ne $ExpectedSigner) { Fail "signer is $signer, expected $ExpectedSigner" }
$sha = (Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLowerInvariant()
$size = (Get-Item $Apk).Length
Write-Host "package      $pkg"
Write-Host "version      $versionName ($versionCode)"
Write-Host "abi          arm64-v8a only, not debuggable"
Write-Host "signer       $signer (matches)"
Write-Host "sha256       $sha"
Write-Host "size         $size"

# ---------------------------------------------------------------- acceptance copy
$apkName = "VoiceFlow-keyboard-$versionName.apk"
if ($AcceptanceDir -ne "") {
    Step "Acceptance copy"
    New-Item -ItemType Directory -Force $AcceptanceDir | Out-Null
    $target = Join-Path $AcceptanceDir $apkName
    if ((Resolve-Path $Apk).Path -ne $target) {
        Copy-Item -Force $Apk $target
    }
    $copied = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
    if ($copied -ne $sha) { Fail "the acceptance copy does not match" }
    Set-Content -Encoding ascii -NoNewline -Path "$target.sha256" -Value "$sha  $apkName`n"
    Write-Host "$target"
    Write-Host "$target.sha256"
}

# ---------------------------------------------------------------- NAS script
$incoming = "/tmp/vf-release"
$stage = @"
set -eu
VC=$versionCode
NAME='$versionName'
APK='$apkName'
SHA='$sha'
SIZE=$size
R='$ShareRoot/releases/android'
IN='$incoming'
# Canary first: refuse while no canary device is configured (the gateway would hold it anyway).
CFG=`$(tr -d ' \t\r\n' < '$GatewayConfig')
case "`$CFG" in
  *'"canaryDevices":[]'*|*'"canaryDevices":null'*) echo "STOP: no canary device in the gateway config"; exit 3 ;;
  *'"canaryDevices":['*) : ;;
  *) echo "STOP: canaryDevices not found in the gateway config"; exit 3 ;;
esac
mkdir -p "`$R"
# No downgrade and no reuse: newer than every release already staged, unless it is this same file.
if [ -f "`$R/`$VC/release.json" ]; then
  if grep -q "\"sha256\":\"`$SHA\"" "`$R/`$VC/release.json"; then echo "already staged: `$VC (`$SHA)"; exit 0; fi
  echo "STOP: a different release is already staged as `$VC"; exit 4
fi
for d in "`$R"/*/; do
  [ -d "`$d" ] || continue
  n=`$(basename "`$d")
  case "`$n" in *[!0-9]*) continue ;; esac
  if [ -f "`$d/release.json" ] && [ "`$n" -ge "`$VC" ]; then echo "STOP: release `$n is not older than `$VC"; exit 5; fi
done
test "`$(sha256sum "`$IN/`$APK" | cut -d' ' -f1)" = "`$SHA" || { echo "STOP: uploaded file hash differs"; exit 6; }
test "`$(stat -c %s "`$IN/`$APK")" = "`$SIZE" || { echo "STOP: uploaded file size differs"; exit 6; }
mkdir -p "`$R/`$VC"
chown $Owner '$ShareRoot/releases' "`$R" "`$R/`$VC"
cp "`$IN/`$APK" "`$R/`$VC/`$APK"
chown $Owner "`$R/`$VC/`$APK"
test "`$(sha256sum "`$R/`$VC/`$APK" | cut -d' ' -f1)" = "`$SHA"
# release.json last, renamed into place, so the gateway never sees half a release.
printf '{"schema":"voiceflow-release","version":1,"versionCode":%s,"versionName":"%s","apk":"%s","sha256":"%s","size":%s}\n' "`$VC" "`$NAME" "`$APK" "`$SHA" "`$SIZE" > "`$R/`$VC/.release.json.tmp"
chown $Owner "`$R/`$VC/.release.json.tmp"
mv "`$R/`$VC/.release.json.tmp" "`$R/`$VC/release.json"
# The uploaded copy stays in its private /tmp folder; the NAS clears /tmp on restart.
date -u +"staged `$VC at %Y-%m-%dT%H:%M:%SZ"
cat "`$R/`$VC/release.json"
docker exec $Container sh -c "sha256sum /data/releases/android/`$VC/`$APK" || echo "note: could not check the file inside the container"
"@
$stage = $stage -replace "`r`n", "`n"

if ($DryRun) {
    Step "Dry run: nothing uploaded, nothing staged"
    Write-Host "Would upload $apkName to the NAS ($incoming), then run:"
    Write-Host "----------------------------------------------------------------"
    Write-Host $stage
    Write-Host "----------------------------------------------------------------"
    Write-Host "Publish for real with the same file:"
    Write-Host "  ./scripts/release-android.ps1 -Apk `"$Apk`" -NasRunner <runner.py>"
    exit 0
}

# ---------------------------------------------------------------- upload and stage
if (-not $NasRunner -or -not (Test-Path $NasRunner)) { Fail "pass -NasRunner (or set VF_NAS_RUNNER)" }
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("vf-release-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $work | Out-Null
$uploader = Join-Path $work "upload.py"
$stageFile = Join-Path $work "stage.sh"
[System.IO.File]::WriteAllText($stageFile, $stage, (New-Object System.Text.UTF8Encoding($false)))
$py = @'
"""Streams one file to <dir> on the NAS over SSH. The runner supplies the host, user and password()."""
import hashlib, importlib.util, sys
from pathlib import Path
import paramiko
runner, src, remote_dir = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
spec = importlib.util.spec_from_file_location("nasrunner", str(runner))
nr = importlib.util.module_from_spec(spec); spec.loader.exec_module(nr)
if nr.LATCH.exists():
    sys.exit("refusing: the runner's auth-failure latch is set")
assert all(c.isalnum() or c in "._-" for c in src.name)
client = paramiko.SSHClient()
client.load_host_keys(str(Path.home() / ".ssh" / "known_hosts"))
client.set_missing_host_key_policy(paramiko.RejectPolicy())
client.connect(nr.HOST, username=nr.USER, password=nr.password(), timeout=20, look_for_keys=False, allow_agent=False)
stdin, stdout, stderr = client.exec_command(
    f"mkdir -p {remote_dir} && chmod 700 {remote_dir} && cat > {remote_dir}/{src.name} && sha256sum {remote_dir}/{src.name}")
digest = hashlib.sha256()
with open(src, "rb") as f:
    for block in iter(lambda: f.read(1 << 20), b""):
        digest.update(block)
        stdin.write(block)
stdin.channel.shutdown_write()
remote = stdout.read().decode().split()
client.close()
if not remote or remote[0] != digest.hexdigest():
    sys.exit("upload hash differs")
print("uploaded", src.name, digest.hexdigest())
'@
[System.IO.File]::WriteAllText($uploader, $py, (New-Object System.Text.UTF8Encoding($false)))

Step "Upload"
Invoke-Native "python" @($uploader, $NasRunner, $Apk, $incoming) -Echo | Out-Null
if ($script:NativeExit -ne 0) { Fail "upload failed" }
Step "Stage on the share (release.json last)"
Invoke-Native "python" @($NasRunner, $stageFile, "300") -Echo | Out-Null
if ($script:NativeExit -ne 0) { Fail "staging failed (the NAS output above says why)" }
Step "Staged"
Write-Host "Canary devices are offered $versionName ($versionCode) on their next check; everyone else after the 24-hour rule."
