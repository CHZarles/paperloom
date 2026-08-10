from __future__ import annotations

import json
import os
import tempfile
import time
import unittest
from pathlib import Path

from harness_py.evaluation.eval_recorder import EvalRecorder, TraceRetention, prune_trace_runs


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

    def test_trace_retention_removes_expired_then_oldest_completed_runs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            now = time.time()
            for name, age, completed in (
                ("expired", 100, True),
                ("oldest", 20, True),
                ("newest", 10, True),
                ("active", 100, False),
            ):
                run_dir = root / name
                run_dir.mkdir()
                (run_dir / "events.jsonl").write_bytes(b"x" * 10)
                if completed:
                    (run_dir / "result.json").write_text("{}", encoding="utf-8")
                for item in run_dir.iterdir():
                    os.utime(item, (now - age, now - age))
                os.utime(run_dir, (now - age, now - age))

            result = prune_trace_runs(
                root,
                TraceRetention(max_age_seconds=50, max_bytes=25, incomplete_grace_seconds=200),
                now=now,
            )

            self.assertFalse((root / "expired").exists())
            self.assertFalse((root / "oldest").exists())
            self.assertTrue((root / "newest").exists())
            self.assertTrue((root / "active").exists())
            self.assertEqual(2, result["deleted_runs"])
            self.assertLessEqual(result["remaining_bytes"], 25)
