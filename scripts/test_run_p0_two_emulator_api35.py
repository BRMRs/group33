import unittest

from run_p0_two_emulator_api35 import (
    avd_names,
    expected_phases,
    finalize_result,
    system_image_package,
    verify_chain_results,
)
from run_debug_online_chain import inspect_instrumentation, network_toggle_commands, phases_for


class VerifyChainResultsTest(unittest.TestCase):
    def test_shared_phase_plan_includes_the_full_offline_acceptance_tail(self):
        self.assertEqual(expected_phases(), phases_for(
            live_dose=True, dose_upload=True, help_delivery=True))
        self.assertIn(('elder-help-restart', 'elder'), expected_phases())
        self.assertIn(('caregiver-sees-help', 'caregiver'), expected_phases())

    def test_instrumentation_result_rejects_skipped_or_duplicate_completion(self):
        passed = """INSTRUMENTATION_STATUS: class=com.example.FlowTest
INSTRUMENTATION_STATUS: test=phase
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.FlowTest
INSTRUMENTATION_STATUS: test=phase
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
"""
        self.assertTrue(inspect_instrumentation(passed)["passed"])
        self.assertFalse(inspect_instrumentation(passed.replace("CODE: 0", "CODE: -3"))["passed"])
        self.assertFalse(inspect_instrumentation(passed + passed)["passed"])

    def test_network_isolation_uses_supported_commands_on_both_api_boundaries(self):
        self.assertEqual(
            [('shell', 'svc', 'wifi', 'disable'), ('shell', 'svc', 'data', 'disable')],
            network_toggle_commands(27, True),
        )
        self.assertEqual(
            [('shell', 'svc', 'wifi', 'enable'), ('shell', 'svc', 'data', 'enable')],
            network_toggle_commands(27, False),
        )
        self.assertEqual(
            [('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')],
            network_toggle_commands(35, True),
        )

    def test_rejects_a_missing_caregiver_acceptance_phase(self):
        complete = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]
        missing = [row for row in complete if row["phase"] != "caregiver-sees-taken"]

        with self.assertRaisesRegex(ValueError, "caregiver-sees-taken"):
            verify_chain_results(missing)

    def test_rejects_a_missing_help_delivery_phase(self):
        complete = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]
        missing = [row for row in complete if row["phase"] != "caregiver-sees-help"]

        with self.assertRaisesRegex(ValueError, "caregiver-sees-help"):
            verify_chain_results(missing)

    def test_requires_both_owned_avds_and_private_state_to_be_removed(self):
        phases = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]

        result = finalize_result(
            head="abc123",
            expected_head="abc123",
            expected_api="35",
            devices={
                "elder": {"api": "35", "abi": "x86_64", "stopped": True},
                "caregiver": {"api": "35", "abi": "x86_64", "stopped": True},
            },
            phases=phases,
            server_healthy=True,
            private_state_removed=False,
        )

        self.assertFalse(result["passed"])
        self.assertEqual("private-state-not-removed", result["failure"])

    def test_accepts_api27_only_when_both_owned_avds_match_the_requested_api(self):
        phases = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]
        devices = {
            "elder": {"api": "27", "abi": "x86_64", "stopped": True},
            "caregiver": {"api": "27", "abi": "x86_64", "stopped": True},
        }

        accepted = finalize_result(
            head="abc123",
            expected_head="abc123",
            expected_api="27",
            devices=devices,
            phases=phases,
            server_healthy=True,
            private_state_removed=True,
        )
        mismatched = finalize_result(
            head="abc123",
            expected_head="abc123",
            expected_api="35",
            devices=devices,
            phases=phases,
            server_healthy=True,
            private_state_removed=True,
        )

        self.assertTrue(accepted["passed"])
        self.assertFalse(mismatched["passed"])
        self.assertEqual("owned-avd-not-verified", mismatched["failure"])
        self.assertNotEqual(avd_names("27"), avd_names("35"))
        self.assertEqual(
            "system-images;android-27;default;x86_64",
            system_image_package("27"),
        )
        self.assertEqual(
            "system-images;android-35;google_apis;x86_64",
            system_image_package("35"),
        )


if __name__ == "__main__":
    unittest.main()
