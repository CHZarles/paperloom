# Lightweight LLM Judge Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single-call LLM judge and a four-case human-agreement report for the existing Python Golden harness.

**Architecture:** A focused `harness_py.judge` module loads the human-labelled review packets, strips private runtime data, invokes one required `submit_judgment` tool call, derives the overall verdict, and compares it with the fixed human labels. The CLI adds one `judge-calibrate` command and writes one aggregate JSON report.

**Tech Stack:** Python 3.12, existing `ChatModel` and MiniMax provider adapter, PyYAML, `dataclasses`, `unittest`.

## Global Constraints

- Python harness only; do not run Java.
- One semantic judge call per valid run.
- Do not expose human verdicts or annotation notes to the judge.
- Do not grade stages, tool counts, paradigms, `answer_type`, private fields, or exact anchor IDs.
- Do not add fuzzy parsing, fallback JSON extraction, judge ensembles, voting, or self-critique.
- Derive `overall` deterministically from the three dimensions.
- Keep the existing `BehaviorScorer` unchanged during this calibration slice.
- Do not commit unless explicitly requested.

---

### Task 1: Calibration Data And Packet Contract

**Files:**
- Create: `harness_py/judge.py`
- Create: `harness_py/tests/test_judge.py`

**Interfaces:**
- Produces: `load_calibration_cases(path, repo_root=None) -> CalibrationSet`
- Produces: `CalibrationCase.judge_packet() -> JsonMap`
- Consumes: `research/golden-data/human-labels.yaml`, answer JSON, and evidence-ledger JSON

- [x] Write a failing test that loads the four committed labels, validates derived overall values,
  resolves all eight artifact paths, and includes only cited evidence in each judge packet.
- [x] Write a failing test proving `judge_packet()` excludes `judgment`, annotation `note`,
  `answer_type`, and exact anchor tags.
- [x] Run `python3 -m unittest harness_py.tests.test_judge.CalibrationLoaderTest -v` and verify the
  missing module fails.
- [x] Implement immutable calibration data types, strict label validation, artifact loading, cited
  evidence projection, and citation-integrity diagnostics.
- [x] Re-run the loader tests and verify they pass.

### Task 2: Structured Single-Call Judge

**Files:**
- Modify: `harness_py/judge.py`
- Modify: `harness_py/tests/test_judge.py`

**Interfaces:**
- Produces: `LLMJudge.judge(case) -> JudgeVerdict`
- Consumes: existing `ChatModel.complete_required_tool(...)`

- [x] Write a failing test using a fake `ChatModel` that returns one `submit_judgment` tool call and
  assert the resulting verdict derives `overall` correctly.
- [x] Write failing protocol tests for a missing tool call, wrong tool name, invalid dimension value,
  and inconsistent `not_applicable` use caused by malformed model output.
- [x] Run the focused judge tests and verify they fail for the missing implementation.
- [x] Implement the versioned judge prompt, strict tool schema, one-call invocation, verdict parsing,
  one concise rationale, and deterministic overall derivation.
- [x] Re-run the focused tests and verify they pass.

### Task 3: Agreement Report And CLI

**Files:**
- Modify: `harness_py/judge.py`
- Modify: `harness_py/cli.py`
- Modify: `harness_py/README.md`
- Modify: `harness_py/tests/test_judge.py`

**Interfaces:**
- Produces: `build_agreement_report(calibration, results, judge_metadata) -> JsonMap`
- Produces CLI: `python3 -m harness_py judge-calibrate ...`

- [x] Write failing tests that prove full-dimension agreement, overall agreement, false pass, false
  failure, and technical error counts are computed independently.
- [x] Write a failing CLI test with an injected fake model that writes `agreement_report.json` and
  returns `0` only for complete agreement.
- [x] Run the report and CLI tests and verify they fail for missing behavior.
- [x] Implement report construction, per-case error capture, CLI arguments, provider diagnostics,
  output writing, and exit codes `0`, `1`, and `2`.
- [x] Document the calibration command, report interpretation, and same-model limitation.
- [x] Re-run all judge tests and verify they pass.

### Task 4: Verification And Live Calibration

**Files:**
- Update: `docs/superpowers/specs/2026-07-11-llm-judge-calibration-design.md`
- Update: `docs/superpowers/plans/2026-07-11-llm-judge-calibration.md`
- Generate: `eval/rag/runs/llm-judge-calibration/agreement_report.json`

- [x] Run `python3 -m unittest harness_py.tests.test_judge -v`.
- [x] Run `python3 -m unittest harness_py.tests.test_golden_v2 harness_py.tests.test_stage_prototype harness_py.tests.test_harness_py -v`.
- [x] Run `python3 -m py_compile harness_py/*.py harness_py/stage_prototype/*.py`.
- [x] Run the live four-case calibration through the DB-backed provider.
- [x] Inspect every disagreement and confirm the report contains no human-label leakage.
- [x] Record the live result and implementation status in the proposal.
- [x] Run `git diff --check` over all changed files.
