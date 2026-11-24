param([string]$Sdk = 'D:\Users\28288\AppData\Local')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$build = Join-Path $root 'build'
$tools = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null
$aar = Join-Path $build 'deps\exifinterface-1.3.7.aar'
$depdir = Join-Path $build 'deps\exif'
New-Item -ItemType Directory -Force -Path (Split-Path $aar) | Out-Null
if (!(Test-Path $aar)) { Invoke-WebRequest 'https://dl.google.com/dl/android/maven2/androidx/exifinterface/exifinterface/1.3.7/exifinterface-1.3.7.aar' -OutFile $aar }
if ((Get-FileHash $aar -Algorithm SHA256).Hash -ne '0E8F1832266C5B0667AD3D3B1098E624E49A09075493A014A7E88AF01FD30AD3') { throw 'AndroidX EXIF dependency checksum mismatch' }
Expand-Archive -LiteralPath $aar -DestinationPath $depdir -Force
$exifJar = Join-Path $depdir 'classes.jar'
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
& "$tools\aapt2.exe" link --manifest "$root\AndroidManifest.xml" -I $androidJar -o "$build\unsigned.apk"
Check-Exit
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -classpath $exifJar -d "$build\classes" $sources
Check-Exit
$classFiles = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$tools\d8.bat" --lib $androidJar --min-api 29 --output "$build\dex" $classFiles $exifJar
Check-Exit
# Insert classes.dex at the archive root.
Push-Location "$build\dex"
try { & "$tools\aapt.exe" add "$build\unsigned.apk" classes.dex; Check-Exit } finally { Pop-Location }
& "$tools\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
Check-Exit
$key = Join-Path $build 'probe.keystore'
if (!(Test-Path $key)) { & (Join-Path ((& java -XshowSettings:properties -version 2>&1 | Select-String "java.home =").ToString().Split("=")[1].Trim()) "bin\keytool.exe") -genkeypair -keystore $key -storepass android -keypass android -alias probe -dname 'CN=MAP002 local probe' -keyalg RSA -validity 3650; Check-Exit }
& "$tools\apksigner.bat" sign --ks $key --ks-pass pass:android --key-pass pass:android --out "$build\probe.apk" "$build\aligned.apk"
Check-Exit
& "$tools\apksigner.bat" verify "$build\probe.apk"
Check-Exit
Write-Output "Built $build\probe.apk"
