import unittest
from analyze_hu_capabilities import analyze

class CapabilityTest(unittest.TestCase):
    def test_source_and_correlation(self):
        report = analyze({}, {"advertisedFields": {"PXC.CLIENT_INFO.supportMic": "false"},
            "packets": [{"id": "PXC/RX/0x00010430", "count": 2}],
            "manualTests": [{"scenario": "gear", "userResult": "inconclusive", "candidates": [
                {"packet": "PXC/RX/0x1234", "absentInIdleWindow": True},
                {"packet": "AA-local/RX/0x1234", "absentInIdleWindow": True}]}]})
        self.assertEqual(report["features"]["gear"]["status"], "not-established")
        self.assertEqual(report["features"]["voice"]["status"], "advertised-fields-observed")
        self.assertIn("not vehicle GPS", report["observed_known"][0]["meaning"])
        self.assertEqual([p["id"] for p in report["unknown_candidates"]], ["PXC/RX/0x1234"])

if __name__ == "__main__":
    unittest.main()
