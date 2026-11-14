param(
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) {$env:ANDROID_SDK_ROOT} elseif ($env:ANDROID_HOME) {$env:ANDROID_HOME} else {"$env:LOCALAPPDATA/Android/Sdk"}),
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory=$true)][string]$XposedApiPath
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $JavaHome) {throw 'Set JAVA_HOME or pass -JavaHome (JDK 21 required).'}
$sdkPath = [IO.Path]::GetFullPath($SdkRoot)
$env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
$xposedApi = (Resolve-Path -LiteralPath $XposedApiPath).Path
if ((Get-FileHash -LiteralPath $xposedApi -Algorithm SHA256).Hash -ne 'F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25') {throw 'Xposed API 82 SHA256 mismatch.'}
$platformPath = Get-ChildItem "$sdkPath/platforms" -Directory | Where-Object {Test-Path "$($_.FullName)/android.jar"} | Sort-Object {[version](($_.Name -replace '^android-','') + '.0')} -Descending | Select-Object -First 1
$toolsPath = Get-ChildItem "$sdkPath/build-tools" -Directory | Where-Object {$_.Name -match '^\d+\.\d+\.\d+$'} | Sort-Object {[version]$_.Name} -Descending | Select-Object -First 1
if (-not $platformPath -or -not $toolsPath) {throw 'Install Android platform 35+ and Build Tools 35+.'}
$androidJar = Join-Path $platformPath.FullName 'android.jar'
$buildTools = $toolsPath.FullName
if ($root -match '[^\x00-\x7F]') {throw 'Place this repository in an ASCII-only path for Windows aapt2.'}
$buildPath = Join-Path $root 'build'
$devKeystore = Join-Path $root '.cache/development.keystore'
New-Item -ItemType Directory -Force (Split-Path $devKeystore) | Out-Null
if (-not (Test-Path -LiteralPath $devKeystore)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $devKeystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=YOYOBackground Development'
    if ($LASTEXITCODE -ne 0) {throw 'Development signing-key generation failed.'}
}
New-Item -ItemType Directory -Force "$buildPath/src", "$buildPath/classes", "$buildPath/dex", "$buildPath/res", "$buildPath/flat", "$buildPath/assets" | Out-Null
Copy-Item "$root/module/src/*" "$buildPath/src" -Recurse -Force
Copy-Item "$root/module/res/*" "$buildPath/res" -Recurse -Force
Copy-Item "$root/module/assets/*" "$buildPath/assets" -Recurse -Force
Copy-Item "$root/module/AndroidManifest.xml" "$buildPath/AndroidManifest.xml" -Force
function Check-Native { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
$sourceFiles = Get-ChildItem "$buildPath/src" -Recurse -Filter '*.java' | ForEach-Object {$_.FullName}
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 17 -target 17 -classpath "$androidJar;$xposedApi" -d "$buildPath/classes" $sourceFiles
Check-Native
& "$buildTools/aapt2.exe" compile --dir "$buildPath/res" -o "$buildPath/flat"
Check-Native
$compiledRes = Get-ChildItem "$buildPath/flat" -Filter '*.flat' | ForEach-Object {$_.FullName}
& "$buildTools/aapt2.exe" link -I "$androidJar" --manifest "$buildPath/AndroidManifest.xml" -A "$buildPath/assets" -o "$buildPath/unsigned.apk" $compiledRes
Check-Native
$classFiles = Get-ChildItem "$buildPath/classes" -Recurse -Filter '*.class' | ForEach-Object {$_.FullName}
& "$buildTools/d8.bat" --lib "$androidJar" --lib $xposedApi --output "$buildPath/dex" $classFiles
Check-Native
& "$env:JAVA_HOME/bin/jar.exe" uf "$buildPath/unsigned.apk" -C "$buildPath/dex" classes.dex
Check-Native
& "$buildTools/zipalign.exe" -f 4 "$buildPath/unsigned.apk" "$buildPath/aligned.apk"
Check-Native
& "$buildTools/apksigner.bat" sign --ks "$devKeystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$buildPath/signed.apk" "$buildPath/aligned.apk"
Check-Native
& "$buildTools/apksigner.bat" verify "$buildPath/signed.apk"
Check-Native
Copy-Item "$buildPath/signed.apk" "$buildPath/YOYOBackground-v0.5.apk" -Force
Write-Output "Built and signature-verified: $buildPath/YOYOBackground-v0.5.apk"


