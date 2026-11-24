# MAP-002 Provider probe

This is a narrow experimental Android app, not the MAP-002 data layer. It uses no Internet permission. It requests photo and media-location permissions for the standard-read experiment, but queries and opens only its own generated test image. It first queries the vendor provider with an empty selection. The current version then creates one synthetic JPEG with known GPS, inserts it into MediaStore, queries only the corresponding ID, logs boolean comparisons, and deletes the synthetic image and temporary file in a `finally` block. If the process is killed before cleanup, remove the synthetic `MAP002_SYNTHETIC_TEST.jpg` photo manually.

Build with JDK, Android SDK platform 34, build-tools 34.0.0, and network access to Google Maven. The build downloads AndroidX ExifInterface 1.3.7 and verifies its SHA-256:

```powershell
.\map002-provider-probe\build.ps1 -Sdk 'D:\path\to\Android\Sdk'
adb install -r .\map002-provider-probe\build\probe.apk
adb shell am start -n org.example.map002probe/.MainActivity
adb logcat -d -s MAP002Probe:I '*:S'
```

The script generates an ignored local signing key and APK. This probe is for API 29 or newer because the synthetic test uses scoped storage. Do not use its target SDK, signing key, or vendor provider as a production recommendation. The synthetic JPEG and SQLite tests have been run on the connected X300 Pro; see `docs/research/map002-provider-test.md`, `docs/research/map002-standard-read-test.md`, and `docs/research/map002-index-performance.md`.
