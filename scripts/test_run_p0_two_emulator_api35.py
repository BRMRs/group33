import unittest

from run_p0_two_emulator_api35 import expected_phases, finalize_result, verify_chain_results
from run_debug_online_chain import inspect_instrumentation, phases_for


class VerifyChainResultsTest(unittest.TestCase):
    def test_shared_phase_plan_includes_the_full_offline_acceptance_tail(self):
        self.assertEqual(expected_phases(), phases_for(live_dose=True, dose_upload=True))

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

    def test_rejects_a_missing_caregiver_acceptance_phase(self):
        complete = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]
        missing = [row for row in complete if row["phase"] != "caregiver-sees-taken"]

        with self.assertRaisesRegex(ValueError, "caregiver-sees-taken"):
            verify_chain_results(missing)

    def test_requires_both_owned_avds_and_private_state_to_be_removed(self):
        phases = [
            {"phase": phase, "role": role, "passed": True}
            for phase, role in expected_phases()
        ]

        result = finalize_result(
            head="abc123",
            expected_head="abc123",
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


if __name__ == "__main__":
    unittest.main()
