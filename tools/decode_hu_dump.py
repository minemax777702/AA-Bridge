#!/usr/bin/env python3
"""Extract an AA-Bridge RAW dump ZIP or traffic.bin into per-stream RX/TX files.
No third-party dependencies. Never extracts arbitrary ZIP paths.
"""
import argparse
import contextlib
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

MAGIC = b"AHUDUMP1"
HEADER = struct.Struct(">qqBii")
MAX_RECORD = 16 * 1024 * 1024
DIRECTIONS = {1: "RX", 2: "TX", 3: "MARK", 4: "META"}


def exact(source, n):
    data = source.read(n)
    if len(data) != n:
        raise EOFError(f"truncated: expected {n}, got {len(data)}")
    return data


def decode(source, destination, manifest=None):
    destination.mkdir(parents=True, exist_ok=False)
    counts = {}
    sequence = 0
    error = None
    consumed = 0
    last_ns = -1
    with contextlib.ExitStack() as stack:
        timeline = stack.enter_context((destination / "timeline.jsonl").open("w", encoding="utf-8"))
        streams = {}
        try:
            if exact(source, 8) != MAGIC:
                raise ValueError("unrecognized dump format")
            wall_ms = struct.unpack(">q", exact(source, 8))[0]
            consumed = 16
            while True:
                first = source.read(1)
                if not first:
                    break
                seq, ns, direction, name_size, length = HEADER.unpack(first + exact(source, HEADER.size - 1))
                if seq != sequence + 1 or ns < last_ns or direction not in DIRECTIONS:
                    raise ValueError(f"invalid record {seq}")
                if not 0 < name_size <= 4096 or not 0 <= length <= MAX_RECORD:
                    raise ValueError(f"invalid record sizes at {seq}")
                name = exact(source, name_size).decode("utf-8")
                payload = exact(source, length)
                kind = DIRECTIONS[direction]
                event = dict(seq=seq, elapsed_ns=ns, stream=name, direction=kind, length=length)
                if direction in (1, 2):
                    key = (name, kind)
                    if key not in streams:
                        safe = hashlib.sha256(name.encode()).hexdigest()[:24] + "-" + kind + ".bin"
                        streams[key] = (safe, stack.enter_context((destination / safe).open("wb")))
                    filename, handle = streams[key]
                    event.update(file=filename, offset=handle.tell())
                    handle.write(payload)
                else:
                    event["text"] = payload.decode("utf-8", errors="replace")
                timeline.write(json.dumps(event, ensure_ascii=False) + "\n")
                counts[kind] = counts.get(kind, 0) + 1
                sequence = seq
                last_ns = ns
                consumed += HEADER.size + name_size + length
        except (EOFError, ValueError, OSError, zipfile.BadZipFile) as exc:
            error = str(exc)
        verified = bool(not error and manifest and manifest.get("completeUntilStop") is True
                        and manifest.get("recordCount") == sequence
                        and manifest.get("bytesWritten") == consumed
                        and manifest.get("wallStartMs") == locals().get("wall_ms"))
        summary = dict(records=sequence, bytes=consumed, counts=counts, verified_complete_until_stop=verified,
                       error=error, manifest=manifest)
        (destination / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, help="new output directory")
    args = parser.parse_args()
    if zipfile.is_zipfile(args.input):
        with zipfile.ZipFile(args.input) as archive:
            try:
                manifest = json.loads(archive.read("manifest.json"))
            except (KeyError, ValueError):
                manifest = None
            with archive.open("traffic.bin") as source:
                summary = decode(source, args.output, manifest)
    else:
        sidecar = args.input.with_name("manifest.json")
        manifest = json.loads(sidecar.read_text(encoding="utf-8")) if sidecar.is_file() else None
        with args.input.open("rb") as source:
            summary = decode(source, args.output, manifest)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0 if summary["verified_complete_until_stop"] else 2


if __name__ == "__main__":
    sys.exit(main())
