from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from harness_py.evaluation.eval_recorder import EvalRecorder


class EvalRecorderTest(unittest.TestCase):
    def test_duplicate_event_is_skipped_and_result_is_written_once(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            recorder = EvalRecorder(tmp, "run_test")
            self.assertTrue(recorder.append(
                kind="run.started",
                operation_id="run",
                payload={"question": "test"},
            ))
            self.assertFalse(recorder.append(
                kind="run.started",
                operation_id="run",
                payload={"question": "test"},
            ))
            self.assertTrue(recorder.finish({"run_id": "run_test", "status": "COMPLETED"}))

            run_dir = Path(tmp) / "run_test"
            events = (run_dir / "events.jsonl").read_text(encoding="utf-8").splitlines()
            result = json.loads((run_dir / "result.json").read_text(encoding="utf-8"))

        self.assertEqual(1, len(events))
        self.assertEqual(1, result["duplicate_event_count"])
        self.assertEqual("COMPLETED", result["result"]["status"])
