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

## Full binary dump (RAW)

Select "Начать полный RAW-дамп" before connecting, choose 1/4/8 GiB. This mode records successful application-level reads/writes at the USB accessory file descriptor (all existing USB modes, including QDLink and probes), accepted Wi-Fi Direct TCP channels and the hello exchange. Includes all payload bytes exposed to these I/O calls, including media, unknown messages and framing; no per-packet clipping or sampling. Separate stream UUIDs distinguish reconnects. The structured log remains an additional, limited companion.

Record only authorized sessions. Full payloads may contain screen/video, voice, identifiers and other private data. Files are in private app storage. Export explicitly via the system document picker. No auto-upload. Delete saved captures from the diagnostics screen.

The selected session limit and a 512 MiB storage reserve are enforced; the asynchronous writer queue is bounded to 16 MiB / 16384 records. On capacity or I/O failure capture stops and is marked incomplete rather than silently sampling. A process kill may leave an unfinalized manifest and truncated tail. A clean user stop proves completeness only for bytes observed at instrumented calls during that interval, not for all physical bus packets, firmware/CAN internals or data sent by other apps. TCP reads/writes are chunks, not network packets; USB AOA streams do not include low-level USB setup transactions. TX records represent successful write calls, not confirmed delivery.

Export ZIP: `manifest.json`, `traffic.bin`. Export ordinary diagnostic logs separately for decoded context. Decode on PC:

```sh
python tools/decode_hu_dump.py AA-HU-....zip extracted
python -m unittest discover -s tools -p "test_decode_hu_dump.py"
```

Outputs per-stream RX/TX binary files, `timeline.jsonl` with offsets and manual marks, and `summary.json`. The decoder exits 2 for incomplete/unverified captures but retains readable records. Binary format (big endian): ASCII AHUDUMP1, int64 epoch milliseconds, then records int64 sequence, int64 elapsed nanoseconds, uint8 direction (1 RX, 2 TX, 3 marker, 4 metadata), int32 UTF-8 stream name length, int32 payload length, stream name bytes and payload. Capture timestamps are taken after the I/O call returns, not at the bus.

Not included: original CarbitLink application's simultaneous USB traffic, Bluetooth HCI, Wi-Fi management frames, internal CAN traffic, Android Auto localhost TLS connection, or hardware signals the HU does not publish. Those require different capture points or authorized external equipment. USB ownership is exclusive.
