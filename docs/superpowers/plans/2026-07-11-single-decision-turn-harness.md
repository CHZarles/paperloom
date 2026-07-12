# Single-Decision Turn Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python live-chat clarification stack with one structured turn decision that preserves an active research task and immediately resumes it after user clarification.

**Architecture:** `LiveResearchChatHarness` asks one `TurnInterpreter` to return `direct`, `clarify`, or `research`. Direct and clarification turns bypass semantic research stages; research turns pass the interpreter's resolved `IntentFrame` into the existing paradigm runtime. `ConversationState` stores one authoritative `active_task` and presentation-only `pending_interaction`.

**Tech Stack:** Python 3.12, standard-library dataclasses, existing `ChatModel` tool-call protocol, `unittest`.

## Global Constraints

- Python harness only; do not run Java.
- Replace overlapping control mechanisms instead of adding fallbacks, keyword routes, or ordinal parsers.
- Keep intermediate artifacts optional.
- Preserve evidence and citation verification for research answers.
- Add only focused regression coverage for the reported conversation failure.
- Do not commit unless explicitly requested.

---

### Task 1: Turn Decision Contract

**Files:**
- Modify: `harness_py/stage_prototype/models.py`
- Modify: `harness_py/stage_prototype/intent.py`
- Test: `harness_py/tests/test_harness_py.py`

**Interfaces:**
- Produces: `TurnDecision`, `TurnInterpreter.interpret(turn, dataset) -> TurnDecision`
- Consumes: current user message, bounded conversation context, active task, pending interaction, and paradigm catalogue

- [x] Add failing tests proving a greeting returns a direct answer and a pending-choice reply returns a research decision with a merged effective goal.
- [x] Run the two tests and verify they fail because `TurnDecision` and `TurnInterpreter` do not exist.
- [x] Implement one tool-call-only structured decision contract with routes `direct`, `clarify`, and `research`.
- [x] Convert research decisions to the existing `IntentFrame`; reject acknowledgement-only research decisions.
- [x] Run the focused tests and verify they pass.

### Task 2: Active Task Conversation Runtime

**Files:**
- Modify: `harness_py/conversation.py`
- Modify: `harness_py/live_chat.py`
- Modify: `harness_py/stage_prototype/runtime.py`
- Modify: `harness_py/cli.py`
- Test: `harness_py/tests/test_harness_py.py`

**Interfaces:**
- Produces: `ConversationState.active_task`, direct/clarification run artifacts, and `ParadigmDrivenHarness.run_case_with_intent(...)`
- Consumes: `TurnDecision` from Task 1

- [x] Add a failing conversation regression covering `hi`, a broad recommendation, and a pending selection that resumes the original task without an acknowledgement-only completion.
- [x] Run the regression and verify the current resolver/raw-message path fails.
- [x] Remove semantic choice resolution from `ConversationState`; keep pending interactions as data only.
- [x] Branch direct and clarification turns before stage execution, and execute research with the resolved effective goal.
- [x] Preserve `active_task` through clarification or technical failure; clear it only after a terminal research answer.
- [x] Run the focused conversation regression and verify it passes.

### Task 3: Lightweight Recommendation Path And Closure

**Files:**
- Modify: `harness_py/stage_prototype/plans.py`
- Modify: `harness_py/README.md`
- Test: `harness_py/tests/test_harness_py.py`
- Test: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: a two-stage `context_specific_brainstorming` plan for bounded candidate grounding and recommendation synthesis
- Consumes: existing corpus tools and deterministic verifier

- [x] Collapse the recommendation-oriented paradigm plan to two semantic stages without adding tools or planner machinery.
- [x] Update README runtime documentation to describe `TurnDecision` and active-task continuation.
- [x] Run focused tests: `python3 -m unittest harness_py.tests.test_harness_py.PythonHarnessPrototypeTest.test_live_chat_direct_greeting_bypasses_research harness_py.tests.test_harness_py.PythonHarnessPrototypeTest.test_live_chat_pending_choice_resumes_active_task -v`.
- [x] Run all Python harness tests: `python3 -m unittest harness_py.tests.test_stage_prototype harness_py.tests.test_harness_py -v`.
- [x] Run `python3 -m py_compile harness_py/*.py harness_py/stage_prototype/*.py`.
- [x] Run one product-database conversation reproducing the user's broad recommendation request and inspect tool count, status, and artifacts.
