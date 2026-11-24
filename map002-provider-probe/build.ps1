param([string]$Sdk = 'D:\Users\28288\AppData\Local')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$buildRoot = Join-Path $root 'build'
$build = Join-Path $buildRoot ([guid]::NewGuid().ToString('N'))
$tools = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
& "$tools\aapt2.exe" link --manifest "$root\AndroidManifest.xml" -I $androidJar -o "$build\unsigned.apk"
Check-Exit
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d "$build\classes" $sources
Check-Exit
$classFiles = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$tools\d8.bat" --lib $androidJar --min-api 29 --output "$build\dex" $classFiles
Check-Exit
Push-Location "$build\dex"
try { & "$tools\aapt.exe" add "$build\unsigned.apk" classes.dex; Check-Exit } finally { Pop-Location }
& "$tools\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
Check-Exit
$key = Join-Path $buildRoot 'probe.keystore'
if (!(Test-Path $key)) { & (Join-Path ((& java -XshowSettings:properties -version 2>&1 | Select-String "java.home =").ToString().Split("=")[1].Trim()) "bin\keytool.exe") -genkeypair -keystore $key -storepass android -keypass android -alias probe -dname 'CN=MAP002 local probe' -keyalg RSA -validity 3650; Check-Exit }
& "$tools\apksigner.bat" sign --ks $key --ks-pass pass:android --key-pass pass:android --out "$buildRoot\readonly-probe.apk" "$build\aligned.apk"
Check-Exit
& "$tools\apksigner.bat" verify "$buildRoot\readonly-probe.apk"
Check-Exit
Write-Output "Built $buildRoot\readonly-probe.apk"
