from __future__ import annotations

import json
import tempfile
import unittest
from collections import deque
from pathlib import Path
from unittest.mock import patch

import yaml

from harness_py.judge import (
    CalibrationDataError,
    JudgeProtocolError,
    JudgeVerdict,
    LLMJudge,
    evaluate_calibration,
    load_calibration_cases,
)
from harness_py.llm import ChatModel, ChatTurn, ToolCall


LABELS_PATH = Path("research/golden-data/human-labels.yaml")
HOLDOUT_LABELS_PATH = Path("research/golden-data/human-labels-holdout.yaml")


class CalibrationLoaderTest(unittest.TestCase):
    def test_committed_labels_resolve_review_artifacts_and_project_only_cited_evidence(self) -> None:
        calibration = load_calibration_cases(LABELS_PATH)

        self.assertEqual("harness_golden_seed", calibration.dataset_id)
        self.assertEqual(4, len(calibration.cases))
        self.assertEqual(2, sum(case.human.overall == "pass" for case in calibration.cases))

        for case in calibration.cases:
            packet = case.judge_packet()
            cited_ids = set(case.answer.get("cited_evidence_ids") or [])

            self.assertEqual(cited_ids, {item["evidence_id"] for item in packet["cited_evidence"]})
            self.assertNotIn("judgment", packet)
            self.assertFalse(_contains_key(packet, "note"))
            self.assertNotIn("answer_type", packet["answer"])
            self.assertNotIn("fields", packet["answer"])
            self.assertTrue(all("matched_anchor_id" not in item for item in packet["cited_evidence"]))
            self.assertTrue(all("location" not in item for item in packet["cited_evidence"]))

    def test_follow_up_holdout_packets_include_conversation_context(self) -> None:
        calibration = load_calibration_cases(HOLDOUT_LABELS_PATH)

        follow_up_cases = [
            case
            for case in calibration.cases
            if case.review.get("conversation") is not None
        ]

        self.assertEqual(3, len(follow_up_cases))
        for case in follow_up_cases:
            packet = case.judge_packet()
            self.assertEqual(case.review["conversation"], packet["review"]["conversation"])

    def test_loader_rejects_human_overall_inconsistent_with_dimensions(self) -> None:
        raw = yaml.safe_load(LABELS_PATH.read_text(encoding="utf-8"))
        raw["labels"][0]["judgment"]["overall"] = "fail"

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "labels.yaml"
            path.write_text(yaml.safe_dump(raw, sort_keys=False), encoding="utf-8")

            with self.assertRaisesRegex(CalibrationDataError, "overall"):
                load_calibration_cases(path, repo_root=Path.cwd())


class LLMJudgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.case = load_calibration_cases(LABELS_PATH).cases[0]

    def test_judge_uses_one_required_tool_call_and_derives_overall(self) -> None:
        model = _QueueChatModel([_judgment_arguments("pass", "pass", "pass")])

        verdict = LLMJudge(model, max_tokens=700).judge(self.case)

        self.assertEqual("pass", verdict.overall)
        self.assertEqual("Synthetic judge rationale.", verdict.rationale)
        self.assertEqual([], verdict.grounding_issues)
        self.assertEqual(1, len(model.calls))
        self.assertEqual("submit_judgment", model.calls[0]["required_tool_name"])
        prompt_packet = json.loads(model.calls[0]["messages"][-1]["content"])
        self.assertEqual(self.case.case_id, prompt_packet["case_id"])
        self.assertNotIn("judgment", prompt_packet)
        self.assertNotIn(self.case.human.note, model.calls[0]["messages"][-1]["content"])

    def test_judge_rejects_missing_wrong_or_invalid_tool_results(self) -> None:
        turns = [
            ChatTurn(content="pass"),
            ChatTurn(content="", tool_calls=[ToolCall("wrong", "other_tool", {})]),
            ChatTurn(
                content="",
                tool_calls=[ToolCall(
                    "invalid",
                    "submit_judgment",
                    _judgment_arguments("maybe", "pass", "pass"),
                )],
            ),
            ChatTurn(
                content="",
                tool_calls=[ToolCall(
                    "inconsistent_grounding",
                    "submit_judgment",
                    {
                        **_judgment_arguments("pass", "pass", "pass"),
                        "grounding_issues": ["An unsupported factual clause."],
                    },
                )],
            ),
        ]

        for turn in turns:
            with self.subTest(turn=turn), self.assertRaises(JudgeProtocolError):
                LLMJudge(_StaticChatModel(turn)).judge(self.case)

    def test_judge_accepts_an_omitted_optional_rationale(self) -> None:
        arguments = _judgment_arguments("pass", "pass", "pass")
        arguments.pop("rationale")
        turn = ChatTurn(content="", tool_calls=[ToolCall(
            "no_rationale",
            "submit_judgment",
            arguments,
        )])

        verdict = LLMJudge(_StaticChatModel(turn)).judge(self.case)

        self.assertEqual("", verdict.rationale)

    def test_judge_flattens_structured_grounding_issue_text(self) -> None:
        verdict = JudgeVerdict.from_dict({
            "decision": "pass",
            "task_fulfillment": "pass",
            "grounding": "fail",
            "grounding_issues": [{
                "item": {"item": "First unsupported clause.", "$text": "Second unsupported clause."},
                "$text": "Third unsupported clause.",
            }],
            "rationale": "Grounding has gaps.",
        })

        self.assertEqual(
            [
                "First unsupported clause.",
                "Second unsupported clause.",
                "Third unsupported clause.",
            ],
            verdict.grounding_issues,
        )


class AgreementReportTest(unittest.TestCase):
    def test_report_distinguishes_full_agreement_false_pass_false_failure_and_errors(self) -> None:
        calibration = load_calibration_cases(LABELS_PATH)
        verdicts = [
            _verdict("pass", "pass", "pass"),
            _verdict("pass", "pass", "pass"),
            _verdict("fail", "fail", "fail"),
            RuntimeError("provider unavailable"),
        ]

        report = evaluate_calibration(
            calibration,
            _SequenceJudge(verdicts),
            judge_metadata={"model": "judge-test"},
        )

        self.assertEqual(4, report["case_count"])
        self.assertEqual(2, report["full_agreement_count"])
        self.assertEqual(2, report["overall_agreement_count"])
        self.assertEqual(1, report["false_pass_count"])
        self.assertEqual(0, report["false_failure_count"])
        self.assertEqual(1, report["technical_error_count"])
        self.assertFalse(report["accepted"])

    def test_cli_writes_an_accepted_four_case_agreement_report(self) -> None:
        from harness_py.cli import main

        calibration = load_calibration_cases(LABELS_PATH)
        responses = [
            _judgment_arguments(
                case.human.decision,
                case.human.task_fulfillment,
                case.human.grounding,
            )
            for case in calibration.cases
        ]
        model = _QueueChatModel(responses)

        class StubProvider:
            def public_diagnostics(self):
                return {"provider": "test", "model": "judge-test"}

        with tempfile.TemporaryDirectory() as tmp, patch(
            "harness_py.cli._judge_model",
            return_value=(StubProvider(), model),
        ), patch("builtins.print"):
            code = main([
                "judge-calibrate",
                "--labels",
                str(LABELS_PATH),
                "--out",
                tmp,
            ])
            report_path = Path(tmp) / "agreement_report.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual(0, code)
        self.assertTrue(report["accepted"])
        self.assertEqual(4, report["full_agreement_count"])
        self.assertEqual(0, report["false_pass_count"])
        self.assertEqual(4, len(model.calls))


def _judgment_arguments(decision: str, task: str, grounding: str) -> dict:
    return {
        "decision": decision,
        "task_fulfillment": task,
        "grounding": grounding,
        "grounding_issues": (
            ["Synthetic grounding issue."] if grounding == "fail" else []
        ),
        "rationale": "Synthetic judge rationale.",
    }


def _verdict(decision: str, task: str, grounding: str) -> JudgeVerdict:
    return JudgeVerdict.from_dict(_judgment_arguments(decision, task, grounding))


class _QueueChatModel(ChatModel):
    def __init__(self, arguments: list[dict]) -> None:
        self.arguments = deque(arguments)
        self.calls: list[dict] = []

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        self.calls.append({
            "messages": messages,
            "tools": tools,
            "required_tool_name": required_tool_name,
            "max_tokens": max_tokens,
        })
        return ChatTurn(content="", tool_calls=[ToolCall(
            id=f"judge_{len(self.calls)}",
            name="submit_judgment",
            arguments=self.arguments.popleft(),
        )])


class _StaticChatModel(ChatModel):
    def __init__(self, turn: ChatTurn) -> None:
        self.turn = turn

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        return self.turn


class _SequenceJudge:
    def __init__(self, results: list[JudgeVerdict | Exception]) -> None:
        self.results = deque(results)

    def judge(self, _case):
        result = self.results.popleft()
        if isinstance(result, Exception):
            raise result
        return result


def _contains_key(value, target: str) -> bool:
    if isinstance(value, dict):
        return target in value or any(_contains_key(item, target) for item in value.values())
    if isinstance(value, list):
        return any(_contains_key(item, target) for item in value)
    return False


if __name__ == "__main__":
    unittest.main()
