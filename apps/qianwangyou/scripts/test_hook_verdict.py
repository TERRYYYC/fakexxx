import json
import tempfile
import unittest
from pathlib import Path

from scripts import hook_verdict


class HookVerdictTest(unittest.TestCase):
    def test_unavailable_negative_control_rejects_platform_default_coincidence(self):
        control = {
            "cellInfo": {"sync": {"lte": {"tac": 4095}}},
        }
        no_op = {
            "sessionId": "acceptance-123",
            "cellInfo": {"sync": {"lte": {"tac": 4095}}},
            "errors": [],
        }
        failed = hook_verdict.evaluate(
            {"cellInfo.sync.lte.tac": 4095},
            no_op,
            "acceptance-123",
            restored=True,
            control_report=control,
            control_paths=("cellInfo.sync.lte.tac",),
        )
        self.assertFalse(failed.passed)
        self.assertEqual("same_as_control", failed.fields[-1].reason)

        unavailable = {
            "sessionId": "acceptance-123",
            "cellInfo": {"sync": {"lte": {"tac": 2_147_483_647}}},
            "errors": [],
        }
        passed = hook_verdict.evaluate(
            {"cellInfo.sync.lte.tac": 2_147_483_647},
            unavailable,
            "acceptance-123",
            restored=True,
            control_report=control,
            control_paths=("cellInfo.sync.lte.tac",),
        )
        self.assertTrue(passed.passed)
        self.assertEqual("different_from_control", passed.fields[-1].reason)
        self.assertEqual(
            {
                "configured": 1,
                "verified": 1,
                "failed": 0,
                "restored": True,
            },
            passed.summary,
        )
    def test_exact_values_include_zero_false_and_empty_string(self):
        report = {
            "sessionId": "acceptance-123",
            "telephony": {
                "networkType": 0,
                "isNetworkRoaming": False,
                "operatorName": "",
            },
            "errors": [],
        }
        expected = {
            "telephony.networkType": 0,
            "telephony.isNetworkRoaming": False,
            "telephony.operatorName": "",
        }

        verdict = hook_verdict.evaluate(
            expected=expected,
            report=report,
            session_id="acceptance-123",
            restored=True,
        )

        self.assertTrue(verdict.passed)
        self.assertEqual(3, verdict.summary["configured"])
        self.assertEqual(3, verdict.summary["verified"])
        self.assertEqual(0, verdict.summary["failed"])
        self.assertTrue(verdict.summary["restored"])

    def test_missing_and_different_paths_fail_independently(self):
        report = {
            "sessionId": "acceptance-123",
            "cellInfo": {"sync": {"lte": {"tac": 10}}},
            "errors": [],
        }
        expected = {
            "cellInfo.sync.lte.tac": 20,
            "cellInfo.sync.lte.ci": 12345,
        }

        verdict = hook_verdict.evaluate(expected, report, "acceptance-123", restored=True)

        self.assertFalse(verdict.passed)
        by_path = {item.path: item for item in verdict.fields}
        self.assertEqual("different", by_path["cellInfo.sync.lte.tac"].reason)
        self.assertEqual("missing", by_path["cellInfo.sync.lte.ci"].reason)

    def test_stale_session_probe_errors_and_missing_restore_are_hard_failures(self):
        report = {
            "sessionId": "acceptance-old",
            "telephony": {"networkType": 20},
            "errors": [{"stage": "callback", "error": "timeout"}],
        }

        verdict = hook_verdict.evaluate(
            {"telephony.networkType": 20},
            report,
            "acceptance-new",
            restored=False,
        )

        self.assertFalse(verdict.passed)
        self.assertEqual(
            {"$sessionId", "$errors", "$restore"},
            {item.path for item in verdict.fields if item.status == "FAILED"},
        )

    def test_boolean_and_integer_are_not_interchangeable(self):
        verdict = hook_verdict.evaluate(
            {"telephony.isNetworkRoaming": False},
            {
                "sessionId": "acceptance-123",
                "telephony": {"isNetworkRoaming": 0},
                "errors": [],
            },
            "acceptance-123",
            restored=True,
        )

        self.assertFalse(verdict.passed)
        self.assertEqual("different_type", verdict.fields[0].reason)

    def test_complete_integer_set_matcher_requires_range_and_variation(self):
        matcher = {
            "$matcher": "complete_int_set",
            "allowed": [-104, -103, -102, -101, -100, -99, -98],
            "count": 256,
        }
        passing_samples = (
            [-104, -103, -102, -101, -100, -99, -98] * 37
        )[:256]
        passing = hook_verdict.evaluate(
            {"cellInfo.sync.lte.rsrpSamples": matcher},
            {
                "sessionId": "acceptance-123",
                "cellInfo": {"sync": {"lte": {"rsrpSamples": passing_samples}}},
                "errors": [],
            },
            "acceptance-123",
            restored=True,
        )
        self.assertTrue(passing.passed)
        self.assertEqual("complete_int_set", passing.fields[0].reason)

        constant = hook_verdict.evaluate(
            {"cellInfo.sync.lte.rsrpSamples": matcher},
            {
                "sessionId": "acceptance-123",
                "cellInfo": {"sync": {"lte": {"rsrpSamples": [-101] * 256}}},
                "errors": [],
            },
            "acceptance-123",
            restored=True,
        )
        self.assertFalse(constant.passed)
        self.assertEqual("incomplete_set", constant.fields[0].reason)

        out_of_range = hook_verdict.evaluate(
            {"cellInfo.sync.lte.rsrpSamples": matcher},
            {
                "sessionId": "acceptance-123",
                "cellInfo": {
                    "sync": {
                        "lte": {
                            "rsrpSamples": passing_samples[:-1] + [-97],
                        },
                    },
                },
                "errors": [],
            },
            "acceptance-123",
            restored=True,
        )
        self.assertFalse(out_of_range.passed)
        self.assertEqual("unexpected_set", out_of_range.fields[0].reason)

    def test_cli_prints_deterministic_summary_and_exit_codes(self):
        report = {
            "sessionId": "acceptance-123",
            "telephony": {"networkType": 20},
            "errors": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "report.json"
            report_path.write_text(json.dumps(report), encoding="utf-8")

            output = []
            code = hook_verdict.main(
                [
                    "--expected-json",
                    '{"telephony.networkType":20}',
                    "--report-file",
                    str(report_path),
                    "--session-id",
                    "acceptance-123",
                    "--restored",
                ],
                emit=output.append,
            )

        self.assertEqual(0, code)
        self.assertEqual(
            "VERIFIED telephony.networkType expected=20 observed=20",
            output[0],
        )
        self.assertEqual(
            {
                "configured": 1,
                "verified": 1,
                "failed": 0,
                "restored": True,
            },
            json.loads(output[-1]),
        )


if __name__ == "__main__":
    unittest.main()
