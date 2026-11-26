#!/usr/bin/env python3
"""Summarize evidence from one or more diagnostic ZIPs; never infer unsupported features."""
import argparse
import json
import zipfile
from pathlib import Path

FEATURES = {
    "display": ("mirror.capture.", "PXC.CLIENT_INFO.supportScreenMirroring"),
    "touch": ("PXC.CLIENT_INFO.supportScreenTouch",),
    "voice": ("PXC.CLIENT_INFO.supportMic",),
    "music": ("PXC.AUDIO_CAPABILITIES.",),
    "navigation": ("PXC.AUDIO_CAPABILITIES.",),
    "gear": (), "parking": (), "sensors": (), "doors": (), "climate": (),
    "wheel": (), "night": (), "volume": (), "ignition": (), "wireless": (),
    "reconnect": (), "idle": (), "call": (), "gps": (), "focus": (), "heartbeat": (),
}
# Direction matters: this is the HU asking for PHONE GPS, not a vehicle GPS feed.
KNOWN = {
    "PXC/RX/0x00010430": "HU requests phone GPS; not vehicle GPS evidence",
    "PXC/RX/0x00010750": "HU requests phone networking status",
    "PXC/RX/0x00010060": "HU microphone data message observed",
    "PXC/RX/0x00010020": "HU audio capability announcement",
    "PXC/RX/0x00010010": "HU client information announcement",
    "mirror/RX/touch": "HU touch callback observed",
    "mirror/RX/key": "HU key callback observed",
    "mirror/RX/capture-config": "HU capture configuration observed",
}
MAX_JSON = 32 * 1024 * 1024


def load(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            def read(name):
                info = z.getinfo(name)
                if info.file_size > MAX_JSON:
                    raise ValueError(f"{name}: oversized JSON")
                return json.loads(z.read(info))
            return read("manifest.json"), read("capabilities.json")
    return {}, json.loads(path.read_text(encoding="utf-8"))


def analyze(manifest, survey):
    fields = survey.get("advertisedFields", {})
    tests = survey.get("manualTests", [])
    packets = survey.get("packets", [])
    features = {}
    for name, prefixes in FEATURES.items():
        advertised = {k: v for k, v in fields.items()
                      if any(k.startswith(p) if p.endswith(".") else k == p for p in prefixes)}
        attempts = [t for t in tests if t.get("scenario") == name]
        features[name] = dict(advertised=advertised, user_results=[t.get("userResult") for t in attempts],
                              status="user-reported-working" if any(t.get("userResult") == "works" for t in attempts)
                              else "user-tested-not-confirmed" if any(t.get("userResult") == "failed" for t in attempts)
                              else "advertised-fields-observed" if advertised else "not-established")
    candidates = {}
    for test in tests:
        scenario = test.get("scenario")
        if scenario == "idle" or test.get("userResult") == "not-tested":
            continue
        for item in test.get("candidates", []):
            key = item.get("packet", "")
            # Phone-side AA messages must not be classified as actual HU packets.
            if "/RX/" not in key or key.startswith(("AA-local/", "device/", "manual/", "session/")):
                continue
            if item.get("absentInIdleWindow") or item.get("sampleDiffersFromIdle"):
                candidates.setdefault(key, []).append(dict(scenario=scenario,
                    absent_in_idle=item.get("absentInIdleWindow", False),
                    sampled_change=item.get("sampleDiffersFromIdle", False),
                    candidate_byte_offsets=item.get("candidateByteOffsets", [])))
    return dict(session=manifest.get("session"), capture_claims_complete=manifest.get("completeUntilStop"),
                features=features, observed_known=[dict(id=p["id"], count=p.get("count"), meaning=KNOWN[p["id"]])
                    for p in packets if p.get("id") in KNOWN],
                unknown_candidates=[dict(id=k, observations=v) for k,v in sorted(candidates.items()) if k not in KNOWN],
                inventory=packets, advertised_fields=fields,
                warnings=["Correlation and hashes are not protocol semantics.",
                          "A user result is not automatically verified by packet evidence.",
                          "AA-local is phone-side, not a head-unit capability.",
                          "Manifest completeness is a claim; run decode_hu_dump.py to verify traffic.bin.",
                          "Not established does not mean unsupported."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("-o", "--output", required=True, type=Path)
    args = parser.parse_args()
    sessions = [analyze(*load(path)) for path in args.inputs]
    repeated = {}
    for i, session in enumerate(sessions):
        for candidate in session["unknown_candidates"]:
            for obs in candidate["observations"]:
                key = (candidate["id"], obs["scenario"])
                repeated.setdefault(key, set()).add(i)
    report = dict(sessions=sessions, repeated_candidates=[
        dict(packet=k[0], scenario=k[1], session_count=len(v))
        for k,v in sorted(repeated.items(), key=lambda item: (-len(item[1]), item[0])) if len(v) > 1])
    with args.output.open("x", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
