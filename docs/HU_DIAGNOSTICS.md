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

Export ZIP: `manifest.json`, `capabilities.json`, `traffic.bin`. Export ordinary diagnostic logs separately for decoded context. Decode on PC:

```sh
python tools/decode_hu_dump.py AA-HU-....zip extracted
python -m unittest discover -s tools -p "test_decode_hu_dump.py"
```

Outputs per-stream RX/TX binary files, `timeline.jsonl` with offsets and manual marks, and `summary.json`. The decoder exits 2 for incomplete/unverified captures but retains readable records. Binary format (big endian): ASCII AHUDUMP1, int64 epoch milliseconds, then records int64 sequence, int64 elapsed nanoseconds, uint8 direction (1 RX, 2 TX, 3 marker, 4 metadata), int32 UTF-8 stream name length, int32 payload length, stream name bytes and payload. Capture timestamps are taken after the I/O call returns, not at the bus.

Not included: original CarbitLink application's simultaneous USB traffic, Bluetooth HCI, Wi-Fi management frames, internal CAN traffic, hardware signals the HU does not publish. Those require different capture points or authorized external equipment. USB ownership is exclusive.

## Capability survey and comparison

Start full RAW, then choose the guided scenario "Фоновый обмен" first. Run each subsequent test separately, repeat when safe, and mark the observed result. Use the normal Android Auto functions for active checks, including audio, microphone, focus and connection heartbeat. No new guessed vehicle commands, arbitrary command sender, brute force, or vehicle actuation are provided. Existing GPS command 0x10430 is a request for phone GPS, NOT a vehicle sensor feed.

The live survey inventories packet IDs, directions, counts, lengths, sampled non-media payload hashes and declared PXC/QDLink/mirror fields. Each scenario records a result, duration, changes vs idle and candidate byte offsets in bounded aligned prefixes of parsed RX control messages. Offsets are hypotheses: time counters, traffic variability, and prefix truncation may explain them. No unsupported classification is inferred from silence. A user-observed result is separate from protocol evidence. For best coverage repeat the same scenario in independent captures.

The RAW dump also contains a distinct android-auto-local stream (wire framing including TLS records, not decrypted media) and AA-local message metadata. This is phone-side evidence and cannot establish what the actual HU supports. Wi-Fi Direct discovery and mDNS metadata are recorded from existing discovery callbacks; no new external network requests. RAW now includes more traffic and may reach its storage limit sooner.

The report is bounded (2048 packet kinds, 256 declared fields, 200 completed tests, 100 candidates per test). It is a companion to RAW, not exhaustive; if RAW stops automatically the survey snapshot may include nearby events after the RAW cutoff. Keep individual captures short and stop explicitly. Export a full dump for the capabilities file; ordinary text logs are separate.

On PC:

```sh
python tools/analyze_hu_capabilities.py capture1.zip capture2.zip -o report.json
python -m unittest discover -s tools -p "test_*hu*.py"
```

The analyzer distinguishes user-observed working functions, advertised fields, observed known commands and unknown candidates. It highlights candidates that recur in multiple captures. Run the raw decoder separately to check completeness. It does not assign unknown vehicle signals or decode proprietary firmware automatically.

## SX11 Comfort targeted survey (EC-RJ-2401)

Observed in an authorized capture: PXC 1.0.2, SDK 0.9.24, JSBD17_0.9.24, reported platform linux_no_package, supportFunction 191, supportConnect 842. These bitmasks have no verified mapping yet. The user's equipment level is Comfort; the advertised voice catalog is not an equipment list. Do not publish the original capture or identifiers.

Observed incoming PXC JSON: 0x10200 night (false observed), 0x10040 navigation (maxNaviIcon 57, supportFunction 0), 0x10690 link (usbSpeed 2048, wifiSpeed 0; units unknown), 0x10470 voice catalog (29660 bytes). Complete bounded JSON is now exported under pxcJson in capabilities.json and changes in traffic.bin. No vehicle control execution is added.

New scenarios observe factory settings, drive mode, cluster brightness, lighting, climate, native voice, radio, cluster navigation and media keys. Only use normal factory controls safely while parked; no forced actuation or CAN injection. A silent phone interface is not proof that the HU lacks vehicle integration.

For direct audio: begin RAW before connecting; stop ordinary music/voice, disable Bluetooth audio, reduce HU volume and explicitly select one documented PXC audio profile. The isolated diagnostic transaction may try the profile even when the HU advertised false. A quiet one-second tone is sent only after start ACK; stop is requested in finally. An ambiguous timeout requires reconnect. Report actual audibility separately: ACK and queued bytes do not prove playback. No automatic retries or format guessing. No persistent capability override.

References (not a claim of exact firmware compatibility):
- Geely SX11 multimedia manual: https://www.geely-motors.com/usermanual-model-10-coolray_sx11-expl-multi_14032993628816372135.pdf
- EasyConnection developer overview: https://h5.carbit.cn/developer
- ScanDoc SX11 multimedia diagnostic overview: https://scandoc.online/last/0/84/22/9
- Owner forum: https://4pda.to/forum/index.php?showtopic=1001500
Different generations and firmware are not interchangeable.
