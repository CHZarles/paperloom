# Behavior-First Golden Data V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the implementation-coupled golden-data v1 format with the approved behavior-first v2 format, migrate the existing nine cases, add three history snapshots, and score only outcome, retrieval, content, and grounding.

**Architecture:** YAML remains the authored source of truth. `dataset.py` loads strict v2 manifests, paper packs, and cases into the existing normalized `GoldenDataset` runtime shape so retrieval tools do not need a second corpus model. A compact deterministic fixture proves scorer behavior, while real golden runs use `LiveResearchChatHarness` so the benchmark follows the same conversation path as terminal chat. Parser-anchor auditing is a separate command and result.

**Tech Stack:** Python 3, PyYAML, `unittest`, existing PaperLoom reading-model JSON, existing MiniMax OpenAI-compatible client.

## Global Constraints

- Python only. Do not edit or run Java code in this plan.
- Do not add a v1/v2 compatibility layer to the Python loader or scorer.
- Do not score intent labels, plan labels, stage order, tool-call count, runtime claim IDs, or exact answer prose.
- Do not expose `expect` data to the real model or stage runtime.
- Keep runtime trace artifacts for diagnostics and optional frontend panes.
- Do not add an LLM judge in this implementation pass.
- Do not add dependencies.
- Keep the existing five-paper corpus; only add three history snapshots after the nine migrated cases pass.
- Preserve unrelated dirty-worktree changes.

---

## File Map

**Create**

- `harness_py/golden_case.py`: v2 case accessors, pack scoping, and history-to-conversation-state conversion.
- `harness_py/golden_fixture.py`: deterministic passing fixture used only to test the dataset and scorer.
- `harness_py/audit.py`: independent authored-anchor-to-reading-model audit.
- `harness_py/tests/test_golden_v2.py`: focused v2 loader, scorer, history, and audit tests.
- `research/golden-data/cases/core.yaml`: the nine migrated cases plus, later, three history cases.

**Modify**

- `harness_py/models.py`: v2 schema constants.
- `harness_py/dataset.py`: strict v2 loading, validation, and normalization.
- `harness_py/scoring.py`: replace trace-contract scoring with four behavior dimensions.
- `harness_py/live_chat.py`: run a v2 snapshot through the same conversation path as terminal chat.
- `harness_py/stage_prototype/runtime.py`: accept message-based case input without reading gold expectations.
- `harness_py/cli.py`: use the v2 fixture/scorer, add `audit`, and route `agent-run` through live chat.
- `harness_py/__init__.py`: export v2 public names.
- `harness_py/tests/test_harness_py.py`: migrate synthetic cases and scorer assertions.
- `harness_py/tests/test_stage_prototype.py`: migrate synthetic cases and deterministic model fixtures.
- `research/golden-data/manifest.yaml`: v2 string references; remove scoring and compatibility metadata.
- `research/golden-data/paper-packs/transformer-bert-gpt.yaml`: one compact papers list and stable anchors.
- `research/golden-data/README.md`: concise v2 authoring and commands.
- `harness_py/README.md`: describe behavior scoring and the live golden runner.
- `research/harness-golden-data-schema.md`: replace the v1 schema with a short pointer to the approved v2 design.

**Delete**

- `harness_py/harness.py`: the v1 gold-driven fake harness.
- `research/golden-data/cases/seed-smoke.yaml`: superseded by `cases/core.yaml`.
- `research/golden-data/cases/paradigm-primary.yaml`: superseded by `cases/core.yaml`.

The existing Java-only `research/golden-data/run-traces/*.yaml` files are outside this Python plan. Leave them untouched and unreferenced by v2.

---

### Task 1: Replace Golden Data V1 With The Behavior-First V2 Core

**Files:**

- Create: `harness_py/golden_case.py`
- Create: `harness_py/golden_fixture.py`
- Create: `harness_py/tests/test_golden_v2.py`
- Create: `research/golden-data/cases/core.yaml`
- Modify: `harness_py/models.py`
- Modify: `harness_py/dataset.py`
- Modify: `harness_py/scoring.py`
- Modify: `harness_py/cli.py`
- Modify: `harness_py/live_chat.py`
- Modify: `harness_py/stage_prototype/runtime.py`
- Modify: `harness_py/__init__.py`
- Modify: `harness_py/tests/test_harness_py.py`
- Modify: `harness_py/tests/test_stage_prototype.py`
- Modify: `research/golden-data/manifest.yaml`
- Modify: `research/golden-data/paper-packs/transformer-bert-gpt.yaml`
- Delete: `harness_py/harness.py`
- Delete: `research/golden-data/cases/seed-smoke.yaml`
- Delete: `research/golden-data/cases/paradigm-primary.yaml`

**Interfaces:**

- Produces: `load_dataset(path) -> GoldenDataset` for v2 only.
- Produces: `BehaviorScorer.score_case(dataset, case, run) -> CaseScore`.
- Produces: `BehaviorScorer.score_dataset(dataset, runs) -> JsonMap`.
- Produces: `GoldenFixtureHarness.run_case(dataset, case) -> HarnessRun`.
- Produces: `LiveResearchChatHarness.run_case(dataset, case) -> HarnessRun`.
- Preserves: the normalized `GoldenDataset.paper_records_by_id`, `anchors_by_id`, `citation_edges`, and `reading_models_by_paper_id` consumed by `ReadingCorpusTools`.

- [ ] **Step 1: Write focused failing tests for the v2 contract**

Create `harness_py/tests/test_golden_v2.py` with these initial tests:

```python
from __future__ import annotations

import unittest
from copy import deepcopy

from harness_py.dataset import load_dataset
from harness_py.golden_fixture import GoldenFixtureHarness
from harness_py.scoring import BehaviorScorer


class GoldenV2Test(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = load_dataset("research/golden-data/manifest.yaml")

    def test_committed_dataset_is_v2_and_has_nine_cases(self) -> None:
        self.assertEqual("harness-golden-data/v2", self.dataset.manifest["schema_version"])
        self.assertEqual(9, len(self.dataset.cases))
        self.assertEqual(5, len(self.dataset.paper_records_by_id))
        self.assertEqual(7, len(self.dataset.anchors_by_id))
        for case in self.dataset.cases:
            self.assertEqual("harness-golden-case/v2", case["schema_version"])
            self.assertEqual("user", case["messages"][-1]["role"])
            for removed in (
                "question",
                "expected_intent",
                "expected_retrieval_plan",
                "gold_evidence",
                "gold_claims",
                "answer_contract",
                "required_trace",
                "compatibility_projection",
            ):
                self.assertNotIn(removed, case)

    def test_fixture_passes_all_committed_cases(self) -> None:
        runs = [GoldenFixtureHarness().run_case(self.dataset, case) for case in self.dataset.cases]
        report = BehaviorScorer().score_dataset(self.dataset, runs)

        self.assertEqual(9, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(score["hard_pass"] for score in report["scores"]))

    def test_scorer_rejects_a_missing_required_anchor(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        broken = deepcopy(run)
        broken["evidence_ledger"]["items"] = []

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "REQUIRED_ANCHOR_MISSING:transformer_adam_training_params_span",
            score.dimensions["retrieval"].errors,
        )

    def test_scorer_rejects_a_wrong_structured_fact(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        broken = deepcopy(run)
        broken["research_answer"]["fields"]["beta2"] = "0.999"

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertFalse(score.hard_pass)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["content"].errors)

    def test_scorer_does_not_grade_internal_paradigm_or_stage_order(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["intent_frame"]["primary_paradigm"] = "deep_comparison"
        run["stage_trace"] = list(reversed(run.get("stage_trace", [])))

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the focused test and verify it fails against v1**

Run:

```bash
python3 -m unittest harness_py.tests.test_golden_v2 -v
```

Expected: failure during import or dataset assertions because `GoldenFixtureHarness`, `BehaviorScorer`, and the v2 schema do not exist.

- [ ] **Step 3: Add v2 constants and case accessors**

In `harness_py/models.py`, replace the golden and score constants with:

```python
GOLDEN_SCHEMA_VERSION = "harness-golden-data/v2"
GOLDEN_CASE_SCHEMA_VERSION = "harness-golden-case/v2"
PAPER_PACK_SCHEMA_VERSION = "harness-paper-pack/v2"
RUN_TRACE_SCHEMA_VERSION = "harness-run-trace/v1"
SCORE_REPORT_SCHEMA_VERSION = "harness-score-report/v2"
ARTIFACT_CONTRACT_SCHEMA_VERSION = "research-harness-artifacts/v1"
```

Create `harness_py/golden_case.py`:

```python
from __future__ import annotations

from dataclasses import replace

from .conversation import ConversationState
from .models import GoldenDataset, JsonMap, as_list, child_map


OUTCOMES = {"answered", "needs_clarification", "abstained", "partial"}
CITATION_POLICIES = {"required", "optional", "forbidden"}


def case_messages(case: JsonMap) -> list[JsonMap]:
    return [child_map(item) for item in as_list(case.get("messages"))]


def case_question(case: JsonMap) -> str:
    messages = case_messages(case)
    return str(messages[-1].get("content") or "") if messages else ""


def case_history(case: JsonMap) -> list[JsonMap]:
    return case_messages(case)[:-1]


def case_expect(case: JsonMap) -> JsonMap:
    return child_map(case.get("expect"))


def pack_for_case(dataset: GoldenDataset, case: JsonMap) -> JsonMap:
    pack_id = str(case.get("paper_pack") or "")
    for pack in dataset.paper_packs:
        if str(pack.get("id") or "") == pack_id:
            return pack
    raise ValueError(f"unknown paper pack for case {case.get('id')}: {pack_id}")


def paper_ids_for_case(dataset: GoldenDataset, case: JsonMap) -> list[str]:
    return [
        str(child_map(paper).get("id"))
        for paper in as_list(pack_for_case(dataset, case).get("papers"))
        if child_map(paper).get("id")
    ]


def conversation_state_for_case(dataset: GoldenDataset, case: JsonMap) -> ConversationState:
    history: list[JsonMap] = []
    turn_index = 0
    for message in case_history(case):
        role = str(message.get("role") or "")
        content = str(message.get("content") or "")
        if role == "user":
            turn_index += 1
            history.append({"role": "user", "turn_index": turn_index, "content": content})
        else:
            history.append({
                "role": "assistant",
                "turn_index": max(turn_index, 1),
                "content": content,
                "summary": content,
            })
    return replace(
        ConversationState.new(
            f"golden_{case.get('id')}",
            scope_paper_ids=paper_ids_for_case(dataset, case),
        ),
        turn_index=turn_index,
        message_history=history,
    )
```

- [ ] **Step 4: Replace the loader with strict v2 loading and normalized runtime records**

In `harness_py/dataset.py`, keep `_load_yaml`, `_load_reading_models`, `_index_by_id`, and `_find_repo_root`, but replace `load_dataset` with this flow:

```python
def load_dataset(manifest_path: str | Path, repo_root: str | Path | None = None) -> GoldenDataset:
    manifest_path = Path(manifest_path).resolve()
    root = Path(repo_root).resolve() if repo_root is not None else _find_repo_root(manifest_path)
    manifest = _load_yaml(manifest_path)
    if manifest.get("schema_version") != GOLDEN_SCHEMA_VERSION:
        raise ValueError(f"unsupported manifest schema: {manifest.get('schema_version')!r}")

    base = manifest_path.parent
    packs = [
        _load_yaml(_resolve_authoring_path(base, root, path))
        for path in as_list(manifest.get("paper_packs"))
    ]
    cases: list[JsonMap] = []
    for path in as_list(manifest.get("case_files")):
        loaded = _load_yaml(_resolve_authoring_path(base, root, path))
        cases.extend(child_map(case) for case in as_list(loaded.get("cases")))

    _validate_packs(packs)
    _validate_cases(cases, packs)
    paper_records = _normalized_paper_records(root, packs)
    anchors = _normalized_anchors(packs)
    citation_edges = [edge for pack in packs for edge in as_list(pack.get("citation_edges"))]
    warnings: list[str] = []
    reading_models = _load_reading_models(root, paper_records, warnings)
    return GoldenDataset(
        root=root,
        manifest_path=manifest_path,
        manifest=manifest,
        paper_packs=packs,
        cases=cases,
        paper_records_by_id=paper_records,
        anchors_by_id=anchors,
        citation_edges=citation_edges,
        reading_models_by_paper_id=reading_models,
        load_warnings=warnings,
    )
```

Add strict validators with these rules and exact errors:

```python
REMOVED_CASE_FIELDS = {
    "question",
    "expected_result",
    "expected_intent",
    "expected_retrieval_plan",
    "corpus_scope",
    "gold_evidence",
    "gold_claims",
    "answer_contract",
    "required_trace",
    "compatibility_projection",
}


def _validate_packs(packs: list[JsonMap]) -> None:
    pack_ids: set[str] = set()
    paper_ids: set[str] = set()
    anchor_ids: set[str] = set()
    for pack in packs:
        if pack.get("schema_version") != PAPER_PACK_SCHEMA_VERSION:
            raise ValueError(f"unsupported paper pack schema: {pack.get('schema_version')!r}")
        pack_id = str(pack.get("id") or "")
        if not pack_id or pack_id in pack_ids:
            raise ValueError(f"invalid or duplicate paper pack id: {pack_id!r}")
        pack_ids.add(pack_id)
        local_papers = {
            str(child_map(paper).get("id"))
            for paper in as_list(pack.get("papers"))
            if child_map(paper).get("id")
        }
        if len(local_papers) != len(as_list(pack.get("papers"))):
            raise ValueError(f"paper pack {pack_id} has missing or duplicate paper ids")
        overlap = paper_ids & local_papers
        if overlap:
            raise ValueError(f"duplicate paper ids across packs: {sorted(overlap)}")
        paper_ids.update(local_papers)
        local_anchor_ids: set[str] = set()
        for raw_anchor in as_list(pack.get("anchors")):
            anchor = child_map(raw_anchor)
            anchor_id = str(anchor.get("id") or "")
            paper_id = str(anchor.get("paper") or "")
            if not anchor_id or anchor_id in anchor_ids:
                raise ValueError(f"invalid or duplicate anchor id: {anchor_id!r}")
            if paper_id not in local_papers:
                raise ValueError(f"anchor {anchor_id} references unknown paper {paper_id}")
            if not str(anchor.get("quote") or "").strip():
                raise ValueError(f"anchor {anchor_id} is missing quote")
            anchor_ids.add(anchor_id)
            local_anchor_ids.add(anchor_id)
        for raw_edge in as_list(pack.get("citation_edges")):
            edge = child_map(raw_edge)
            if edge.get("from_paper_id") not in local_papers or edge.get("to_paper_id") not in local_papers:
                raise ValueError(f"paper pack {pack_id} has a citation edge with an unknown paper")
            evidence_anchor_id = str(edge.get("evidence_anchor_id") or "")
            if evidence_anchor_id and evidence_anchor_id not in local_anchor_ids:
                raise ValueError(
                    f"paper pack {pack_id} citation edge references unknown anchor {evidence_anchor_id}"
                )


def _validate_cases(cases: list[JsonMap], packs: list[JsonMap]) -> None:
    packs_by_id = {str(pack["id"]): pack for pack in packs}
    anchors_by_pack = {
        pack_id: {str(child_map(anchor).get("id")) for anchor in as_list(pack.get("anchors"))}
        for pack_id, pack in packs_by_id.items()
    }
    papers_by_pack = {
        pack_id: {str(child_map(paper).get("id")) for paper in as_list(pack.get("papers"))}
        for pack_id, pack in packs_by_id.items()
    }
    case_ids: set[str] = set()
    for case in cases:
        case_id = str(case.get("id") or "")
        if case.get("schema_version") != GOLDEN_CASE_SCHEMA_VERSION:
            raise ValueError(f"case {case_id} has unsupported schema {case.get('schema_version')!r}")
        if not case_id or case_id in case_ids:
            raise ValueError(f"invalid or duplicate case id: {case_id!r}")
        case_ids.add(case_id)
        removed = sorted(REMOVED_CASE_FIELDS & set(case))
        if removed:
            raise ValueError(f"case {case_id} contains removed v1 fields: {removed}")
        if not str(case.get("paradigm") or "").strip():
            raise ValueError(f"case {case_id} is missing paradigm")
        pack_id = str(case.get("paper_pack") or "")
        if pack_id not in packs_by_id:
            raise ValueError(f"case {case_id} references unknown paper pack {pack_id}")
        messages = [child_map(item) for item in as_list(case.get("messages"))]
        if not messages or messages[-1].get("role") != "user":
            raise ValueError(f"case {case_id} must end with a user message")
        for message in messages:
            if message.get("role") not in {"user", "assistant"}:
                raise ValueError(f"case {case_id} has invalid message role {message.get('role')!r}")
            if not str(message.get("content") or "").strip():
                raise ValueError(f"case {case_id} has an empty message")
        expectation = child_map(case.get("expect"))
        if expectation.get("outcome") not in OUTCOMES:
            raise ValueError(f"case {case_id} has invalid outcome {expectation.get('outcome')!r}")
        if expectation.get("citations", "optional") not in CITATION_POLICIES:
            raise ValueError(f"case {case_id} has invalid citation policy")
        for bucket in ("required", "forbidden"):
            for paper_id in as_list(child_map(expectation.get("papers")).get(bucket)):
                if paper_id not in papers_by_pack[pack_id]:
                    raise ValueError(f"case {case_id} references unknown paper {paper_id}")
            for anchor_id in as_list(child_map(expectation.get("evidence")).get(bucket)):
                if anchor_id not in anchors_by_pack[pack_id]:
                    raise ValueError(f"case {case_id} references unknown anchor {anchor_id}")
        for raw_claim in as_list(expectation.get("claims")):
            claim = child_map(raw_claim)
            if not str(claim.get("text") or "").strip():
                raise ValueError(f"case {case_id} contains a claim without text")
            for anchor_id in as_list(claim.get("evidence")):
                if anchor_id not in anchors_by_pack[pack_id]:
                    raise ValueError(f"case {case_id} claim references unknown anchor {anchor_id}")
```

Normalize v2 paper and anchor authoring fields into the existing tool-facing shape:

```python
def _normalized_paper_records(root: Path, packs: list[JsonMap]) -> dict[str, JsonMap]:
    records: dict[str, JsonMap] = {}
    for pack in packs:
        pack_id = str(pack["id"])
        for raw_paper in as_list(pack.get("papers")):
            paper = child_map(raw_paper)
            paper_id = str(paper["id"])
            records[paper_id] = {
                "paper_id": paper_id,
                "role": paper.get("role"),
                "identity": {
                    key: paper.get(key)
                    for key in (
                        "title", "authors", "year", "venue", "doi", "arxiv_id", "version_label"
                    )
                    if paper.get(key) is not None
                },
                "source_assets": {
                    "source_url": paper.get("source_url"),
                    "reading_model_path": str(
                        Path("data/golden")
                        / pack_id
                        / "reading-models"
                        / f"{paper_id}.reading-model.json"
                    ),
                },
            }
    return records


def _normalized_anchors(packs: list[JsonMap]) -> dict[str, JsonMap]:
    anchors: dict[str, JsonMap] = {}
    for pack in packs:
        for raw_anchor in as_list(pack.get("anchors")):
            anchor = child_map(raw_anchor)
            anchor_id = str(anchor["id"])
            anchors[anchor_id] = {
                "anchor_id": anchor_id,
                "paper_id": anchor.get("paper"),
                "role": anchor.get("role", "supports"),
                "element": {
                    "type": anchor.get("type", "paragraph"),
                    "page": anchor.get("page"),
                    "section": anchor.get("section"),
                },
                "selector": {"exact_text": anchor.get("quote")},
                "normalized_facts": child_map(anchor.get("facts")),
            }
    return anchors
```

Import `GOLDEN_CASE_SCHEMA_VERSION`, `PAPER_PACK_SCHEMA_VERSION`, `OUTCOMES`, and `CITATION_POLICIES` at the top of `dataset.py`.

- [ ] **Step 5: Replace trace scoring with four behavior dimensions**

Replace `harness_py/scoring.py` with a `BehaviorScorer` built around these public types:

```python
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .golden_case import case_expect
from .models import SCORE_REPORT_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map


@dataclass(frozen=True)
class DimensionScore:
    status: str
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> JsonMap:
        return {"status": self.status, "errors": self.errors}


@dataclass(frozen=True)
class CaseScore:
    case_id: str
    hard_pass: bool
    dimensions: dict[str, DimensionScore]
    review: JsonMap

    def to_dict(self) -> JsonMap:
        return {
            "case_id": self.case_id,
            "hard_pass": self.hard_pass,
            "dimensions": {key: value.to_dict() for key, value in self.dimensions.items()},
            "review": self.review,
        }


class BehaviorScorer:
    def score_case(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> CaseScore:
        dimensions = {
            "outcome": self._score_outcome(case, run),
            "retrieval": self._score_retrieval(case, run),
            "content": self._score_content(case, run),
            "grounding": self._score_grounding(case, run),
        }
        criteria = [str(item) for item in as_list(case.get("review"))]
        criteria.extend(
            str(child_map(claim).get("text"))
            for claim in as_list(case_expect(case).get("claims"))
            if child_map(claim).get("text")
        )
        return CaseScore(
            case_id=str(case.get("id") or ""),
            hard_pass=all(score.status != "fail" for score in dimensions.values()),
            dimensions=dimensions,
            review={
                "status": "not_run" if criteria else "not_required",
                "criteria": criteria,
            },
        )

    def score_dataset(self, dataset: GoldenDataset, runs: list[JsonMap]) -> JsonMap:
        runs_by_case = {str(run.get("case_id") or run.get("question_id")): run for run in runs}
        scores = [
            self.score_case(dataset, case, runs_by_case.get(str(case.get("id")), {}))
            for case in dataset.cases
        ]
        passed = sum(1 for score in scores if score.hard_pass)
        return {
            "schema_version": SCORE_REPORT_SCHEMA_VERSION,
            "dataset_id": dataset.manifest.get("dataset_id"),
            "case_count": len(scores),
            "passed_count": passed,
            "failed_count": len(scores) - passed,
            "scores": [score.to_dict() for score in scores],
        }
```

Implement the four methods with these exact checks:

```python
    def _score_outcome(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        expected = str(expectation.get("outcome") or "")
        actual = _actual_outcome(run)
        errors = [] if expected == actual else [f"OUTCOME_MISMATCH:expected={expected}:actual={actual}"]
        expected_reason = str(expectation.get("reason") or "")
        actual_reason = str(child_map(run.get("research_answer")).get("outcome_reason") or "")
        if expected_reason and expected_reason != actual_reason:
            errors.append(
                f"OUTCOME_REASON_MISMATCH:expected={expected_reason}:actual={actual_reason}"
            )
        return _dimension(errors)

    def _score_retrieval(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        evidence = _accepted_evidence(run)
        paper_ids = {str(item.get("paper_id")) for item in evidence if item.get("paper_id")}
        anchor_ids = {str(item.get("matched_anchor_id")) for item in evidence if item.get("matched_anchor_id")}
        errors: list[str] = []
        for paper_id in as_list(child_map(expectation.get("papers")).get("required")):
            if paper_id not in paper_ids:
                errors.append(f"REQUIRED_PAPER_MISSING:{paper_id}")
        for paper_id in as_list(child_map(expectation.get("papers")).get("forbidden")):
            if paper_id in paper_ids:
                errors.append(f"FORBIDDEN_PAPER_ACCEPTED:{paper_id}")
        for anchor_id in as_list(child_map(expectation.get("evidence")).get("required")):
            if anchor_id not in anchor_ids:
                errors.append(f"REQUIRED_ANCHOR_MISSING:{anchor_id}")
        for anchor_id in as_list(child_map(expectation.get("evidence")).get("forbidden")):
            if anchor_id in anchor_ids:
                errors.append(f"FORBIDDEN_ANCHOR_ACCEPTED:{anchor_id}")
        configured = bool(child_map(expectation.get("papers")) or child_map(expectation.get("evidence")))
        return _dimension(errors, configured)

    def _score_content(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        facts = child_map(case_expect(case).get("facts"))
        if not facts:
            return DimensionScore("not_applicable")
        actual = child_map(child_map(run.get("research_answer")).get("fields"))
        actual_values = {_scalar_string(value) for value in _flatten_values(actual)}
        errors: list[str] = []
        for key, expected in facts.items():
            normalized = _scalar_string(expected)
            if key in actual and _scalar_string(actual.get(key)) != normalized:
                errors.append(f"FACT_MISMATCH:{key}")
            elif key not in actual and normalized not in actual_values:
                errors.append(f"FACT_MISSING:{key}")
        return _dimension(errors)

    def _score_grounding(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        policy = str(expectation.get("citations") or "optional")
        evidence = _accepted_evidence(run)
        evidence_by_id = {
            str(item.get("evidence_id")): item
            for item in evidence
            if item.get("evidence_id")
        }
        answer = child_map(run.get("research_answer"))
        cited = {str(item) for item in as_list(answer.get("cited_evidence_ids")) if item}
        cited_claims = {str(item) for item in as_list(answer.get("cited_claim_ids")) if item}
        errors: list[str] = []
        if policy == "required" and not cited:
            errors.append("CITATIONS_REQUIRED")
        if policy == "forbidden" and cited:
            errors.append("CITATIONS_FORBIDDEN")
        unknown = sorted(cited - set(evidence_by_id))
        if unknown:
            errors.append("UNKNOWN_CITED_EVIDENCE:" + ",".join(unknown))
        if policy == "required":
            for anchor_id in as_list(child_map(expectation.get("evidence")).get("required")):
                anchor_evidence = {
                    evidence_id
                    for evidence_id, item in evidence_by_id.items()
                    if item.get("matched_anchor_id") == anchor_id
                }
                if not (anchor_evidence & cited):
                    errors.append(f"REQUIRED_ANCHOR_NOT_CITED:{anchor_id}")
        claims = as_list(child_map(run.get("claim_graph")).get("claims"))
        claim_ids = {
            str(child_map(claim).get("claim_id"))
            for claim in claims
            if child_map(claim).get("claim_id")
        }
        unknown_claims = sorted(cited_claims - claim_ids)
        if unknown_claims:
            errors.append("UNKNOWN_CITED_CLAIM:" + ",".join(unknown_claims))
        for claim in claims:
            claim_map = child_map(claim)
            if _normalize_claim_status(claim_map.get("status")) != "supported":
                continue
            support = {str(item) for item in as_list(claim_map.get("supporting_evidence_ids"))}
            if not support:
                errors.append(f"SUPPORTED_CLAIM_WITHOUT_EVIDENCE:{claim_map.get('claim_id')}")
            elif not support <= set(evidence_by_id):
                errors.append(f"SUPPORTED_CLAIM_CITES_UNKNOWN_EVIDENCE:{claim_map.get('claim_id')}")
        configured = policy != "optional" or bool(cited) or bool(claims)
        return _dimension(errors, configured)
```

Add helpers:

```python
def _dimension(errors: list[str], configured: bool = True) -> DimensionScore:
    if not configured:
        return DimensionScore("not_applicable")
    return DimensionScore("fail" if errors else "pass", errors)


def _accepted_evidence(run: JsonMap) -> list[JsonMap]:
    return [
        child_map(item)
        for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
        if child_map(item).get("evidence_id")
        and child_map(item).get("citeable") is not False
        and child_map(item).get("evidence_quality") != "rejected"
        and child_map(item).get("element_type") != "paper_candidate"
    ]


def _actual_outcome(run: JsonMap) -> str:
    answer = child_map(run.get("research_answer"))
    explicit = str(answer.get("outcome") or "")
    if explicit in {"answered", "needs_clarification", "abstained", "partial"}:
        return explicit
    status = str(answer.get("status") or run.get("status") or "").upper()
    if status == "COMPLETED":
        return "answered"
    if status == "NEEDS_CLARIFICATION":
        return "needs_clarification"
    if status == "INCOMPLETE_PRECISE":
        supported = any(
            _normalize_claim_status(child_map(claim).get("status")) == "supported"
            for claim in as_list(child_map(run.get("claim_graph")).get("claims"))
        )
        return "partial" if supported and _accepted_evidence(run) else "abstained"
    return "technical_failure"


def _flatten_values(value: Any) -> list[Any]:
    if isinstance(value, dict):
        return [item for child in value.values() for item in _flatten_values(child)]
    if isinstance(value, list):
        return [item for child in value for item in _flatten_values(child)]
    return [value]
```

Retain `_scalar_string` and `_normalize_claim_status` from the current file. Delete `TraceScorer`, `StructuralTraceScorer`, primary-paradigm checks, retrieval-strategy checks, trace-obligation checks, and artifact-contract validation from golden scoring.

- [ ] **Step 6: Replace the v1 fake harness with a compact fixture**

Create `harness_py/golden_fixture.py`. It may read `expect` because it is explicitly a scorer fixture, not the real harness. Implement this public shape:

```python
class GoldenFixtureHarness:
    harness_id = "golden_fixture_v2"

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        expectation = case_expect(case)
        evidence = [
            _fixture_evidence(dataset, str(anchor_id))
            for anchor_id in as_list(child_map(expectation.get("evidence")).get("required"))
        ]
        evidence_by_anchor = {
            str(item["matched_anchor_id"]): str(item["evidence_id"])
            for item in evidence
        }
        claims = _fixture_claims(expectation, evidence_by_anchor)
        outcome = str(expectation.get("outcome"))
        status = {
            "answered": "COMPLETED",
            "needs_clarification": "NEEDS_CLARIFICATION",
            "abstained": "INCOMPLETE_PRECISE",
            "partial": "INCOMPLETE_PRECISE",
        }[outcome]
        cited = []
        if expectation.get("citations", "optional") != "forbidden":
            cited = [str(item["evidence_id"]) for item in evidence]
        case_id = str(case["id"])
        answer = {
            "answer_id": stable_id("answer", case_id),
            "question_id": case_id,
            "status": status,
            "outcome": outcome,
            "outcome_reason": str(expectation.get("reason") or ""),
            "answer_type": "golden_fixture",
            "summary": case_question(case),
            "sections": [],
            "markdown": case_question(case),
            "fields": dict(child_map(expectation.get("facts"))),
            "cited_claim_ids": [str(claim["claim_id"]) for claim in claims],
            "cited_evidence_ids": cited,
            "reasoning_artifact_ids": [stable_id("reasoning", case_id)],
            "verification_id": stable_id("verification", case_id),
        }
        return _fixture_run(case_id, status, evidence, claims, answer)
```

`_fixture_evidence` must use the normalized anchor and paper record:

```python
def _fixture_evidence(dataset: GoldenDataset, anchor_id: str) -> JsonMap:
    anchor = dataset.anchors_by_id[anchor_id]
    paper_id = str(anchor["paper_id"])
    identity = child_map(dataset.paper_records_by_id[paper_id].get("identity"))
    element = child_map(anchor.get("element"))
    return {
        "evidence_id": stable_id("fixture_evidence", anchor_id),
        "matched_anchor_id": anchor_id,
        "paper_id": paper_id,
        "title": identity.get("title") or paper_id,
        "paper_version": identity.get("version_label") or identity.get("year") or "unknown",
        "section": element.get("section") or "unsectioned",
        "page": element.get("page") or "unknown",
        "location": f"golden_anchor:{anchor_id}",
        "element_type": element.get("type") or "paragraph",
        "span_text": child_map(anchor.get("selector")).get("exact_text") or "",
        "retrieval_strategy": "golden_fixture",
        "relevance_score": 1.0,
        "evidence_quality": "verified",
        "supports_claim_ids": [],
        "refutes_claim_ids": [],
    }
```

`_fixture_claims` must create one evidence-linked claim per authored claim. When a case has facts but no claims, create one supported claim per fact and link it to all fixture evidence. `_fixture_run` must emit the existing artifact keys so CLI output panes remain available: `intent_frame`, `retrieval_plan`, `stage_trace`, `evidence_ledger`, `claim_graph`, `reasoning_artifacts`, `verification_pass`, and `research_answer`.

Delete `harness_py/harness.py`; do not retain `ContractDrivenHarness` as an alias.

- [ ] **Step 7: Migrate the manifest and compact paper pack**

Replace `research/golden-data/manifest.yaml` with:

```yaml
schema_version: harness-golden-data/v2
dataset_id: harness_golden_seed
paper_packs:
  - paper-packs/transformer-bert-gpt.yaml
case_files:
  - cases/core.yaml
```

Replace `research/golden-data/paper-packs/transformer-bert-gpt.yaml` with the compact authored shape below. Preserve the current five identities, three citation edges, and seven anchor facts exactly:

```yaml
schema_version: harness-paper-pack/v2
id: transformer_bert_gpt

papers:
  - id: attention_is_all_you_need_2017
    role: target
    title: "Attention Is All You Need"
    authors: ["Ashish Vaswani", "Noam Shazeer"]
    year: 2017
    venue: NeurIPS
    arxiv_id: "1706.03762"
    version_label: "arXiv v7 (2023-08-02) / NeurIPS 2017"
    source_url: "https://arxiv.org/pdf/1706.03762v7"
  - id: adam_2014
    role: predecessor
    title: "Adam: A Method for Stochastic Optimization"
    authors: ["Diederik P. Kingma", "Jimmy Ba"]
    year: 2014
    venue: ICLR
    arxiv_id: "1412.6980"
    version_label: "arXiv v9 / ICLR 2015 camera-ready"
    source_url: "https://arxiv.org/pdf/1412.6980v9"
  - id: bert_2018
    role: successor
    title: "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding"
    authors: ["Jacob Devlin", "Ming-Wei Chang"]
    year: 2018
    venue: NAACL
    arxiv_id: "1810.04805"
    version_label: "arXiv v2 (2019-05-24) / NAACL 2019"
    source_url: "https://arxiv.org/pdf/1810.04805v2"
  - id: gpt2_2019
    role: successor
    title: "Language Models are Unsupervised Multitask Learners"
    authors: ["Alec Radford"]
    year: 2019
    venue: "OpenAI technical report"
    version_label: "OpenAI technical report PDF (2019-02)"
    source_url: "https://cdn.openai.com/better-language-models/language_models_are_unsupervised_multitask_learners.pdf"
  - id: gpt3_2020
    role: hard_distractor
    title: "Language Models are Few-Shot Learners"
    authors: ["Tom B. Brown"]
    year: 2020
    venue: NeurIPS
    arxiv_id: "2005.14165"
    version_label: "arXiv v4 (2020-07-22) / NeurIPS 2020"
    source_url: "https://arxiv.org/pdf/2005.14165v4"

citation_edges:
  - from_paper_id: bert_2018
    to_paper_id: attention_is_all_you_need_2017
    edge_type: uses_transformer_encoder
    evidence_anchor_id: bert_transformer_encoder_background
  - from_paper_id: gpt3_2020
    to_paper_id: gpt2_2019
    edge_type: uses_gpt2_style_architecture
    evidence_anchor_id: gpt3_uses_gpt2_architecture
  - from_paper_id: gpt2_2019
    to_paper_id: attention_is_all_you_need_2017
    edge_type: uses_transformer_decoder_language_model
    evidence_anchor_id: gpt2_transformer_language_model

anchors:
  - id: transformer_encoder_decoder_architecture
    paper: attention_is_all_you_need_2017
    type: abstract
    page: 1
    quote: "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder."
    facts:
      architecture_role: encoder-decoder architecture
  - id: transformer_adam_training_params_span
    paper: attention_is_all_you_need_2017
    type: paragraph
    page: 7
    section: "5.3 Optimizer"
    quote: "We used the Adam optimizer"
    facts:
      adam_beta1: "0.9"
      adam_beta2: "0.98"
      adam_epsilon: "1e-9"
  - id: adam_default_beta2_span
    paper: adam_2014
    role: qualifies
    type: algorithm
    page: 2
    section: Algorithm
    quote: "Good default settings for the tested machine learning problems are"
    facts:
      adam_default_alpha: "0.001"
      adam_default_beta1: "0.9"
      adam_default_beta2: "0.999"
      adam_default_epsilon: "1e-8"
  - id: bert_transformer_encoder_background
    paper: bert_2018
    role: background
    type: paragraph
    page: 3
    section: "3 BERT"
    quote: "multi-layer bidirectional Transformer encoder"
    facts:
      architecture_role: bidirectional Transformer encoder
  - id: bert_masked_lm_pretraining
    paper: bert_2018
    type: paragraph
    page: 4
    section: "3.1 Pre-training BERT"
    quote: "masked LM"
    facts:
      training_objective: masked language modeling
  - id: gpt2_transformer_language_model
    paper: gpt2_2019
    type: paragraph
    page: 4
    section: "2.3. Model"
    quote: "We use a Transformer (Vaswani et al., 2017) based architecture for our LMs"
    facts:
      architecture_role: Transformer language model
      training_objective: left-to-right language modeling
  - id: gpt3_uses_gpt2_architecture
    paper: gpt3_2020
    type: paragraph
    page: 8
    section: "2.1 Model and Architectures"
    quote: "We use the same model and architecture as"
    facts:
      architecture_lineage: GPT-3 uses the same model and architecture family as GPT-2
```

- [ ] **Step 8: Migrate the nine cases without internal-plan expectations**

Create `research/golden-data/cases/core.yaml` with this exact initial content:

```yaml
cases:
  - schema_version: harness-golden-case/v2
    id: transformer_adam_params_001
    split: seed
    paradigm: precision_fact_extraction
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Transformer original paper used Adam with what beta1, beta2, and epsilon?"
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017]
        forbidden: [bert_2018, gpt3_2020]
      evidence:
        required: [transformer_adam_training_params_span]
        forbidden: [bert_transformer_encoder_background]
      facts:
        beta1: "0.9"
        beta2: "0.98"
        epsilon: "1e-9"
      citations: required

  - schema_version: harness-golden-case/v2
    id: attention_paper_ambiguous_001
    split: stress
    paradigm: ambiguity_resolution
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "attention paper"
    expect:
      outcome: needs_clarification
      citations: forbidden

  - schema_version: harness-golden-case/v2
    id: gpt5_architecture_boundary_001
    split: stress
    paradigm: uncertainty_knowledge_boundary
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "What are the architecture details of GPT-5?"
    expect:
      outcome: abstained
      citations: optional
    review:
      - "States that the available corpus does not verify GPT-5 architecture details."
      - "Does not infer GPT-5 architecture from GPT-3."

  - schema_version: harness-golden-case/v2
    id: bert_transformer_role_001
    split: seed
    paradigm: precision_fact_extraction
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Does BERT use the Transformer as an encoder or decoder?"
    expect:
      outcome: answered
      papers:
        required: [bert_2018]
      evidence:
        required: [bert_transformer_encoder_background]
      facts:
        transformer_role: bidirectional Transformer encoder
      citations: required

  - schema_version: harness-golden-case/v2
    id: bert_vs_transformer_comparison_001
    split: seed
    paradigm: deep_comparison
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Compare the original Transformer and BERT on architecture role, directionality, and training objective."
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017, bert_2018]
        forbidden: [gpt2_2019]
      evidence:
        required:
          - transformer_encoder_decoder_architecture
          - bert_transformer_encoder_background
          - bert_masked_lm_pretraining
        forbidden: [gpt2_transformer_language_model]
      claims:
        - text: "The original Transformer is presented as an encoder-decoder architecture."
          evidence: [transformer_encoder_decoder_architecture]
        - text: "BERT uses a bidirectional Transformer encoder."
          evidence: [bert_transformer_encoder_background]
        - text: "BERT is pre-trained with a masked language modeling objective."
          evidence: [bert_masked_lm_pretraining]
      citations: required
    review:
      - "Compares all three requested axes rather than producing two unrelated summaries."

  - schema_version: harness-golden-case/v2
    id: transformer_to_bert_genealogy_001
    split: seed
    paradigm: association_influence_genealogy
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Show the direct technical lineage from Attention Is All You Need to BERT and explain the cited relationship."
    expect:
      outcome: answered
      papers:
        required: [bert_2018]
      evidence:
        required: [bert_transformer_encoder_background]
      claims:
        - text: "BERT's direct relationship to Attention Is All You Need is that it uses a bidirectional Transformer encoder."
          evidence: [bert_transformer_encoder_background]
      citations: required

  - schema_version: harness-golden-case/v2
    id: adam_beta2_conflict_001
    split: stress
    paradigm: contradiction_resolution
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Some sources say Adam beta2 is 0.999, but the Transformer paper used 0.98. Resolve this conflict."
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017, adam_2014]
        forbidden: [bert_2018]
      evidence:
        required: [transformer_adam_training_params_span, adam_default_beta2_span]
      claims:
        - text: "The Adam paper gives beta2 = 0.999 as a recommended default."
          evidence: [adam_default_beta2_span]
        - text: "The Transformer paper used beta2 = 0.98 in its own training setup."
          evidence: [transformer_adam_training_params_span]
      citations: required
    review:
      - "Explains that a default recommendation and a paper-specific setting are not contradictory."

  - schema_version: harness-golden-case/v2
    id: transformer_optimizer_reproduction_001
    split: seed
    paradigm: methodology_reproduction
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Build the optimizer portion of a strict reproduction checklist for Attention Is All You Need: give the optimizer, beta1, beta2, and epsilon exactly as used in the paper, without substituting generic Adam defaults."
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017]
        forbidden: [adam_2014, bert_2018]
      evidence:
        required: [transformer_adam_training_params_span]
        forbidden: [adam_default_beta2_span, bert_transformer_encoder_background]
      facts:
        optimizer: Adam
        beta1: "0.9"
        beta2: "0.98"
        epsilon: "1e-9"
      citations: required

  - schema_version: harness-golden-case/v2
    id: gpt3_to_transformer_multihop_001
    split: seed
    paradigm: complex_multihop_reasoning
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Using only the available papers, build the two-hop architecture chain from GPT-3 through GPT-2 to Attention Is All You Need. State what each hop establishes and do not invent a direct GPT-3-to-Transformer citation."
    expect:
      outcome: answered
      papers:
        required: [gpt3_2020, gpt2_2019]
        forbidden: [bert_2018]
      evidence:
        required: [gpt3_uses_gpt2_architecture, gpt2_transformer_language_model]
        forbidden: [bert_transformer_encoder_background]
      claims:
        - text: "GPT-3 uses the same model and architecture family as GPT-2."
          evidence: [gpt3_uses_gpt2_architecture]
        - text: "GPT-2 uses a Transformer-based architecture for its language models."
          evidence: [gpt2_transformer_language_model]
        - text: "The supported chain is GPT-3 to GPT-2 to Attention Is All You Need; no direct GPT-3-to-Transformer citation is asserted."
          evidence: [gpt3_uses_gpt2_architecture, gpt2_transformer_language_model]
      citations: required
```

Delete the two v1 case files after `core.yaml` is present.

- [ ] **Step 9: Route real golden runs through the live conversation harness**

Add this method to `LiveResearchChatHarness`:

```python
    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        state = conversation_state_for_case(dataset, case)
        run, _ = self.run_turn(dataset, state, case_question(case))
        run["case_id"] = str(case["id"])
        run["question_id"] = str(case["id"])
        return run
```

Import `case_question` and `conversation_state_for_case`. This method must not pass `case["expect"]` or `case["paradigm"]` to `TurnInterpreter`, `ResearchAgentHarness`, or stage prompts.

In `stage_prototype/runtime.py`, make `_turn_frame` and `_intent_artifact` obtain a message-based question only when `case.question.text` is absent:

```python
def _case_question(case: JsonMap) -> str:
    direct = str(child_map(case.get("question")).get("text") or "")
    if direct:
        return direct
    messages = [child_map(item) for item in as_list(case.get("messages"))]
    return str(messages[-1].get("content") or "") if messages else ""
```

Do not read `expect.facts` into `TurnFrame.required_answer_field_names`. A real golden run receives an empty list unless an ordinary live runtime case explicitly supplies `required_answer_field_names`.

Simplify `_live_case` in `live_chat.py` to contain only runtime data:

```python
return {
    "id": f"live_chat_{digest}",
    "question": {"text": effective_goal or question},
    "raw_question": question,
    "conversation_context": state.prompt_context(dataset),
    "corpus_scope": {"allowed_paper_ids": paper_ids},
}
```

In `cli.py`:

- Remove the root `--contracts` argument from golden commands.
- Use `GoldenFixtureHarness` and `BehaviorScorer` for `validate` and `run`.
- Use `LiveResearchChatHarness(...).run_case(dataset, case)` and `BehaviorScorer` for `agent-run`.
- Keep `chat` and `chat-shell` behavior unchanged.

Update `harness_py/__init__.py`:

```python
from .agent_harness import ResearchAgentHarness
from .dataset import load_dataset
from .golden_fixture import GoldenFixtureHarness
from .scoring import BehaviorScorer

__all__ = ["BehaviorScorer", "GoldenFixtureHarness", "ResearchAgentHarness", "load_dataset"]
```

- [ ] **Step 10: Migrate the existing synthetic tests without preserving v1 fields**

In both existing test modules:

- Replace synthetic `question` with `messages`.
- Replace `expected_result`, `expected_intent`, and `answer_contract` with `paradigm` plus `expect`.
- Replace `corpus_scope` with `paper_pack` and include a synthetic v2 paper pack in `GoldenDataset.paper_packs`.
- Replace `gold_evidence` and `gold_claims` with `expect.evidence`, `expect.facts`, and `expect.claims`.
- Replace `TraceScorer` and `StructuralTraceScorer` assertions with `BehaviorScorer` assertions.
- Delete tests that expect `PRIMARY_PARADIGM_MISMATCH`, required retrieval strategies, or trace-obligation failures.
- Keep stage-runtime tests that directly verify stage behavior; those are runtime tests, not golden scoring.

Use this exact synthetic case shape everywhere:

```python
case = {
    "schema_version": "harness-golden-case/v2",
    "id": "synthetic_case",
    "paradigm": "precision_fact_extraction",
    "paper_pack": "synthetic_pack",
    "messages": [{"role": "user", "content": "What is the synthetic answer?"}],
    "expect": {
        "outcome": "answered",
        "papers": {"required": ["synthetic_paper"]},
        "evidence": {"required": ["synthetic_anchor"]},
        "facts": {"answer": "42"},
        "citations": "required",
    },
}
```

Use this paper pack in synthetic datasets:

```python
paper_packs=[{
    "schema_version": "harness-paper-pack/v2",
    "id": "synthetic_pack",
    "papers": [{"id": "synthetic_paper", "title": "Synthetic Paper", "year": 2026}],
    "anchors": [{
        "id": "synthetic_anchor",
        "paper": "synthetic_paper",
        "page": 1,
        "section": "Result",
        "quote": "structured value forty two",
    }],
}],
```

Update `_GoldenCaseStageModel` to read:

```python
self.required_anchor_ids = [
    str(item)
    for item in as_list(child_map(case_expect(case).get("evidence")).get("required"))
]
```

Give the deterministic model one structured `TURN_DECISION` branch so committed cases exercise `LiveResearchChatHarness`:

```python
    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            expectation = case_expect(self.case)
            outcome = str(expectation.get("outcome"))
            paradigm = str(self.case.get("paradigm"))
            definition = PARADIGM_DEFINITIONS[paradigm]
            if outcome == "needs_clarification":
                arguments = {
                    "route": "clarify",
                    "effective_goal": case_question(self.case),
                    "task": {"verb": "clarify", "object": "paper_identity"},
                    "constraints": {},
                    "primary_paradigm": "ambiguity_resolution",
                    "answer_shape": "ambiguity_clarification",
                    "assumption": "",
                    "blocking_reason": "ambiguous_paper_identity",
                    "direct_reply": "",
                    "pending_interaction": {
                        "interaction_id": f"choice_{self.case['id']}",
                        "kind": "free_text",
                        "question": "Which paper do you mean?",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": [],
                    "required_evidence_types": [],
                    "required_capabilities": [],
                    "requires_corpus_observation": False,
                    "confidence": 1.0,
                }
            else:
                arguments = {
                    "route": "research",
                    "effective_goal": " ".join(
                        str(message.get("content") or "")
                        for message in case_messages(self.case)
                        if message.get("role") == "user"
                    ),
                    "task": {"verb": "research", "object": "papers"},
                    "constraints": {},
                    "primary_paradigm": paradigm,
                    "answer_shape": definition.default_answer_shape,
                    "assumption": "",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": None,
                    "paper_references": [],
                    "requested_aspects": [],
                    "required_evidence_types": [],
                    "required_capabilities": [paradigm],
                    "requires_corpus_observation": True,
                    "confidence": 1.0,
                }
            return ChatTurn(content="", tool_calls=[ToolCall(
                id=f"turn_{self.case['id']}",
                name="submit_turn_decision",
                arguments=arguments,
            )])
        return self._complete_research_turn(messages, tools, max_tokens)
```

Move the existing intent/stage body into `_complete_research_turn`. The deterministic intent payload may use `case["paradigm"]` because it is test-only. Its answer stage must use `expect.facts` and `expect.claims`; generate local runtime IDs such as `claim_1`, `claim_2`, and link them to the evidence IDs listed by each authored claim. For the uncertainty case, emit one `underdetermined` runtime claim with no support rather than indexing an absent authored claim.

- [ ] **Step 11: Run the v2 core tests and deterministic dataset command**

Run:

```bash
python3 -m unittest harness_py.tests.test_golden_v2 -v
python3 -m unittest discover -s harness_py/tests -v
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
```

Expected:

- Both unittest commands end with `OK`.
- `validate` reports `case_count: 9`, `passed_count: 9`, and `failed_count: 0`.
- No Java command is run.

- [ ] **Step 12: Commit the atomic v2 migration**

```bash
git add harness_py/models.py harness_py/dataset.py harness_py/scoring.py \
  harness_py/golden_case.py harness_py/golden_fixture.py harness_py/live_chat.py \
  harness_py/stage_prototype/runtime.py harness_py/cli.py harness_py/__init__.py \
  harness_py/tests/test_golden_v2.py harness_py/tests/test_harness_py.py \
  harness_py/tests/test_stage_prototype.py research/golden-data/manifest.yaml \
  research/golden-data/cases/core.yaml \
  research/golden-data/paper-packs/transformer-bert-gpt.yaml
git add -u harness_py/harness.py research/golden-data/cases/seed-smoke.yaml
git commit -m "refactor: replace golden data with behavior-first v2"
```

---

### Task 2: Add Three History-Dependent Golden Snapshots

**Files:**

- Modify: `research/golden-data/cases/core.yaml`
- Modify: `harness_py/tests/test_golden_v2.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**

- Consumes: `messages` and `LiveResearchChatHarness.run_case` from Task 1.
- Produces: three next-turn snapshots that exercise ordinal choice, confirmation, and constraint refinement without introducing a conversation-specific schema.

- [ ] **Step 1: Write failing assertions for the expanded dataset and history conversion**

Add to `GoldenV2Test`:

```python
    def test_committed_dataset_has_three_history_snapshots(self) -> None:
        history_cases = [case for case in self.dataset.cases if len(case["messages"]) > 1]
        self.assertEqual(3, len(history_cases))
        self.assertEqual(12, len(self.dataset.cases))

    def test_history_snapshot_becomes_live_conversation_context(self) -> None:
        from harness_py.golden_case import case_question, conversation_state_for_case

        case = next(case for case in self.dataset.cases if case["id"] == "bert_choice_followup_001")
        state = conversation_state_for_case(self.dataset, case)

        self.assertEqual("The second.", case_question(case))
        self.assertEqual(1, state.turn_index)
        self.assertEqual(2, len(state.message_history))
        self.assertEqual("assistant", state.message_history[-1]["role"])
        self.assertIn("BERT", state.message_history[-1]["summary"])
```

- [ ] **Step 2: Run the focused test and verify it fails with nine cases**

Run:

```bash
python3 -m unittest harness_py.tests.test_golden_v2.GoldenV2Test.test_committed_dataset_has_three_history_snapshots -v
```

Expected: FAIL because the dataset still has nine cases.

- [ ] **Step 3: Append the three snapshots**

Append these cases to `research/golden-data/cases/core.yaml`:

```yaml
  - schema_version: harness-golden-case/v2
    id: bert_choice_followup_001
    split: stress
    paradigm: context_specific_brainstorming
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Help me choose one paper to understand bidirectional Transformer encoders."
      - role: assistant
        content: "Choose one: (1) Attention Is All You Need, (2) BERT, or (3) GPT-2."
      - role: user
        content: "The second."
    expect:
      outcome: answered
      papers:
        required: [bert_2018]
      evidence:
        required: [bert_transformer_encoder_background]
      facts:
        transformer_role: bidirectional Transformer encoder
      citations: required

  - schema_version: harness-golden-case/v2
    id: transformer_bert_confirmation_followup_001
    split: stress
    paradigm: deep_comparison
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Compare the original Transformer with BERT."
      - role: assistant
        content: "Should I compare architecture role, directionality, and training objective?"
      - role: user
        content: "Yes, that is my choice."
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017, bert_2018]
      evidence:
        required:
          - transformer_encoder_decoder_architecture
          - bert_transformer_encoder_background
          - bert_masked_lm_pretraining
      claims:
        - text: "The comparison covers architecture role, directionality, and training objective."
          evidence:
            - transformer_encoder_decoder_architecture
            - bert_transformer_encoder_background
            - bert_masked_lm_pretraining
      citations: required

  - schema_version: harness-golden-case/v2
    id: adam_constraint_refinement_followup_001
    split: stress
    paradigm: precision_fact_extraction
    paper_pack: transformer_bert_gpt
    messages:
      - role: user
        content: "Give me Adam beta2."
      - role: assistant
        content: "Do you mean Adam's recommended default or the value used by the Transformer paper?"
      - role: user
        content: "The Transformer value."
    expect:
      outcome: answered
      papers:
        required: [attention_is_all_you_need_2017]
        forbidden: [adam_2014]
      evidence:
        required: [transformer_adam_training_params_span]
        forbidden: [adam_default_beta2_span]
      facts:
        beta2: "0.98"
      citations: required
```

- [ ] **Step 4: Prove the history reaches the one-turn decision model without production string matching**

Add this test-only subclass beside `_GoldenCaseStageModel`:

```python
class _HistoryCaptureModel(_GoldenCaseStageModel):
    def __init__(self, dataset: GoldenDataset, case: dict):
        super().__init__(dataset, case)
        self.turn_payloads: list[dict] = []

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        if required_tool_name == "submit_turn_decision":
            self.turn_payloads.append(json.loads(messages[-1]["content"]))
        return self.complete(messages, tools, max_tokens)
```

Add this test:

```python
    def test_live_golden_runner_passes_prior_messages_to_turn_decision(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        case = next(case for case in dataset.cases if case["id"] == "bert_choice_followup_001")
        model = _HistoryCaptureModel(dataset, case)

        run = LiveResearchChatHarness(model).run_case(dataset, case)

        context = model.turn_payloads[0]["conversation_context"]
        self.assertEqual("The second.", model.turn_payloads[0]["user_message"])
        self.assertEqual(2, len(context["recent_messages"]))
        self.assertEqual("user", context["recent_messages"][0]["role"])
        self.assertEqual("assistant", context["recent_messages"][1]["role"])
        self.assertEqual("COMPLETED", run["status"])
```

The production code only transports messages. All deterministic interpretation remains inside this test model.

- [ ] **Step 5: Run all deterministic tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_golden_v2 -v
python3 -m unittest discover -s harness_py/tests -v
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
```

Expected: `validate` reports `case_count: 12`, `passed_count: 12`, and `failed_count: 0`; unittest ends with `OK`.

- [ ] **Step 6: Commit the history snapshots**

```bash
git add research/golden-data/cases/core.yaml harness_py/tests/test_golden_v2.py harness_py/tests/test_stage_prototype.py
git commit -m "test: add history-dependent golden snapshots"
```

---

### Task 3: Separate Parser Anchor Audit From Harness Evaluation

**Files:**

- Create: `harness_py/audit.py`
- Modify: `harness_py/cli.py`
- Modify: `harness_py/tests/test_golden_v2.py`
- Generate locally: `data/golden/transformer-bert-gpt/generated-audit/anchor-verification.json` (`data/` is intentionally gitignored).

**Interfaces:**

- Consumes: normalized authored anchors and reading models from `GoldenDataset`.
- Produces: `audit_dataset(dataset) -> JsonMap`.
- Produces: CLI command `python3 -m harness_py audit --out <path>`.
- Does not call or score the research harness.

- [ ] **Step 1: Write the failing audit test**

Add:

```python
    def test_all_authored_anchors_are_locatable_in_reading_models(self) -> None:
        from harness_py.audit import audit_dataset

        report = audit_dataset(self.dataset)

        self.assertEqual(7, report["anchor_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(item["status"] == "pass" for item in report["anchors"]))
```

- [ ] **Step 2: Run the focused test and verify the module is missing**

Run:

```bash
python3 -m unittest harness_py.tests.test_golden_v2.GoldenV2Test.test_all_authored_anchors_are_locatable_in_reading_models -v
```

Expected: ERROR with `ModuleNotFoundError: harness_py.audit`.

- [ ] **Step 3: Implement exact normalized quote auditing**

Create `harness_py/audit.py`:

```python
from __future__ import annotations

import re

from .models import GoldenDataset, JsonMap, as_list, child_map


def audit_dataset(dataset: GoldenDataset) -> JsonMap:
    documents = _documents_by_paper(dataset)
    results: list[JsonMap] = []
    for anchor_id, anchor in sorted(dataset.anchors_by_id.items()):
        paper_id = str(anchor.get("paper_id") or "")
        page = child_map(anchor.get("element")).get("page")
        quote = str(child_map(anchor.get("selector")).get("exact_text") or "")
        normalized_quote = _normalize(quote)
        matched = [
            document
            for document in documents.get(paper_id, [])
            if (page is None or document["page"] is None or str(document["page"]) == str(page))
            and normalized_quote
            and normalized_quote in _normalize(str(document["text"]))
        ]
        results.append({
            "anchor_id": anchor_id,
            "paper_id": paper_id,
            "page": page,
            "status": "pass" if matched else "fail",
            "matched_location_refs": [str(item["location_ref"]) for item in matched],
        })
    failed = sum(1 for item in results if item["status"] == "fail")
    return {
        "schema_version": "harness-anchor-audit/v1",
        "dataset_id": dataset.manifest.get("dataset_id"),
        "anchor_count": len(results),
        "passed_count": len(results) - failed,
        "failed_count": failed,
        "anchors": results,
    }


def _documents_by_paper(dataset: GoldenDataset) -> dict[str, list[JsonMap]]:
    result: dict[str, list[JsonMap]] = {}
    for paper_id, model in dataset.reading_models_by_paper_id.items():
        for raw in as_list(model.get("reading_elements")):
            item = child_map(raw)
            text = str(item.get("searchableText") or item.get("bodyText") or item.get("captionText") or "")
            if not text:
                continue
            result.setdefault(paper_id, []).append({
                "page": item.get("pageNumber"),
                "location_ref": item.get("locationRef") or item.get("readingElementId") or item.get("id"),
                "text": text,
            })
    return result


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", value.casefold()))
```

If an authored quote spans multiple reading elements, fix the authored quote to the smallest complete phrase found in one source element. Do not add fuzzy thresholds to make the test pass.

- [ ] **Step 4: Add the independent CLI command**

In `cli.py`, add:

```python
audit_parser = subcommands.add_parser("audit", help="Verify authored anchors against parsed reading models.")
audit_parser.add_argument(
    "--out",
    default="data/golden/transformer-bert-gpt/generated-audit/anchor-verification.json",
)
```

Handle it with:

```python
if args.command == "audit":
    report = audit_dataset(load_dataset(args.manifest))
    target = Path(args.out)
    target.parent.mkdir(parents=True, exist_ok=True)
    _write_json(target, report)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["failed_count"] == 0 else 1
```

- [ ] **Step 5: Generate and verify the committed audit report**

Run:

```bash
python3 -m harness_py --manifest research/golden-data/manifest.yaml audit \
  --out data/golden/transformer-bert-gpt/generated-audit/anchor-verification.json
python3 -m unittest harness_py.tests.test_golden_v2 -v
```

Expected: the audit reports `anchor_count: 7`, `passed_count: 7`, `failed_count: 0`; tests end with `OK`.

- [ ] **Step 6: Commit the audit boundary**

```bash
git add harness_py/audit.py harness_py/cli.py harness_py/tests/test_golden_v2.py
git commit -m "feat: separate golden anchor audit from harness scoring"
```

---

### Task 4: Remove V1 Documentation And Verify The Real Harness

**Files:**

- Modify: `research/golden-data/README.md`
- Modify: `harness_py/README.md`
- Modify: `research/harness-golden-data-schema.md`
- Modify: `docs/superpowers/specs/2026-07-11-behavior-first-golden-data-design.md` only if implementation discovered a factual mismatch; do not expand its scope.

**Interfaces:**

- Consumes: all v2 code and data from Tasks 1-3.
- Produces: concise authoring documentation and recorded deterministic/live results.

- [ ] **Step 1: Replace the golden-data README with the actual v2 structure**

Use this content structure in `research/golden-data/README.md`:

```markdown
# Harness Golden Data V2

Canonical entry point: `research/golden-data/manifest.yaml`.

Authored data has three parts:

- `manifest.yaml`: paper-pack and case-file index.
- `paper-packs/*.yaml`: paper identities, citation edges, and stable evidence anchors.
- `cases/*.yaml`: message history plus observable outcome, paper, evidence, fact, and citation expectations.

Runtime stages and tool counts are deliberately not golden expectations.

Commands:

```bash
python3 -m harness_py validate
python3 -m harness_py audit
python3 -m harness_py agent-run --out /tmp/paismart-golden-v2-live
```

`validate` is deterministic scorer validation. `audit` verifies parser coverage. `agent-run` evaluates the real MiniMax-backed harness. Their failures are reported separately.

The Seed-60 files are planning documents, not executable cases.
```

- [ ] **Step 2: Update the Python harness README**

Make these exact documentation changes:

- Replace `ContractDrivenHarness` with `GoldenFixtureHarness`.
- Replace `TraceScorer` terminology with `BehaviorScorer`.
- Document `hard_pass` and the four dimensions.
- State that claim rubrics are `review.status=not_run` until human review or a later semantic judge.
- State that `agent-run` uses `LiveResearchChatHarness.run_case` and therefore receives prior messages.
- Update the committed count from nine to twelve and identify three history snapshots.
- Keep the existing terminal-chat instructions and optional trace-pane description.

- [ ] **Step 3: Collapse the old v1 schema document**

Replace `research/harness-golden-data-schema.md` with:

```markdown
# Harness Golden Data Schema

The canonical behavior-first v2 design is:

- `docs/superpowers/specs/2026-07-11-behavior-first-golden-data-design.md`

The executable examples are:

- `research/golden-data/manifest.yaml`
- `research/golden-data/paper-packs/transformer-bert-gpt.yaml`
- `research/golden-data/cases/core.yaml`

V1 intent, retrieval-plan, trace-obligation, and compatibility-projection fields are no longer supported by the Python harness.
```

- [ ] **Step 4: Scan for active Python references to removed v1 concepts**

Run:

```bash
rg -n "ContractDrivenHarness|TraceScorer|StructuralTraceScorer|expected_intent|expected_retrieval_plan|gold_evidence|gold_claims|required_trace|compatibility_projection" harness_py research/golden-data
```

Expected: no active Python code or executable v2 case references. Historical design/plan text and the untouched Java-only run traces may still contain v1 terminology.

- [ ] **Step 5: Run complete deterministic verification**

Run:

```bash
python3 -m unittest discover -s harness_py/tests -v
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
python3 -m harness_py --manifest research/golden-data/manifest.yaml audit \
  --out data/golden/transformer-bert-gpt/generated-audit/anchor-verification.json
```

Expected:

- Unit tests end with `OK`.
- Deterministic evaluation reports `12/12` hard passes.
- Anchor audit reports `7/7` passes.

- [ ] **Step 6: Run three real MiniMax representative cases**

Run:

```bash
python3 -m harness_py --manifest research/golden-data/manifest.yaml agent-run \
  --case-id transformer_adam_params_001 \
  --case-id gpt3_to_transformer_multihop_001 \
  --case-id bert_choice_followup_001 \
  --out /tmp/paismart-golden-v2-live
```

Expected operational result: three run directories and one `score_report.json` are written. Record the actual per-dimension failures without adding test-only branches or changing gold expectations to make MiniMax pass. The target is `failed_count: 0`; any failure becomes the next harness issue, not a schema workaround.

- [ ] **Step 7: Inspect the live reports for the two user-facing signals**

Read:

```bash
python3 -m json.tool /tmp/paismart-golden-v2-live/score_report.json
python3 -m json.tool /tmp/paismart-golden-v2-live/bert_choice_followup_001/research_answer.json
```

Confirm:

- The score report identifies failures by outcome, retrieval, content, or grounding.
- The ordinal follow-up proceeds to research rather than asking what “the second” means.
- The answer cites BERT evidence rather than merely acknowledging the choice.
- Trace artifacts remain available but do not affect `hard_pass`.

- [ ] **Step 8: Commit documentation and final cleanup**

```bash
git add research/golden-data/README.md harness_py/README.md research/harness-golden-data-schema.md
git commit -m "docs: document behavior-first golden evaluation"
```

---

## Completion Gate

The implementation is complete only when all of the following are true:

- The Python loader rejects v1 authored cases.
- The authored dataset contains twelve concise v2 cases over the existing five papers.
- Deterministic `validate` reports twelve hard passes.
- Parser `audit` reports seven anchor passes independently.
- The real `agent-run` path receives prior messages through `LiveResearchChatHarness`.
- No real harness prompt receives `expect` or the authored `paradigm`.
- Golden scoring ignores stage order, tool count, intent labels, and answer prose.
- Every scorer failure belongs to outcome, retrieval, content, or grounding.
- The selected live MiniMax run artifacts are inspected and their actual result is reported honestly.
- No Java code or Java test is touched or run.
