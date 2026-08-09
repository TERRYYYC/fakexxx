import unittest

from scripts import test_runtime_verify_flow as runtime_flow


FP1 = "sha256:1111111111111111"
FP2 = "sha256:2222222222222222"


class RuntimeVerifyFlowContractTest(unittest.TestCase):
    def test_parser_reads_probe_and_scheduler_evidence_from_logcat(self):
        requested = runtime_flow.parse_line(
            f"08-01 20:00:00.000 I/FakeGPS-Probe: event=requested requestId=r1 fp={FP1}"
        )
        delivered = runtime_flow.parse_line(
            f"I/FakeGPS-Probe( 1234): event=delivered requestId=r1 fp={FP1} fields=7"
        )
        changed = runtime_flow.parse_line(
            "08-01 20:00:02.000 I/LSPosed: FakeGPS-Hook: event=interval_changed "
            "process=com.example fromMs=30000 toMs=5000"
        )

        self.assertEqual(("requested", "r1", FP1), (requested.event, requested.request_id, requested.fingerprint))
        self.assertEqual(7, delivered.fields)
        self.assertEqual((30000, 5000), (changed.from_ms, changed.to_ms))

        started = runtime_flow.parse_line(
            f"I/FakeGPS-Probe( 4321): event=started requestId=r1 fp={FP1}"
        )
        self.assertEqual(("started", 4321), (started.event, started.pid))

    def test_parser_rejects_malformed_ids_fingerprints_and_intervals(self):
        malformed = (
            "FakeGPS-Probe: event=requested requestId=has spaces fp=sha256:1111111111111111",
            "FakeGPS-Probe: event=requested requestId=r1 fp=not-a-fingerprint",
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=-1",
        )
        self.assertTrue(all(runtime_flow.parse_line(line) is None for line in malformed))

    def test_stale_delivery_cannot_pass_the_trace(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=r2 fp={FP2} fields=1",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched delivered", verdict.errors)

    def test_terminal_event_before_request_cannot_pass_by_key_coincidence(self):
        lines = [
            f"FakeGPS-Probe: event=delivered requestId=r1 fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched delivered", verdict.errors)

    def test_truncated_historical_prefix_does_not_poison_latest_complete_probe(self):
        lines = [
            f"I/FakeGPS-Probe( 90): event=started requestId=rolled-out fp={FP2}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=rolled-out fp={FP2} fields=1",
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000",
            f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_intervals=(30000,),
            expected_fingerprint=FP1,
            require_probe=True,
            require_scheduler=True,
            expected_scheduler_process="name.caiyao.fakegps:hook_verify",
        )

        self.assertTrue(verdict.passed, verdict.errors)

    def test_unmatched_terminal_after_first_retained_request_still_fails(self):
        lines = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000",
            f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 90): event=delivered requestId=alien fp={FP2} fields=1",
            f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_intervals=(30000,),
            expected_fingerprint=FP1,
            require_probe=True,
            require_scheduler=True,
            expected_scheduler_process="name.caiyao.fakegps:hook_verify",
        )

        self.assertFalse(verdict.passed)
        self.assertIn("unmatched delivered", verdict.errors)

    def test_ignored_stale_callback_is_evidence_but_not_a_terminal_delivery(self):
        valid = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP2}",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=old fp={FP2} reason=STALE_RESULT",
        ]
        self.assertTrue(runtime_flow.verify_trace(valid).passed)

        active = [
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=current fp={FP1} reason=STALE_RESULT",
        ]
        verdict = runtime_flow.verify_trace(active)
        self.assertFalse(verdict.passed)
        self.assertIn("ignored active result", verdict.errors)

        unknown = [
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=old fp={FP2} reason=STALE_RESULT",
        ]
        verdict = runtime_flow.verify_trace(unknown)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched ignored", verdict.errors)

    def test_unmatched_failure_cannot_masquerade_as_the_active_request(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=old fp={FP2} reason=NOT_SCOPED",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched failed", verdict.errors)

    def test_timeout_retry_requires_process_exit_new_id_and_fresh_delivery(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=r1 fp={FP1} reason=TIMEOUT",
            f"FakeGPS-Probe: event=requested requestId=r2 fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=r2 fp={FP1} fields=1",
        ]
        self.assertFalse(
            runtime_flow.verify_trace(
                lines,
                require_timeout_retry=True,
                probe_process_gone=False,
            ).passed
        )
        self.assertTrue(
            runtime_flow.verify_trace(
                lines,
                require_timeout_retry=True,
                probe_process_gone=True,
            ).passed
        )

        stale_green = lines[:-1] + [
            f"FakeGPS-Probe: event=delivered requestId=r1 fp={FP1} fields=1",
            lines[-1],
        ]
        verdict = runtime_flow.verify_trace(
            stale_green,
            require_timeout_retry=True,
            probe_process_gone=True,
        )
        self.assertFalse(verdict.passed)
        self.assertIn("timed-out request delivered", verdict.errors)
        self.assertIn("multiple terminal events for request", verdict.errors)

    def test_interval_matrix_and_single_scheduler_owner_are_strict(self):
        lines = [
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=30000",
            "FakeGPS-Hook: event=interval_changed process=com.example fromMs=30000 toMs=5000",
            "FakeGPS-Hook: event=interval_changed process=com.example fromMs=5000 toMs=60000",
        ]
        self.assertTrue(runtime_flow.verify_trace(lines, expected_intervals=(5000, 60000)).passed)
        duplicate = lines + [
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=60000"
        ]
        verdict = runtime_flow.verify_trace(duplicate, expected_intervals=(5000, 60000))
        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example", verdict.errors)

    def test_scheduler_owner_is_unique_per_android_pid_not_process_name(self):
        restarted = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
            "I/LSPosed-Bridge( 101): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
        ]
        self.assertTrue(runtime_flow.verify_trace(restarted).passed)

        duplicate_in_one_process = restarted[:1] * 2
        verdict = runtime_flow.verify_trace(duplicate_in_one_process)
        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example pid=100", verdict.errors)

    def test_scheduler_owner_with_mixed_pid_provenance_fails_closed(self):
        mixed = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=30000",
        ]

        verdict = runtime_flow.verify_trace(mixed)

        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example", verdict.errors)

    def test_expected_fingerprint_and_not_scoped_failure_are_explicit_scenarios(self):
        not_scoped = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=r1 fp={FP1} reason=NOT_SCOPED",
        ]
        self.assertTrue(
            runtime_flow.verify_trace(
                not_scoped,
                expected_fingerprint=FP1,
                expected_probe_failure="NOT_SCOPED",
            ).passed
        )
        self.assertFalse(
            runtime_flow.verify_trace(not_scoped, expected_fingerprint=FP2).passed
        )

    def test_latest_probe_failure_cannot_be_masked_by_historical_delivery(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=old fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=current fp={FP1} reason=NOT_SCOPED",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_fingerprint=FP1,
            require_probe=True,
        )

        self.assertFalse(verdict.passed)
        self.assertIn("latest probe was not delivered", verdict.errors)

    def test_expected_failure_must_belong_to_latest_probe(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=old fp={FP1} reason=NOT_SCOPED",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_fingerprint=FP1,
            expected_probe_failure="NOT_SCOPED",
        )

        self.assertFalse(verdict.passed)
        self.assertIn("missing probe failure NOT_SCOPED", verdict.errors)

    def test_expected_fingerprint_must_belong_to_latest_probe(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=old fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP2}",
            f"FakeGPS-Probe: event=delivered requestId=current fp={FP2} fields=1",
        ]

        verdict = runtime_flow.verify_trace(lines, expected_fingerprint=FP1)

        self.assertFalse(verdict.passed)
        self.assertIn(f"missing delivered fingerprint {FP1}", verdict.errors)

    def test_required_scheduler_belongs_to_latest_probe_process_lifetime(self):
        unrelated = [
            "I/LSPosed-Bridge( 90): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000",
            f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            unrelated,
            require_probe=True,
            require_scheduler=True,
            expected_scheduler_process="name.caiyao.fakegps:hook_verify",
        )

        self.assertFalse(verdict.passed)
        self.assertIn("no scheduler owner for latest probe process", verdict.errors)

        current = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000",
            f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1",
        ]
        self.assertTrue(
            runtime_flow.verify_trace(
                current,
                require_probe=True,
                require_scheduler=True,
                expected_scheduler_process="name.caiyao.fakegps:hook_verify",
            ).passed
        )

    def test_required_scheduler_rejects_ambiguous_or_pidless_probe_start(self):
        owner = (
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000"
        )
        requested = f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}"
        delivered = (
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1"
        )
        kwargs = {
            "require_probe": True,
            "require_scheduler": True,
            "expected_scheduler_process": "name.caiyao.fakegps:hook_verify",
        }

        pidless = runtime_flow.verify_trace(
            [
                owner,
                requested,
                f"FakeGPS-Probe: event=started requestId=current fp={FP1}",
                delivered,
            ],
            **kwargs,
        )
        self.assertFalse(pidless.passed)
        self.assertIn("no scheduler owner for latest probe process", pidless.errors)

        duplicate = runtime_flow.verify_trace(
            [
                owner,
                requested,
                f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
                f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
                delivered,
            ],
            **kwargs,
        )
        self.assertFalse(duplicate.passed)
        self.assertIn("multiple process starts for latest probe", duplicate.errors)

    def test_expected_interval_uses_latest_probe_scheduler_state(self):
        lines = [
            "I/LSPosed-Bridge( 90): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=5000",
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=name.caiyao.fakegps:hook_verify intervalMs=30000",
            f"I/FakeGPS-Probe( 10): event=requested requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 100): event=started requestId=current fp={FP1}",
            f"I/FakeGPS-Probe( 10): event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_intervals=(5000,),
            require_probe=True,
            require_scheduler=True,
            expected_scheduler_process="name.caiyao.fakegps:hook_verify",
        )

        self.assertFalse(verdict.passed)
        self.assertIn("latest probe scheduler interval is 30000, expected 5000", verdict.errors)

        changed = lines[:2] + [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=interval_changed "
            "process=name.caiyao.fakegps:hook_verify fromMs=30000 toMs=5000",
        ] + lines[2:]
        self.assertTrue(
            runtime_flow.verify_trace(
                changed,
                expected_intervals=(5000,),
                require_probe=True,
                require_scheduler=True,
                expected_scheduler_process="name.caiyao.fakegps:hook_verify",
            ).passed
        )


if __name__ == "__main__":
    unittest.main()
