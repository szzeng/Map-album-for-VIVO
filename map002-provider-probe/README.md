# MAP-002 V3.1 read-only probe

This experimental probe requests image permission, samples at most 20,000 currently visible IDs (20 by default) from `external_primary`, and performs one bounded read-only vendor query using those IDs. If more than one external volume is detected, it stops. Only aggregates and exception types are logged: no IDs, coordinates, paths or image content. It does not open original photos, read EXIF, write media, or maintain a GPS database. An optional test reads one authorized thumbnail. The Provider ID-to-volume relationship is not yet proven; samples cannot be used as a production association.

```powershell
.\map002-provider-probe\build.ps1 -Sdk 'D:\path\to\Android\Sdk'
adb install -r .\map002-provider-probe\build\readonly-probe.apk
adb shell am start -S -n org.example.map002probe/.MainActivity --ei sample_limit 500 --ez range_test true --ez thumb_test true
adb logcat -d -s MAP002ReadOnly:I '*:S'
```

A single active query uses a cancellation signal, an eight-second cancellation request for shell-triggered runs (ten seconds for Activity runs), and closes cursors. A non-cooperating provider may ignore cancellation; this is not a hard timeout guarantee. The build uses a fresh intermediate directory, so historical GPS/EXIF code is not accidentally packaged. The ignored local signing key remains the same for updates. No network access is required to build.

Prior test records are historical only. See `docs/research/map002-provider-readonly-test.md` for scope and open questions.

For a one-shot background query while the native gallery remains in front (ADB shell holds the receiver's `DUMP` permission):

```powershell
adb shell am broadcast --receiver-foreground -n org.example.map002probe/.ProbeReceiver --ei sample_limit 2000 --ei sample_offset 20000
```

Large, separated batches are for controlled validation only, not a continuous polling strategy. The four-batch coverage result is not a transactional snapshot.

The in-memory `EphemeralGeoCache` is an isolated prototype; it is not wired to a production identity/authorization validator. Its synthetic tests run on the host JDK:

```powershell
javac -encoding UTF-8 -d map002-provider-probe\build\cache-tests map002-provider-probe\src\org\example\map002probe\EphemeralGeoCache.java map002-provider-probe\tests\org\example\map002probe\EphemeralGeoCacheTest.java
java -cp map002-provider-probe\build\cache-tests org.example.map002probe.EphemeralGeoCacheTest
```
