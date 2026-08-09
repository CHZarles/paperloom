from __future__ import annotations

import unittest

from harness_py.orchestration.run_control import RunControl


class RunControlTest(unittest.TestCase):
    def test_counts_model_calls_without_a_call_limit(self) -> None:
        control = RunControl()

        control.start_model_call()
        control.start_model_call()

        self.assertEqual(2, control.model_calls_started)

    def test_records_usage_without_turn_token_limit(self) -> None:
        control = RunControl()

        control.record_model_usage(200_000, 200_000, 400_000)

        self.assertEqual(400_000, control.total_tokens)


if __name__ == "__main__":
    unittest.main()
