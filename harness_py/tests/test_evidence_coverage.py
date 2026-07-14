from __future__ import annotations

import unittest

from harness_py.orchestration.evidence_coverage import evaluate_evidence_coverage


PAPERS = {
    "gaia_2024": {"identity": {"title": "GAIA: a benchmark for General AI Assistants"}},
    "webarena_2024": {
        "identity": {"title": "WebArena: A Realistic Web Environment for Building Autonomous Agents"}
    },
    "swebench_2024": {
        "identity": {"title": "SWE-bench: Can Language Models Resolve Real-World GitHub Issues?"}
    },
}


class EvidenceCoverageTest(unittest.TestCase):
    def test_multi_paper_answer_requires_a_citation_from_each_named_paper(self) -> None:
        decision = evaluate_evidence_coverage(
            draft={
                "outcome": "answered",
                "markdown": "GAIA reports one gap [[ev_gaia]], while WebArena reports another.",
            },
            question="Compare GAIA and WebArena.",
            paper_records_by_id=PAPERS,
            trace=[
                _find_event("gaia_2024", "webarena_2024"),
                _read_event("ev_gaia", "gaia_2024", "ev_web", "webarena_2024"),
            ],
            known_evidence={
                "ev_gaia": _evidence("ev_gaia", "gaia_2024"),
                "ev_web": _evidence("ev_web", "webarena_2024"),
            },
        )

        self.assertEqual(("webarena_2024",), decision.missing_cited_paper_ids)

    def test_returned_candidate_must_be_read_before_answering(self) -> None:
        decision = evaluate_evidence_coverage(
            draft={"outcome": "answered", "markdown": "SWE-bench uses real GitHub issues."},
            question="Which benchmark fits issue fixing?",
            paper_records_by_id=PAPERS,
            trace=[_find_event("swebench_2024")],
            known_evidence={},
        )

        self.assertEqual(("swebench_2024",), decision.missing_read_paper_ids)

    def test_heading_only_citation_does_not_satisfy_content_coverage(self) -> None:
        heading = _evidence("ev_heading", "swebench_2024", element_type="heading")
        decision = evaluate_evidence_coverage(
            draft={
                "outcome": "answered",
                "markdown": "SWE-bench constructs executable issue-resolution tasks [[ev_heading]].",
            },
            question="Explain SWE-bench.",
            paper_records_by_id=PAPERS,
            trace=[_find_event("swebench_2024"), _read_event("ev_heading", "swebench_2024")],
            known_evidence={"ev_heading": heading},
        )

        self.assertEqual(("swebench_2024",), decision.weak_only_paper_ids)

    def test_metadata_only_search_does_not_require_content_citations(self) -> None:
        decision = evaluate_evidence_coverage(
            draft={"outcome": "answered", "markdown": "The corpus contains GAIA and WebArena."},
            question="List the papers.",
            paper_records_by_id=PAPERS,
            trace=[{
                "tool_name": "search_paper_candidates",
                "arguments": {"query_text": ""},
                "result": {"candidates": [
                    {"paper_id": "gaia_2024"},
                    {"paper_id": "webarena_2024"},
                ]},
            }],
            known_evidence={},
        )

        self.assertTrue(decision.accepted)

    def test_search_preview_cannot_support_paper_content_claims(self) -> None:
        decision = evaluate_evidence_coverage(
            draft={
                "outcome": "answered",
                "markdown": "SWE-bench is built to evaluate agents that fix GitHub issues.",
            },
            question="Which benchmark should I use for issue fixing?",
            paper_records_by_id=PAPERS,
            trace=[{
                "tool_name": "search_paper_candidates",
                "arguments": {"query_text": "coding issue benchmark"},
                "result": {"candidates": [{"paper_id": "swebench_2024"}]},
            }],
            known_evidence={},
        )

        self.assertEqual(("swebench_2024",), decision.missing_candidate_paper_ids)


def _find_event(*paper_ids: str) -> dict:
    return {
        "tool_name": "find_reading_locations",
        "arguments": {"paper_ids": list(paper_ids), "query_text": "evidence"},
        "result": {
            "locations": [
                {"paper_id": paper_id, "location_ref": f"loc_{paper_id}"}
                for paper_id in paper_ids
            ]
        },
    }


def _read_event(*values: str) -> dict:
    items = []
    for index in range(0, len(values), 2):
        evidence_id, paper_id = values[index:index + 2]
        items.append(_evidence(evidence_id, paper_id))
    return {
        "tool_name": "read_locations",
        "arguments": {"location_refs": [f"loc_{item['paper_id']}" for item in items]},
        "result": {"items": items},
    }


def _evidence(evidence_id: str, paper_id: str, *, element_type: str = "paragraph") -> dict:
    return {
        "evidence_id": evidence_id,
        "paper_id": paper_id,
        "element_type": element_type,
        "span_text": "Substantive evidence text from the paper.",
    }


if __name__ == "__main__":
    unittest.main()
