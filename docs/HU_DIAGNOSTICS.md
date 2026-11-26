# AA-Bridge Diagnostics

Separate installable build type, package `io.github.minemax777702.androidautobridge.diagnostics`.
The regular debug/release builds keep their application IDs and do not enable the capture hooks.

## Build

JDK 17, existing Android SDK and Gradle wrapper:

```sh
./gradlew :app:assembleDiagnostics
```

Windows: `gradlew.bat :app:assembleDiagnostics`.
APK: `app/build/outputs/apk/diagnostics/app-diagnostics.apk` (debug signing).

## Test

1. Disconnect the regular bridge/official CarbitLink. The accessory and Wi-Fi Direct ports cannot be shared simultaneously.
2. Open the diagnostic APK (the diagnostics screen opens on launch), or go to Settings → Диагностика ГУ и автомобиля. Start capture before connecting. Return to the bridge and use its normal USB or wireless flow. Android Auto setup is still required for projection.
3. With the car stationary, mark a test, perform the action, mark the result; repeat for each available function. For a new vehicle state, repeat the same action to correlate events.
4. Stop the capture and export all logs as ZIP. Review before sending. No external upload is implemented.

## Evidence and scope

PXC parsed RX and constructed TX frames: IDs, extra, length, stream ID when available, up to 4096 control bytes in hex. Known audio/microphone payloads excluded from the new trace.
ADB RX and constructed TX packet metadata (high-volume WRTE/OKAY headers sampled, totals in STOP); QDLink JSON, legacy control blocks and A6A6 messages; incoming mirror-control chunks over USB/Wi-Fi Direct, connection details via existing logs; touch/key events; USB and audio device inventory, connection snapshots.
Existing diagnostic logs are included, not just the current session. The structured trace has a session UUID, sequence, elapsed time, explicit truncation and dropped-event summary (50,000 events / about 24 MiB per session).
A TX_BUILT event is not proof of delivery. This does not record other applications' traffic or full media streams. Unknown binary control payloads may include private identifiers; existing logs may contain sensitive data as well.
No arbitrary vehicle-control commands, CAN injection, port scanning, or simulation of real vehicle sensor values are added.

Parking sensors, gear, speed, doors, etc. are only identifiable if the HU actually exposes relevant messages. A manual marker is not a sensor reading.
A full passive comparison with official CarbitLink requires a separate authorized capture (e.g. external USB analyzer); ordinary unprivileged Android applications cannot read another app's USB/TLS streams. This is a first instrumentation build, not proof of vehicle compatibility.
