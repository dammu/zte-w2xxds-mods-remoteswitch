# Builds remoteswitch.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Run: pwsh -File build.ps1   (needs ANDROID_HOME and JAVA_HOME)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# Android SDK (build-tools;36.0.0 + platforms;android-36) and JDK 17, from ANDROID_HOME / JAVA_HOME
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not (Test-Path $sdk)) { throw "Set ANDROID_HOME to your Android SDK" }
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw "Set JAVA_HOME to a JDK 17" }
$bt = Join-Path $sdk 'build-tools\36.0.0'
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
foreach ($p in $bt, $androidJar) {
    if (-not (Test-Path $p)) { throw "Missing $p; install it with: sdkmanager `"build-tools;36.0.0`" `"platforms;android-36`"" }
}
$apiJar = "$root\libs\api-82.jar"   # Xposed API: compile-only, LSPosed provides it at runtime

# Debug signing key: not in the repository, created on the first build. Keep it: an APK signed with
# a different key cannot be installed over an existing install.
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $ks -storepass android -keypass android -alias clonelabel `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=w2xxds debug" | Out-Null
    Write-Host "created debug signing key $ks"
}
$out = "$root\build"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\gen" | Out-Null

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest "$root\AndroidManifest.xml" `
    -A "$root\assets" --java "$out\gen" --min-sdk-version 28 --target-sdk-version 33 --no-version-vectors "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$sources = Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName
& "$jdk\bin\javac.exe" -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -Xlint:-options `
    -bootclasspath $androidJar -classpath $apiJar -d "$out\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# one jar instead of every .class path: d8.bat runs through cmd.exe, whose 8191-char line limit the list outgrows
& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --min-api 28 --lib $androidJar --classpath $apiJar --output $out "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Push-Location $out
& "$jdk\bin\jar.exe" uf base.apk classes.dex
Pop-Location
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --ks-key-alias clonelabel `
    --out "$root\remoteswitch.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "built $root\remoteswitch.apk"
