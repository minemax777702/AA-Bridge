"""Validate that every registered diagnostic scenario is reachable exactly once."""
import pathlib
import re
import unittest
from analyze_hu_capabilities import analyze

ROOT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/caraalink/diagnostics"

class WizardCoverage(unittest.TestCase):
    def test_all_scenarios_are_reachable(self):
        survey = (ROOT / "HuSurvey.kt").read_text(encoding="utf-8")
        wizard = (ROOT / "HuWizardActivity.kt").read_text(encoding="utf-8")
        scenarios = re.findall(r'Scenario\("([^"]+)"', survey)
        block = re.search(r'private val ids = listOf\((.*?)\)\s*private var index', wizard, re.S)
        self.assertIsNotNone(block)
        ids = re.findall(r'"([^"]+)"', block.group(1))
        self.assertEqual(len(scenarios), len(set(scenarios)))
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(scenarios), set(ids))
        self.assertEqual(ids[0], "idle")
        self.assertIn("map_rotate_full", ids)
        self.assertIn("mic_pxc", ids)

    def test_analyzer_keeps_new_scenario_evidence(self):
        evidence = {"vehicleStateBefore": "ACC", "routeVerified": True}
        report = analyze({}, {"manualTests": [{"scenario": "audio_bt", "userResult": "works",
                                             "wizardEvidence": evidence}]})
        self.assertEqual(report["features"]["audio_bt"]["evidence"], [evidence])
        self.assertEqual(report["features"]["audio_bt"]["status"], "user-reported-working")

if __name__ == "__main__":
    unittest.main()
