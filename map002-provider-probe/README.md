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


`load_bench` is an experimental, one-shot measurement, not a map data interface. It reads only authorized primary-volume image IDs and queries the vivo Provider in batches of up to 20,000. The arrays are local to the run and capped at 100,000 IDs; `require_complete` fails closed if the currently visible total exceeds that limit. It does not establish media identity or protect a later UI against authorization races. Memory is sampled, not continuously profiled. For the controlled multi-batch measurement only, batches are separated by five seconds, and a 45-second cancellation request is scheduled; this is not a hard timeout or a recurring background strategy.

```powershell
adb shell am start -S -n org.example.map002probe/.MainActivity --ez no_request true --ez load_bench true --ei load_limit 2000 --ez compare_viewport true
```

`range_suite` also checks a small synthetic antimeridian boundary fixture on the device and records only aggregate counts for real-data ranges. The `released=method_exit` log means arrays are no longer intentionally retained by that method; it is not proof of immediate garbage collection. See the performance report for measured results and limitations.

`guard_lifecycle_test` runs synthetic cache fixtures and a foreground/background lifecycle diagnostic. `GuardedGeoStore` is experimental and requires a real authorization and identity verifier; it is not integrated with the loading benchmark and must not expose production locations. Foreground return reads the MediaStore visible count only; the count is not an authorization-set check.

`guard_live_test` is a 500-ID real-data lifecycle experiment. It rechecks the selected IDs in MediaStore and only retains locations in process memory, but it does not prove vendor identity, atomically bind permission state to provider reads, or authorize display in a product. `guard_live_hold` adds a one-time five-second diagnostic pause after the provider query.

`ViewportFilter` is a pure primitive-array experiment. It returns source indexes and total matches, including when the caller-supplied output array is too small. It is not a visibility, identity or lifetime guard; its output must not be exposed to a map before those checks are validated.

`media_lookup_test` exercises the internal `MediaStoreLookup` helper on one currently visible primary-volume image: metadata, an on-demand URI, a thumbnail, a missing ID, and cancellation. It never verifies vendor GPS identity, nor offers an atomically authorized production interface. `media_lookup_hold` is a one-time diagnostic pause for a lifecycle cancellation test.
