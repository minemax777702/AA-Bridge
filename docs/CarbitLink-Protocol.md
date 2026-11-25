# CarbitLink Protocol Notes

Consolidated, evidence-sourced notes on how a CarbitLink dongle (and the
real `net.easyconn` head-unit app) talk to a phone. CarAALink impersonates
the **head unit** side of this protocol so a phone plugs straight into the
dongle (and eventually the car's own head unit) without the dongle's own
phone-side software.

Every fact below cites where it came from. Nothing here is guessed -- if
we don't have a source, it's marked `UNKNOWN`. See `CLAUDE.md` for why
that rule exists.

## Sources

- **Our own code / real captures**: CarAALink's own `AdbTransport.kt`,
  `CarSession.kt`, and real logcat captures taken from a phone running
  CarAALink talking to a real CarbitLink dongle (`CarbitDriverOversea`).
- **`net.easyconn` decompile**: `EasyConnection.apk`, pulled directly off
  a real Jancar/AutoChips head unit (2026-11-04/05), decompiled with
  `jadx`. This is CarbitLink's *own* real head-unit app -- the thing
  CarAALink is reimplementing. Package `net.easyconn.ecsdk` holds its
  public SDK type system (`ECTypes.java`); these are the IVI app's own
  internal API enums, not necessarily the literal over-the-wire byte
  values (those are confirmed separately, from real captures).
- **Head-unit logcat** (`Reverse engineering Claude/*.txt`): logcat/shell
  dumps captured on that same real head unit while it was actually
  mirroring, over both USB-AOA and `EC_TRANSPORT_ANDROID_WIFI`.
- **BLECarbitlink** (`docs/protocol.md` in that external repo, GPL-3.0):
  cross-checked for the generic ADB-over-AOA transport layer. Facts
  paraphrased here, not copied.

## Layer 1: USB transport (AOA)

The dongle enumerates as an Android Open Accessory device
(`manufacturer=Carbit`, `model=CarbitDriverOversea`). CarAALink talks to
it the same way a real phone would:

- Standard AOA handshake, then a **zero-kick**: the host writes an empty
  packet to bulk-OUT before the device will send `CNXN`. Confirmed
  required in practice (`AdbTransport.kt`'s zero-kick-then-wait loop);
  matches BLECarbitlink's documented behavior for the same dongle family.
- After `CNXN`, this is the standard ADB stream protocol: `OPEN`/`OKAY`/
  `WRTE`/`CLSE`, 24-byte headers (`command/arg0/arg1/data_length/
  data_check/magic`), `maxData` negotiated from the `CNXN` payload.
  CarAALink's own implementation already matches BLECarbitlink's
  documented header/checksum facts -- no mismatch found when compared
  directly.
- Real capture: `CNXN from head unit v=0x1000000 maxdata=16384
  'host::tX9U '`. EIO (`write failed: EIO` / `ENODEV`) on this transport
  is a real, recurring, *unresolved* instability -- confirmed independent
  of app-side encoder choice (toggled hardware vs software H.264, same
  EIO behavior either way). Not yet root-caused.

## Layer 2: ADB streams used

Once `CNXN` completes, CarbitLink opens several `OPEN` streams over that
same AOA connection, each a distinct logical channel (confirmed from a
real capture):

| Stream (destination) | Purpose |
|---|---|
| `tcp:10922` (×2 opens) | Control/handshake JSON (PXC) + a second control channel |
| `tcp:10921` | `MIRROR ctrl` -- binary control messages for the Mirror channel |
| `tcp:10920` | `MIRROR` data stream -- the actual encoded video, opened once Mirror is approved |

(Port numbers are real, taken from our own capture; CarbitLink may use
others in other configurations -- not verified beyond what we've seen.)

## Layer 3: PXC control channel (JSON)

Over the `tcp:10922` streams, CarbitLink (dongle) and CarAALink (acting
as head unit) exchange small framed JSON payloads. Real examples from a
capture, each prefixed with a message code (`0xXXXXXXXX`) we have not
fully enumerated:

```
0x00010000  PXC channel open
0x00010010  C2P {"HUID":"...", "HUName":"Android_6f01", "bluetoothPolicy":0,
             "btAddress":"", "btName":"", "btPin":"", "carBrand":"...",
             "carConfig":"...", "channel":"37204", "currentHUTime":...}
0x00010690  C2P {"usbSpeed":2048,"wifiSpeed":0}
0x000103A0  C2P {"port":11021,"pwd":"...","userName":"carbit_ec_ftp_ota"}
             -- an FTP-ish credential exchange; purpose beyond that UNKNOWN
0x000103E0  C2P {"client_set":"easy_conn","sn":"..."}
0x00010020  C2P {"autoChangeToBT":true,"music":false,"talkie":false,
             "tts":false,"vr":false}
0x00010040  C2P {"maxNaviIcon":57,"supportFunction":0}
0x00010370  C2P {"key":"content","value":"<long opaque blob>"}
             -- looks encrypted/obfuscated, not JSON; purpose UNKNOWN
0x00010400  C2P {"address":"","name":"","pin":""}       -- Bluetooth info
0x00010450  C2P (empty body)
0x00010460  C2P {"bluetooth":4130}
```

These field names line up with `net.easyconn.ecsdk.ECTypes` (decompiled):
`ECCarDescription` has `carBrand`/`carModel`/`carConfig`/`btName`/
`btAddress`/`btPin`/`dpi`; `ECBTServiceTransportType`, `ECCarStatusType`
etc. match the shapes above. The exact numeric message codes are
confirmed from our own capture; which SDK call produces which code is
**not** independently confirmed against the decompile (we matched by
field shape, not by tracing the dispatch code) -- treat that mapping as
likely, not proven.

## Layer 4: Mirror video channel

### Negotiation

The dongle sends a `MIRROR ctrl 0x0010` binary message, then a
`CONFIG_CAPTURE` describing what it wants, e.g. (real capture):

```
CaptureConfig(width=1920, height=720, wantFps=0, wantEncoder=2,
              bitRate=4194304, orientation=1, displayId=0,
              supportExtendProtocol=0)
```

- `wantFps=0` means **pull mode**: the car blocks on a frame-request
  call instead of us pushing frames on a timer. (`wantFps>0` would be
  push mode -- CarAALink supports both; real captures so far are all
  pull mode.)
- `wantEncoder` is presumably `ECVideoType` from the decompile
  (`H264=0, MPEG4=1, JPEG=2, MAX=3`) but **`wantEncoder=2` doesn't match
  `EC_VIDEO_TYPE_JPEG`'s value 2** in every capture we have, and the
  stream is H.264 either way -- so `wantEncoder` is likely a different
  enum (possibly a HW/SW encoder selector rather than codec type).
  **UNKNOWN**, not confirmed.
- `CONFIG_CAPTURE` can be resent mid-session with no actual change
  (confirmed from a real log, ~9s apart, nothing touched on either end)
  -- CarAALink must not rebuild the encoder/surface on a no-op repeat of
  this message, or it stomps the live buffer queue and looks like a
  glitch (this was a real, fixed bug).
- CarAALink replies by opening the Mirror data stream and sending codec
  config (SPS/PPS) immediately before the first real frame.

### Pull-mode frame delivery

Once the data stream is open, the car issues frame-request calls that
**block until a frame is available** -- it does not poll with "none yet"
responses (confirmed from the existing pull-queue design and matches the
observed request/response pairing in every real capture).

**Measured pull cadence** (real capture, 2026-11-06, ~134s session):
frame requests arrived at **~75-79/s**, dramatically faster than any
reasonable on-screen content rate. Over that session: `requests=9899
sent=9899 encoded=4373` -- every request got answered, but **more than
half of everything sent was a repeat of already-sent data**, not a fresh
encode, because the encoder was targeting 30fps while the car pulled far
faster than that. This is the measured, log-confirmed mechanism behind
the "TV lag / repeats motion" symptom -- not a corruption bug, a pacing
mismatch. Whether the *real* phone-side CarbitLink app also pulls at
this rate, or whether a real phone's encoder simply runs faster and
rarely needs the repeat path, is **UNKNOWN** -- we don't have a capture
of the real phone app's own encode cadence to compare against.

### Starvation fallback

If the encoder has nothing queued when a request comes in, CarAALink
waits up to 80ms before falling back to resending the most recently sent
frame, so the car never times out waiting (`CarSession.kt`,
`scheduleStarvationCheck`). This is **our own design**, not a documented
CarbitLink requirement -- we don't have confirmation of what timeout, if
any, the real protocol actually enforces before this is needed.

## Layer 5: Touch / input (not yet mapped to CarbitLink Mirror)

The decompile confirms `net.easyconn`'s own touch model:

```
ECTouchEventType: EC_TOUCH_UP(0), EC_TOUCH_DOWN(1), EC_TOUCH_MOVE(2)
ECTouchMode:      EC_TOUCH_MODE_SINGLE_TOUCH(0), EC_TOUCH_MODE_MULTI_TOUCH(1),
                  EC_TOUCH_MODE_NOT_SUPPORT_TOUCH(2)
ECTouchEventData: { pointX, pointY, slot }
```

CarAALink's own Mirror touch handling exists (see `CarSession.kt`) but
has **not** been cross-checked against these decompiled shapes in this
pass -- flagged as a follow-up, not done here.

## Layer 6: Transport enum (confirms Layer 1/2 choice)

```
ECTransportType: EC_TRANSPORT_ANDROID_USB_ADB(0), EC_TRANSPORT_ANDORID_USB_AOA(1),
                 EC_TRANSPORT_ANDROID_WIFI(2), EC_TRANSPORT_IOS_USB_EAP(3),
                 EC_TRANSPORT_IOS_USB_MUX(4), EC_TRANSPORT_IOS_USB_AIRPLAY(5),
                 EC_TRANSPORT_IOS_WIFI_APP(6), EC_TRANSPORT_IOS_WIFI_AIRPLAY(7)
```

CarAALink only implements the Android USB-AOA path (value `1`). A real
head-unit capture over `EC_TRANSPORT_ANDROID_WIFI` (value `2`) was also
observed (`Logcat car carbitlink wireless.txt`) -- a completely separate
transport (mDNS/`_EasyConn._tcp.local.` discovery, TCP sockets), **not**
relevant to CarAALink's USB-AOA implementation and not analyzed further.

## What's still genuinely unknown

- The dispatch table mapping each PXC message code (`0x000103xx` etc.) to
  a named SDK call -- we matched by JSON shape, not by tracing code.
- What `wantEncoder` in `CONFIG_CAPTURE` actually selects.
- Whether the real phone-side CarbitLink app's Mirror encoder runs at a
  pull-matching frame rate, or relies on the same starvation-style
  repeat behavior we do.
- The exact structure of the `0x00010370` "content"/"value" blob -- looks
  encrypted, not inspected further.
- Real head-unit support, audio buffering and microphone negotiation across firmware versions.

Do not fill these in from guesswork -- update this file only from a new
capture or decompile finding, same rule as everywhere else in this repo.

## Layer 7: Audio (experimental)

Source: user-supplied CarbitLink-EasyConnection 6.5.1 APK, SHA256
`545059ccc0db49d613268c3d5d12e6916649ce3f467a77d1198882977841c9e7`.
Static analysis of `lib/arm64-v8a/libEPSDK.so` (ELF virtual addresses; no proprietary binaries or code copied):

| Function / address | Finding |
|---|---|
| C2PMainService::handleEnableDownloadPhoneAudio, 0x1c0240 | C2P 0x10020 reads tts, vr, talkie, music; reply 0x10021 echoes extra |
| P2CMainService::sendAudioStart, 0x1c2c80 | 0x20090 JSON type, sampleRate, channel, format; expects 0x20091 before success |
| P2CMainService::sendAudioData, 0x1c301c | 0x60000030 for music (SDK type 8); raw data payload |
| P2CMainService::sendAudioStop, 0x1c30f0 | 0x200D0 JSON type; expects 0x200D1 |
| P2CMainService::startAudioRecord, 0x1c33d4 | 0x200F0 with sampleRate/channel/format, ACK 0x200F1 |
| P2CMainService::stopAudioRecord, 0x1c364c | 0x20100, ACK 0x20101 |
| C2PMainService::handleMicRecordData, 0x1c07b4 | 0x10060 raw input, ACK 0x10061 echoes extra |

Music wire type is 4 (SDK enum 8 is mapped to JSON type 4). PCM S16_LE
format 3 is from the DEX EPAudioFormatType enum. Use 48000 Hz stereo for
the decoded AA media channel. The existing PXC framing is reused.

### Additional audio and microphone findings

- `sendAudioData` table at 0x1392e0 maps SDK types 1, 2, 4, 8
  to 0x60000000, 0x60000010, 0x60000020, 0x60000030.
- `sendAudioStart` and `sendAudioStop` map SDK enum values to
  JSON types 1 (TTS), 2 (VR), 3 (Talkie), 4 (Music).
- `handleEnableDownloadPhoneAudio` at 0x1c0240 reads boolean
  `tts`, `vr`, `talkie`, `music`; each must explicitly be true.
- `handleClientInfo` at 0x1bef68 checks boolean `supportMic` and calls
  `EPAudioManager::enableAudioRecod` at 0x1befe8.
- `startAudioRecord` sends JSON sampleRate/channel/format without a
  type; `stopAudioRecord` has an empty body. Mic data ACK echoes
  the extra field and has an empty body (0x1c07e8–0x1c0800).
- Start/stop playback ACKs have no validated stream correlation in
  the SDK. The implementation serializes pending playback requests.
  A timeout disables remote playback until a new connection.

### AA integration and limitations

Opt-in CarbitLink audio negotiates the existing AAC media sink plus a
16 kHz/16-bit/mono speech sink (AA AudioType SPEECH=1, see
https://github.com/mrmees/open-android-auto/blob/main/docs/channels/audio.md).
Selecting VR during mic requests or transient audio focus is an integration
heuristic, not a proven semantic mapping. Low-level Talkie is implemented
and tested; no AA telephony/PTT source is mapped to it without more
evidence. Calls keep the Bluetooth/OS path. PCM formats are the chosen AA
formats, not a firmware guarantee.

The mic checkbox is off by default and requires CarbitLink audio mode,
RECORD_AUDIO, supportMic=true, start ACK and actual PCM. Only an AA
microphone request starts it. Data is bounded, never logged, discarded
on close. Missing/stalled mic data falls back to local capture. Output
falls back to phone/Bluetooth if unsupported, congested or unacknowledged.
No QDLink wire changes. All paths need real-vehicle verification.
