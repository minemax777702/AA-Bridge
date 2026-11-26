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

## Sequential wizard and rotation A/B

Start a full RAW capture before connecting; once connected, open "Пошаговый мастер диагностики". Begin the baseline test, then answer Да / Нет / Не уверен / Пропустить. Each answer closes its observation window and opens the next automatically if prerequisites are met. For Android media output with no BT, disable Bluetooth audio; for BT media, connect only the car's media output. The wizard requests the selected device on an isolated AudioTrack and records the actual routed device; it cannot switch Bluetooth on/off or guarantee Android honors a route. PXC profiles run sequentially without permanent overrides. After timeout reconnect before the next PXC test. All signals are short and quiet; set HU volume low. Manual vehicle observations remain manual and must be safe while parked. No new vehicle commands.

The RAW capture includes `gesture/hu-input` (original and scaled coordinates), `gesture/aa-input-sent` (action, actionIndex, all pointer IDs and coordinates after successful local write) and `gesture/aa-input-filtered` (multi-finger events dropped by the standard 30 ms limiter). Repeat zoom and rotation on the same map. The special `map_rotate_full` test temporarily bypasses the move limiter for two or more pointers only; all other input behavior is unchanged. It is an A/B diagnostic, not a confirmed fix. Compare the two rotation results and event streams. A map app may itself restrict rotation.

Answers, observed routes and transport result are recorded in capabilities.json under manualTests[].wizardEvidence and in RAW timeline. A user saying yes without verified audio routing is recorded as inconclusive. Back exits the wizard; completed results stay in the current capture, but restarting the wizard starts at the first question. The final button stops recording; export ZIP from diagnostics.


## Live HU parameters and change tracking

Open Diagnostics → Параметры ГУ в реальном времени after connecting. No RAW recording or wizard is required for the live table. All data comes from existing incoming PXC, mirror control and QDLink messages. The parameter table separates verified field semantics (e.g. night boolean) from announced capabilities (e.g. supportMic), raw data and unknown vehicle signals. Do not infer ignition, door, headlight or CAN state from a connection, an empty packet, phone-side GPS, or a user answer.

The other tabs show bounded JSON field paths, packet counts and last receipt, and recent changes with timestamp, source, old/new values and byte offsets for unknown binary messages. Mark an action to correlate it with packet changes. The last 2,000 changes are kept in process memory across reconnects; current fields reset on a new connection. Export JSON before closing the app. Unchanged repeat messages update receipt time but do not create field changes. Binary comparison uses aligned offsets (not inferred signal encoding), only up to 4096-byte bodies, with a limited diff preview. Media bodies and common mirror touch/heartbeat traffic are not interpreted as telemetry.

For a complete session export full RAW separately. The ZIP includes live-parameters.json (snapshot taken at finalization, bounded in-memory history may include earlier connections) and live/change metadata for changes observed during the RAW capture. The original traffic remains authoritative; snapshot may extend beyond an automatic capture cutoff. No new network requests, vehicle commands or CAN access are introduced.


## Emgrand SS11 — experimental Instrument receiver

Actual vehicle: Geely Emgrand IV (SS11), 2024 Comfort, 8-inch HU, 6AT, firmware 4.55_20240401. CLIENT_INFO reports EC-RJ-2401 and carModel SX11; the latter is not reliable vehicle identification.

Evidence: supplied CarbitLink 6.5.1 APK and 6.6.2 build 576 XAPK, arm64 libEPSDK.so identical (SHA256 5b22221ca165d0116440349afd2bb758feeabb017a8059b6c904f9a761ebbb03). No vendor code/binaries stored in this repo.
- CMPServer constructor 0x1c425c: TCP port 10926.
- CMPServer::handlePXCClient 0x1c4aa0: initial 16-byte header, empty reply cmd+1, extra=0.
- MIServer::makePXCService 0x1c8158: 0x01060000 C2P, 0x01070000 P2C.
- C2PMInstService::handleCMD 0x1c9794: config 0x01060010, MXC 0x01060020, overspeed configuration 0x01060040. Config ACK 0x01060011 echoes extra and has 20-byte NUL-padded ASCII 1.0.0. C2P heartbeat ACK echoes extra; P2C heartLoop at 0x1c3c7c sends 0x70000000 and waits for 0x70000001 before repeating.
- EPMInst::parseMXCBaseInfo 0x1c4ea4 forwards bodies to mcu_parser 0x1c680c. Nested sync 53 CA, little-endian length plus 4 (normal frames), type at offset 4, CRC16 polynomial 0x1021 seed 0, little-endian CRC at end. CRC checked before decode. Kotlin receiver bounds outer frames to 64 KiB and nested frames to 4096 bytes.
- Type 1: speed, gear, RPM, temperatures, fuel, 44 light indicators and their validity flags, drive mode, backlight, ignition. Type 2: odometer, trip, range and selected trip metrics. Enum IDs cross-checked against classes3.dex EPMInstDataEnum. All numeric fields are SDK raw values (temperature offsets from native parser applied); units/scales and gear/drive-mode enums are not confirmed on SS11. Flag=0 means unavailable, not zero. Synthetic test fixtures are not real vehicle data.
- PXC 0x10460 reversing 4112/4113: original Java handler net.easyconn.carman.ec.b$b.n in classes7.dex pauses/resumes music on these codes respectively. Bluetooth/drivingMode remain raw codes; the latter is an application mode, not confirmed Eco/Sport/Comfort.
- Tyre parser exists (0x1c4f04), but no verified dispatch in C2PMInstService; no invented tyre mapping. Door, actual climate, parking sensors and CAN remain unconfirmed.

Diagnostics build only: SocketTransport listens on 10926 in addition to normal ports, accepts only existing Wi-Fi Direct interface policy, caps Instrument sockets to four and uses 15s read timeout. USB handler accepts a peer-opened tcp:10926 ADB stream experimentally (not verified that HU opens it). No scans or outbound connections. Only known handshake/config/heartbeat messages are sent; no vehicle commands. An Instrument disconnect does not stop the main projection. No capability spoofing or active MI discovery command is claimed.

Open live parameters after connecting; check Instrument 10926 status. Start full RAW before connection if exporting a complete experiment. Live JSON includes raw values, validity, timestamps and last nonempty previous session if reconnect occurred. The table flags Instrument readings older than 10 seconds or without active telemetry as stale. If there is no Instrument connection, the feature is still unconfirmed on this firmware. No original dump or identifiers published.
