import io
import json
import struct
import tempfile
import unittest
from pathlib import Path

from decode_hu_dump import decode


class DumpTest(unittest.TestCase):
    def fixture(self):
        data = bytearray(b"AHUDUMP1" + struct.pack(">q", 42))
        for i, direction, name, payload in [
            (1, 1, b"usb-aoa/id", b"\x00\xff"),
            (2, 3, b"manual", b"gear"),
            (3, 1, b"usb-aoa/id", b"\x12"),
            (4, 2, b"usb-aoa/id", b"\x34"),
        ]:
            data.extend(struct.pack(">qqBii", i, i * 100, direction, len(name), len(payload)))
            data.extend(name + payload)
        manifest = dict(completeUntilStop=True, recordCount=4, bytesWritten=len(data), wallStartMs=42)
        return data, manifest

    def test_reconstructs_bytes_and_markers(self):
        data, manifest = self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            result = decode(io.BytesIO(data), out, manifest)
            self.assertTrue(result["verified_complete_until_stop"])
            self.assertEqual(next(out.glob("*-RX.bin")).read_bytes(), b"\x00\xff\x12")
            self.assertEqual(next(out.glob("*-TX.bin")).read_bytes(), b"\x34")
            timeline = [json.loads(x) for x in (out / "timeline.jsonl").read_text().splitlines()]
            self.assertEqual(timeline[1]["text"], "gear")

    def test_truncated_is_not_complete(self):
        data, manifest = self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            result = decode(io.BytesIO(data[:-1]), Path(tmp) / "out", manifest)
            self.assertFalse(result["verified_complete_until_stop"])
            self.assertEqual(result["records"], 3)

    def test_unfinalized_is_not_complete(self):
        data, manifest = self.fixture()
        manifest["completeUntilStop"] = False
        with tempfile.TemporaryDirectory() as tmp:
            result = decode(io.BytesIO(data), Path(tmp) / "out", manifest)
            self.assertFalse(result["verified_complete_until_stop"])


if __name__ == "__main__":
    unittest.main()
