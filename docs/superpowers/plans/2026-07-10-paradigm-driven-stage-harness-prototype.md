# Paradigm-Driven Semantic Stage Harness Prototype Implementation Plan

> **Superseded implementation shape (2026-07-10):** The detailed compiler/registry plan below was
> intentionally reduced after design review. The implemented slice uses one structured intent call
> plus 22 explicit paradigm plans in `harness_py/stage_prototype/plans.py`, sequential online
> semantic stages, live evidence/claim state, stage-scoped tools, and deterministic verification.
> There is no dynamic workflow compiler or DAG. See `harness_py/README.md` and the current-decision
> section in the companion design document for the authoritative runtime shape.

> **Robustness upgrade (2026-07-10):** The policy below is adopted for the next implementation
> loop and supersedes any incompatible ambiguity, stop-condition, or prompt-inventory wording in
> this historical plan. The detailed rationale is in
> `docs/superpowers/specs/2026-07-10-harness-robustness-upgrade-strategy.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python prototype in which an LLM frames the question paradigm, compiles a typed semantic stage plan, and executes bounded stage-level model/tool loops that incrementally update evidence and claims before final verification.

**Architecture:** Add an isolated `harness_py.stage_prototype` package beside the existing harness. The implemented lightweight version uses one intent call, eight explicit plans, stage-level tool gating, incremental research state, and artifact projection. Deterministic adapters prove all nine golden cases through all eight plan families; MiniMax supplies live model-driven smokes.

**Tech Stack:** Python 3.12, standard-library `dataclasses`, existing `ChatModel`/`MiniMaxChatModel`, existing `ReadingCorpusTools`, `unittest`, YAML golden data.

## Adopted Robustness Policy

The harness must not predict user topics or add question-specific routes. Paradigms remain the
semantic research strategy selected by the LLM; the following generic policy governs how every
turn begins, executes, and stops:

```text
user message
-> resolve a current PendingInteraction, if present
-> LLM frames intent and actionability
-> select a paradigm plan
-> execute online semantic stages with scoped tools
-> patch research and conversation state after each stage
-> verify the answer contract
-> answer, or return one explicit terminal interaction
```

### 1. Actionability Before Clarification

The intent frame labels uncertainty as `none`, `non_blocking`, or `blocking`.

- `none`: proceed normally.
- `non_blocking`: proceed under a named assumption or coverage policy. Broad requests and
  preference gaps are in this category; they must receive a useful first answer.
- `blocking`: ask one focused question before research because proceeding would likely target the
  wrong identity, version, relationship, or hard constraint.

Do not coerce every ambiguous request into `ambiguity_resolution`. That paradigm remains a narrow
tool for genuinely blocked research, not the default response to open-ended chat.

### 2. Pending Interactions Are Typed Conversation State

When a question is necessary, persist a `PendingInteraction` with a stable interaction ID, option
IDs, and the state patch for each option. Resolve a short option reply against that object before
new intent framing, then clear it. Never reconstruct an option from prior assistant prose.

This is a conversation protocol, not a content keyword router. Free-form replies remain
LLM-interpreted against the persisted interaction and recent conversation context.

### 3. Corpus Observation Before Grounded Completion

Any recommendation of corpus papers or factual assertion about papers requires fresh corpus
observations before `COMPLETED`:

- recommendations inspect candidate paper cards through existing corpus tools;
- relevance claims beyond title/metadata inspect reading-model evidence;
- paper-content claims cite accepted evidence IDs.

The intent and stage prompts receive concise corpus metadata and tool schemas, not the full paper
inventory. Tool results are the only source for selected paper cards and accepted evidence.

### 4. Stage and Turn Stop Conditions

A local missing obligation is not automatically terminal. Continue when a later stage can still
resolve it. Only these conditions may end a turn:

```text
request_user_input      an explicit terminal PendingInteraction exists
incomplete_precise      required evidence was attempted and remains unavailable
technical_failure       a bounded repair cannot obtain a valid model/tool result
all_stages_completed    the verified answer contract is satisfied
```

Every candidate set is bounded. A stage must not answer by serialising the whole corpus as selected
paper IDs.

### 5. Answer First, Review Artefacts on Demand

The primary surface is the answer, its selected papers or citations, and any named assumption or
limitation. `IntentFrame`, plan, stage trace, evidence ledger, claim graph, and verification are
optional review artefacts for terminal output and future frontend panes. Do not expose
chain-of-thought.

### 6. Thin Closure Loop

Implement only the actionability field, pending-interaction resolution, terminal-condition fix,
corpus-observation guard, prompt-inventory removal, and candidate cap. Retain the 22 explicit
paradigm plans and current tools. Do not add a planner, dynamic DAG, tool catalogue, database
schema, or frontend surface in this loop.

Verify with one live-chat golden regression reproducing the observed clarification loop, the
existing Python harness tests, and one manual terminal chat against the product database. The
regression must show at most one persisted clarification, a corpus tool call before any paper
recommendation, and a bounded evidence-backed `COMPLETED` answer.

## Global Constraints

- The top-level runtime must not be a global ReAct loop.
- A bounded model/tool loop is allowed only inside the currently executing semantic stage.
- `EvidenceLedger` and `ClaimGraph` are incrementally updated shared state, not post-hoc stages.
- Paradigms are multi-label planning priors, not hard-coded routes.
- Only registered semantic stage kinds may appear in an executable plan.
- Every tool call must be authorized by the current stage and its budget.
- Every completed paper-content claim must reference accepted inspectable evidence.
- Only blocking ambiguity produces `NEEDS_CLARIFICATION`. Non-blocking uncertainty produces an
  evidence-backed answer with an explicit assumption; unavailable required evidence after an
  attempted retrieval produces `INCOMPLETE_PRECISE`.
- Raw chain-of-thought must not be persisted; only concise decision summaries are allowed.
- Keep the prototype isolated under `harness_py/stage_prototype/` and clearly disposable.
- Do not modify or delete the existing `ResearchAgentHarness` during this experiment.
- Do not commit in this workspace unless the user explicitly requests a commit.

---

## File Structure

Create:

```text
harness_py/stage_prototype/__init__.py
harness_py/stage_prototype/models.py
harness_py/stage_prototype/registry.py
harness_py/stage_prototype/planning.py
harness_py/stage_prototype/tool_gate.py
harness_py/stage_prototype/runtime.py
harness_py/stage_prototype/artifacts.py
harness_py/stage_prototype/scripted.py
harness_py/tests/test_stage_prototype.py
```

Modify:

```text
harness_py/cli.py
harness_py/README.md
```

Responsibilities:

- `models.py`: all typed artifacts and serialization helpers.
- `registry.py`: stable semantic stage kinds and plan validation.
- `planning.py`: planner interfaces, LLM paradigm framer, and LLM plan compiler.
- `tool_gate.py`: current-stage tool authorization, budgets, and observation capture.
- `runtime.py`: research state, atomic stage patches, stage execution, and external harness interface.
- `artifacts.py`: projections into the existing harness artifact shape.
- `scripted.py`: deterministic scripted planner/executor used only by prototype tests.
- `test_stage_prototype.py`: runtime, gating, patching, plan-family, and golden-case tests.

---

### Task 1: Typed Stage Domain Model

**Files:**
- Create: `harness_py/stage_prototype/__init__.py`
- Create: `harness_py/stage_prototype/models.py`
- Test: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: `TurnFrame`, `ParadigmFrame`, `StageSpec`, `SemanticStagePlan`, `StageResult`, `PlanPatch`, `ResearchState`, and `StageRun`.
- Consumes: `GoldenDataset` and `JsonMap` from `harness_py.models`.

- [ ] **Step 1: Write failing serialization and state tests**

```python
class StagePrototypeModelTest(unittest.TestCase):
    def test_stage_plan_round_trips(self):
        plan = SemanticStagePlan(
            plan_id="plan_fact",
            family="exact_fact",
            stages=[StageSpec(
                stage_id="resolve",
                stage_kind="resolve_target_identity",
                semantic_goal="Resolve the target paper.",
                completion_contract={"required_fields": ["resolved_paper_ids"]},
                permitted_tools=["find_papers_by_identity"],
                executor="hybrid",
            )],
            edges=[],
        )
        self.assertEqual(plan, SemanticStagePlan.from_dict(plan.to_dict()))

    def test_research_state_starts_with_empty_live_artifacts(self):
        state = ResearchState.new("case_1")
        self.assertEqual({}, state.evidence_items_by_id)
        self.assertEqual({}, state.claims_by_id)
        self.assertEqual([], state.completed_stage_ids)
```

- [ ] **Step 2: Run tests and verify the missing imports fail**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StagePrototypeModelTest -v
```

Expected: `ImportError` for `harness_py.stage_prototype.models`.

- [ ] **Step 3: Implement immutable typed artifacts**

Use dataclasses with explicit `to_dict()`/`from_dict()` methods. The minimum definitions are:

```python
@dataclass(frozen=True)
class TurnFrame:
    turn_id: str
    question: str
    allowed_paper_ids: list[str]
    conversation_context: JsonMap = field(default_factory=dict)
    expected_case: JsonMap = field(default_factory=dict)

@dataclass(frozen=True)
class ParadigmFrame:
    primary_paradigm: str
    secondary_paradigms: list[str]
    confidence: float
    answer_contract: str
    semantic_obligations: list[str]
    ambiguity_status: str
    required_evidence_types: list[str]
    required_capabilities: list[str]

@dataclass(frozen=True)
class StageSpec:
    stage_id: str
    stage_kind: str
    semantic_goal: str
    completion_contract: JsonMap
    permitted_tools: list[str]
    executor: str
    input_refs: list[str] = field(default_factory=list)
    preconditions: list[str] = field(default_factory=list)
    max_model_turns: int = 3
    max_tool_calls: int = 4
    failure_policy: str = "incomplete_precise"

@dataclass(frozen=True)
class SemanticStagePlan:
    plan_id: str
    family: str
    stages: list[StageSpec]
    edges: list[JsonMap]
    global_stop_conditions: list[str] = field(default_factory=list)

@dataclass(frozen=True)
class PlanPatch:
    add_stages: list[StageSpec] = field(default_factory=list)
    revise_stages: list[JsonMap] = field(default_factory=list)
    terminate_status: str | None = None

@dataclass(frozen=True)
class StageResult:
    stage_id: str
    status: str
    decision_summary: str
    observations: list[JsonMap] = field(default_factory=list)
    evidence_patch: list[JsonMap] = field(default_factory=list)
    claim_patch: list[JsonMap] = field(default_factory=list)
    reasoning_artifact_patch: list[JsonMap] = field(default_factory=list)
    conversation_state_patch: JsonMap = field(default_factory=dict)
    missing_obligations: list[JsonMap] = field(default_factory=list)
    proposed_plan_patch: PlanPatch | None = None
    diagnostics: JsonMap = field(default_factory=dict)

@dataclass(frozen=True)
class ResearchState:
    turn_id: str
    resolved_paper_ids: list[str]
    evidence_items_by_id: dict[str, JsonMap]
    rejected_evidence: list[JsonMap]
    missing_evidence: list[JsonMap]
    claims_by_id: dict[str, JsonMap]
    reasoning_artifacts: list[JsonMap]
    completed_stage_ids: list[str]
    failed_stage_ids: list[str]
    remaining_obligations: list[str]
    conversation_state_patch: JsonMap
```

- [ ] **Step 4: Export the external prototype interface types**

`harness_py/stage_prototype/__init__.py`:

```python
from .models import ParadigmFrame, ResearchState, SemanticStagePlan, StageResult, StageSpec, TurnFrame

__all__ = [
    "ParadigmFrame",
    "ResearchState",
    "SemanticStagePlan",
    "StageResult",
    "StageSpec",
    "TurnFrame",
]
```

- [ ] **Step 5: Run model tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StagePrototypeModelTest -v
```

Expected: all model tests pass.

- [ ] **Step 6: Review checkpoint**

Confirm no domain type imports the model client, tools, CLI, or golden scorer.

---

### Task 2: Semantic Stage Registry and Plan Validation

**Files:**
- Create: `harness_py/stage_prototype/registry.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Consumes: `SemanticStagePlan`, `StageSpec`.
- Produces: `StageRegistry`, `PlanValidationResult`, `default_stage_registry()`.

- [ ] **Step 1: Write failing registry tests**

```python
class StageRegistryTest(unittest.TestCase):
    def test_registry_rejects_unknown_stage_kind(self):
        plan = self._plan(StageSpec(
            stage_id="mystery",
            stage_kind="invent_anything",
            semantic_goal="Do something unknown.",
            completion_contract={},
            permitted_tools=[],
            executor="llm",
        ))
        result = default_stage_registry().validate_plan(plan, available_tools=[])
        self.assertFalse(result.valid)
        self.assertIn("UNKNOWN_STAGE_KIND:invent_anything", result.errors)

    def test_registry_rejects_tool_not_permitted_by_stage_kind(self):
        plan = self._plan(StageSpec(
            stage_id="axes",
            stage_kind="establish_comparison_axes",
            semantic_goal="Establish comparison axes.",
            completion_contract={"minimum_axes": 3},
            permitted_tools=["read_locations"],
            executor="llm",
        ))
        result = default_stage_registry().validate_plan(plan, available_tools=["read_locations"])
        self.assertIn("STAGE_TOOL_POLICY_VIOLATION:axes:read_locations", result.errors)
```

- [ ] **Step 2: Run registry tests and verify failure**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StageRegistryTest -v
```

Expected: missing `registry` module.

- [ ] **Step 3: Implement the registry**

Define stable stage kinds and maximum tool capabilities:

```python
STAGE_KIND_TOOL_POLICIES = {
    "frame_semantic_obligations": set(),
    "resolve_target_identity": {"list_papers", "find_papers_by_identity"},
    "clarify_ambiguity": {"list_papers", "find_papers_by_identity"},
    "define_fact_slots": set(),
    "establish_comparison_axes": set(),
    "retrieve_supporting_evidence": {"search_reading_locations", "read_locations"},
    "retrieve_counterevidence": {"search_reading_locations", "read_locations"},
    "trace_genealogy": {"get_citation_edges", "search_reading_locations", "read_locations"},
    "test_knowledge_boundary": {"list_papers", "find_papers_by_identity", "search_reading_locations"},
    "extract_atomic_claims": set(),
    "adjudicate_conflict": set(),
    "check_coverage": set(),
    "synthesize_answer": set(),
    "verify_research_contract": set(),
}
```

`validate_plan()` must reject unknown kinds, duplicate stage ids, dangling edges, cycles, unavailable
tools, kind-policy violations, missing semantic goals, invalid executors, and plans without
`verify_research_contract`.

- [ ] **Step 4: Add cycle and missing-verifier tests**

Add tests that assert `PLAN_CYCLE` and `MISSING_VERIFICATION_STAGE`.

- [ ] **Step 5: Run registry tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StageRegistryTest -v
```

Expected: all registry tests pass.

- [ ] **Step 6: Review checkpoint**

Verify that the registry contains semantic operators only; no `build_evidence_ledger` or
`build_claim_graph` stage exists.

---

### Task 3: Incremental Research State and Atomic Stage Patches

**Files:**
- Create: `harness_py/stage_prototype/runtime.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Consumes: `ResearchState`, `StageResult`, `StageSpec`.
- Produces: `apply_stage_result(state, spec, result) -> ResearchState` and `StagePatchError`.

- [ ] **Step 1: Write failing patch tests**

```python
class ResearchStatePatchTest(unittest.TestCase):
    def test_stage_patch_adds_evidence_before_claim(self):
        state = ResearchState.new("turn_1")
        result = StageResult(
            stage_id="extract",
            status="completed",
            decision_summary="Extracted one supported claim.",
            evidence_patch=[{"evidence_id": "ev_1", "paper_id": "p1", "location": "loc1", "span_text": "x"}],
            claim_patch=[{"claim_id": "c1", "status": "supported", "supporting_evidence_ids": ["ev_1"]}],
        )
        updated = apply_stage_result(state, self._stage("extract"), result)
        self.assertIn("ev_1", updated.evidence_items_by_id)
        self.assertIn("c1", updated.claims_by_id)

    def test_stage_patch_rejects_supported_claim_without_evidence(self):
        result = StageResult(
            stage_id="extract",
            status="completed",
            decision_summary="Unsupported claim.",
            claim_patch=[{"claim_id": "c1", "status": "supported", "supporting_evidence_ids": ["missing"]}],
        )
        with self.assertRaisesRegex(StagePatchError, "UNKNOWN_SUPPORT_EVIDENCE:c1:missing"):
            apply_stage_result(ResearchState.new("turn_1"), self._stage("extract"), result)
```

- [ ] **Step 2: Run patch tests and verify failure**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.ResearchStatePatchTest -v
```

- [ ] **Step 3: Implement atomic patch application**

The function must validate the whole patch before returning a new immutable state:

```python
def apply_stage_result(state: ResearchState, spec: StageSpec, result: StageResult) -> ResearchState:
    if result.stage_id != spec.stage_id:
        raise StagePatchError(f"STAGE_RESULT_ID_MISMATCH:{spec.stage_id}:{result.stage_id}")
    evidence = {**state.evidence_items_by_id}
    for item in result.evidence_patch:
        validate_evidence_item(item)
        evidence[str(item["evidence_id"])] = item
    claims = {**state.claims_by_id}
    for claim in result.claim_patch:
        validate_claim(claim, evidence)
        claims[str(claim["claim_id"])] = claim
    return replace(
        state,
        evidence_items_by_id=evidence,
        claims_by_id=claims,
        completed_stage_ids=[*state.completed_stage_ids, spec.stage_id]
        if result.status == "completed" else state.completed_stage_ids,
        failed_stage_ids=[*state.failed_stage_ids, spec.stage_id]
        if result.status in {"incomplete", "failed_technical"} else state.failed_stage_ids,
        missing_evidence=[*state.missing_evidence, *result.missing_obligations],
        reasoning_artifacts=[*state.reasoning_artifacts, *result.reasoning_artifact_patch],
        conversation_state_patch={**state.conversation_state_patch, **result.conversation_state_patch},
    )
```

- [ ] **Step 4: Add tests for rejected evidence, duplicate ids, and incomplete stages**

Verify that rejected evidence never becomes claim support and incomplete stages preserve named
missing obligations.

- [ ] **Step 5: Run patch tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.ResearchStatePatchTest -v
```

- [ ] **Step 6: Review checkpoint**

Confirm that no code reconstructs the ledger or claim graph from the final answer.

---

### Task 4: Stage-Level Tool Gate

**Files:**
- Create: `harness_py/stage_prototype/tool_gate.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Consumes: `ReadingCorpusTools`, current `StageSpec`.
- Produces: `StageToolGate.definitions()` and `StageToolGate.call(name, arguments)`.

- [ ] **Step 1: Write failing gate tests**

```python
class StageToolGateTest(unittest.TestCase):
    def test_gate_exposes_only_current_stage_tools(self):
        gate = StageToolGate(
            ReadingCorpusTools(self.dataset),
            self._stage(permitted_tools=["search_reading_locations"]),
        )
        names = [item["function"]["name"] for item in gate.definitions()]
        self.assertEqual(["search_reading_locations"], names)

    def test_gate_rejects_out_of_stage_tool(self):
        gate = StageToolGate(
            ReadingCorpusTools(self.dataset),
            self._stage(permitted_tools=["search_reading_locations"]),
        )
        with self.assertRaisesRegex(StageToolViolation, "OUT_OF_STAGE_TOOL:read_locations"):
            gate.call("read_locations", {"evidence_ids": []})

    def test_gate_enforces_tool_budget(self):
        stage = self._stage(permitted_tools=["list_papers"], max_tool_calls=1)
        gate = StageToolGate(ReadingCorpusTools(self.dataset), stage)
        gate.call("list_papers", {})
        with self.assertRaisesRegex(StageToolViolation, "STAGE_TOOL_BUDGET_EXHAUSTED"):
            gate.call("list_papers", {})
```

- [ ] **Step 2: Run gate tests and verify failure**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StageToolGateTest -v
```

- [ ] **Step 3: Implement the gate as a narrow adapter**

The gate must filter existing OpenAI-compatible tool definitions, count calls, store normalized
observations, and reject tools not listed by the stage. It delegates transport behavior to
`ReadingCorpusTools` rather than duplicating retrieval logic.

- [ ] **Step 4: Add observation provenance tests**

Assert every gate observation records `stage_id`, `tool_name`, arguments, and returned payload.

- [ ] **Step 5: Run gate tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.StageToolGateTest -v
```

- [ ] **Step 6: Review checkpoint**

Confirm the stage executor cannot access the unfiltered `ReadingCorpusTools` instance.

---

### Task 5: LLM Paradigm Framing, Plan Compilation, and Stage Execution Adapters

**Files:**
- Create: `harness_py/stage_prototype/planning.py`
- Modify: `harness_py/stage_prototype/runtime.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: `ParadigmPlanner`, `LlmParadigmPlanner`, `StageExecutor`, `LlmStageExecutor`.
- Consumes: existing `ChatModel`, `StageRegistry`, `StageToolGate`, typed model artifacts.

- [ ] **Step 1: Write failing planner tests with a queue-backed fake model**

```python
class QueueChatModel(ChatModel):
    def __init__(self, turns):
        self.turns = list(turns)
        self.requests = []

    def complete(self, messages, tools, max_tokens):
        self.requests.append({"messages": messages, "tools": tools})
        return self.turns.pop(0)

def test_llm_planner_frames_and_compiles_registered_stages(self):
    model = QueueChatModel([
        ChatTurn(content=json.dumps(PARADIGM_PAYLOAD)),
        ChatTurn(content=json.dumps(PLAN_PAYLOAD)),
    ])
    planner = LlmParadigmPlanner(model, default_stage_registry())
    frame = planner.frame(self.turn_frame)
    plan = planner.compile(self.turn_frame, frame, AVAILABLE_TOOLS)
    self.assertEqual("deep_comparison", frame.primary_paradigm)
    self.assertEqual("comparison_matrix", plan.family)
```

- [ ] **Step 2: Run planner tests and verify failure**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.LlmPlannerTest -v
```

- [ ] **Step 3: Implement strict JSON model calls with one repair attempt**

`LlmParadigmPlanner.frame()` and `.compile()` must call the model without tools, parse one JSON
object, validate it, and issue one compact repair prompt on invalid JSON. The plan prompt includes
the registered stage kinds and their tool policies; it must not include golden expected answers.

- [ ] **Step 4: Write failing bounded stage-executor tests**

Use a fake model that first calls `search_reading_locations`, then returns a `StageResult` JSON. Assert
that only the stage's permitted tool definitions are sent and that the result contains captured
observations.

- [ ] **Step 5: Implement `LlmStageExecutor`**

```python
class LlmStageExecutor(StageExecutor):
    def execute(self, turn, stage, state, gate):
        messages = build_stage_messages(turn, stage, state)
        for _ in range(stage.max_model_turns):
            response = self.model.complete(messages, gate.definitions(), self.max_tokens)
            if response.tool_calls:
                append_tool_results(messages, response.tool_calls, gate)
                continue
            result = parse_stage_result(response.content, stage.stage_id)
            return replace(result, observations=gate.observations)
        return StageResult(
            stage_id=stage.stage_id,
            status="incomplete",
            decision_summary="Stage model-turn budget exhausted.",
            observations=gate.observations,
            missing_obligations=[{"reason": "stage_model_budget_exhausted"}],
        )
```

Stage prompts include only the stage goal, completion contract, a bounded state projection, and the
permitted tool names. They explicitly prohibit final-answer generation unless the stage kind is
`synthesize_answer`.

- [ ] **Step 6: Run planner and executor tests**

Run:

```bash
python3 -m unittest \
  harness_py.tests.test_stage_prototype.LlmPlannerTest \
  harness_py.tests.test_stage_prototype.LlmStageExecutorTest -v
```

- [ ] **Step 7: Review checkpoint**

Verify that framing, planning, and each stage are separate model calls with separate schemas.

---

### Task 6: Generic Stage Runtime, Plan Patches, and Final Gate

**Files:**
- Modify: `harness_py/stage_prototype/runtime.py`
- Create: `harness_py/stage_prototype/artifacts.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: `ParadigmStageHarness.run_turn(dataset, turn_frame) -> JsonMap`.
- Consumes: `ParadigmPlanner`, `StageExecutor`, `StageRegistry`, `ReadingCorpusTools`.

- [ ] **Step 1: Write a failing end-to-end runtime test**

```python
class ParadigmStageHarnessTest(unittest.TestCase):
    def test_plan_exists_before_authorized_stage_tool_calls(self):
        planner = ScriptedPlanner(frame=FACT_FRAME, plan=FACT_PLAN)
        executor = ScriptedStageExecutor(FACT_STAGE_RESULTS)
        run = ParadigmStageHarness(planner, executor).run_turn(self.dataset, self.turn)
        self.assertEqual("exact_fact", run["semantic_stage_plan"]["family"])
        first_tool_event = next(e for e in run["stage_runs"] if e["diagnostics"].get("tool_calls"))
        self.assertIn(first_tool_event["stage_id"], [s["stage_id"] for s in run["semantic_stage_plan"]["stages"]])
```

- [ ] **Step 2: Implement dependency-ordered stage execution**

`ParadigmStageHarness` must:

1. frame the paradigm;
2. compile and validate the plan;
3. initialize `ResearchState`;
4. execute ready stages in dependency order;
5. create a new `StageToolGate` for each stage;
6. validate and apply each result atomically;
7. accept or reject explicit plan patches;
8. stop on clarification, precise incompleteness, technical failure, or budget exhaustion;
9. project standard artifacts;
10. run deterministic final verification before presenting `COMPLETED`.

- [ ] **Step 3: Implement safe plan patches**

Only additions and revisions to not-yet-started stages are allowed. Validate the full patched plan
before replacing it. Reject attempts to remove verification, add unknown stages, add unauthorized
tools, or alter completed stages.

- [ ] **Step 4: Implement standard artifact projections**

`artifacts.py` exposes:

```python
def project_intent_frame(turn, paradigm_frame) -> JsonMap: ...
def project_retrieval_plan(plan, stage_runs) -> JsonMap: ...
def project_evidence_ledger(state) -> JsonMap: ...
def project_claim_graph(state) -> JsonMap: ...
def project_verification(turn, plan, state) -> JsonMap: ...
def project_answer(turn, state, verification) -> JsonMap: ...
```

The answer projection may use the verified `synthesize_answer` artifact, but it must drop any claim
or evidence id absent from live state.

- [ ] **Step 5: Add final-gate tests**

Test unsupported claims, missing verifier stage, incomplete required stage, and ambiguity status.
Assert only a zero-unsupported-claim run can become `COMPLETED`.

- [ ] **Step 6: Run runtime tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype.ParadigmStageHarnessTest -v
```

- [ ] **Step 7: Review checkpoint**

Search the prototype for one global model/tool loop. Every tool loop must be inside
`LlmStageExecutor.execute()` and bounded by `StageSpec`.

---

### Task 7: Scripted Six-Family Golden-Case Coverage

**Files:**
- Create: `harness_py/stage_prototype/scripted.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: `ScriptedPlanner`, `ScriptedStageExecutor`, and generic fixture builders used only by tests.
- Consumes: golden case fields through the test fixture builder, never from production runtime code.

- [ ] **Step 1: Build test-only case scripts**

Map the seven case ids to six plan families only inside the test adapter:

```python
CASE_FAMILIES = {
    "transformer_adam_params_001": "exact_fact",
    "bert_transformer_role_001": "exact_fact",
    "attention_paper_ambiguous_001": "ambiguity_clarification",
    "gpt5_architecture_boundary_001": "uncertainty_boundary",
    "bert_vs_transformer_comparison_001": "comparison_matrix",
    "transformer_to_bert_genealogy_001": "citation_genealogy",
    "adam_beta2_conflict_001": "contradiction_arbitration",
}
```

The helper translates each case's required anchors and claims into scripted stage results so the
runtime mechanics can be tested deterministically. It must not be imported by production modules.

- [ ] **Step 2: Write all-case coverage test**

```python
def test_all_committed_cases_use_generic_stage_runtime_and_six_families(self):
    dataset = load_dataset("research/golden-data/manifest.yaml")
    runs = [run_scripted_case(dataset, case) for case in dataset.cases]
    self.assertEqual(7, len(runs))
    self.assertEqual(6, len({run["semantic_stage_plan"]["family"] for run in runs}))
    self.assertTrue(all(run["harness_id"] == "python_paradigm_stage_prototype_v0" for run in runs))
    self.assertTrue(all(run["verification_pass"]["unsupported_claim_count"] == 0 for run in runs if run["status"] == "COMPLETED"))
```

- [ ] **Step 3: Add family-shape assertions**

Assert exact fact contains `define_fact_slots`, comparison contains
`establish_comparison_axes`, genealogy contains `trace_genealogy`, contradiction contains
`adjudicate_conflict`, ambiguity contains `clarify_ambiguity`, and uncertainty contains
`test_knowledge_boundary`.

- [ ] **Step 4: Add negative mutation tests**

Remove one required comparison evidence cell and assert `INCOMPLETE_PRECISE`. Remove one conflict
source and assert the conflict cannot be adjudicated. Add an unregistered PlanPatch and assert it is
rejected.

- [ ] **Step 5: Run all prototype tests**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype -v
```

- [ ] **Step 6: Review checkpoint**

Search `harness_py/stage_prototype/` for committed case ids. The only allowed case-id mapping is in
`scripted.py`, which is a test-only adapter.

---

### Task 8: CLI, Artifact Export, Documentation, and Live MiniMax Smoke

**Files:**
- Modify: `harness_py/cli.py`
- Modify: `harness_py/README.md`
- Modify: `harness_py/stage_prototype/__init__.py`
- Modify: `harness_py/tests/test_stage_prototype.py`

**Interfaces:**
- Produces: `python3 -m harness_py stage-prototype`.
- Consumes: existing provider configuration and golden dataset loaders.

- [ ] **Step 1: Add CLI parser tests**

Test these forms:

```bash
python3 -m harness_py stage-prototype --case-id transformer_adam_params_001
python3 -m harness_py stage-prototype --case-id bert_vs_transformer_comparison_001 --out /tmp/stage-prototype
python3 -m harness_py stage-prototype --case-id transformer_adam_params_001 --live-model
```

The default mode uses scripted deterministic adapters. `--live-model` uses MiniMax from the product
DB and is allowed to run one selected case only.

- [ ] **Step 2: Implement the CLI branch**

The command loads the golden dataset, selects exactly one case unless `--all` is supplied, builds a
`TurnFrame`, runs `ParadigmStageHarness`, prints a compact summary, and optionally writes:

```text
paradigm_frame.json
semantic_stage_plan.json
stage_runs.json
intent_frame.json
retrieval_plan.json
evidence_ledger.json
claim_graph.json
verification_pass.json
research_answer.json
harness_run.json
```

- [ ] **Step 3: Update README with prototype status and deletion warning**

Document that `harness_py/stage_prototype/` is experimental and should be absorbed or deleted after
the design decision. Include exact deterministic and live commands.

- [ ] **Step 4: Run deterministic CLI smoke for all nine cases**

Run:

```bash
python3 -m harness_py stage-prototype --all --out /tmp/paradigm-stage-prototype
```

Expected: seven runs, six plan families, no technical failures, and standard artifacts for every
case.

- [ ] **Step 5: Run one live MiniMax smoke**

Run:

```bash
python3 -m harness_py stage-prototype \
  --case-id transformer_adam_params_001 \
  --live-model \
  --out /tmp/paradigm-stage-live
```

Expected evidence:

- a model-produced `ParadigmFrame`;
- a model-produced plan containing only registered stages;
- tool calls recorded under their authorizing stages;
- final status `COMPLETED` or a precise named failure demonstrating where the live planner or stage
  executor was insufficient.

- [ ] **Step 6: Run full regression verification**

Run:

```bash
python3 -m unittest harness_py.tests.test_stage_prototype -v
python3 -m unittest harness_py.tests.test_harness_py -v
python3 -m py_compile harness_py/*.py harness_py/stage_prototype/*.py
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
```

Expected: all tests pass and deterministic golden validation reports `failed_count: 0`.

- [ ] **Step 7: Final review checkpoint**

Verify the acceptance criteria from
`docs/superpowers/specs/2026-07-10-paradigm-driven-stage-harness-prototype-design.md` one by one using
the exported run artifacts and command output. Record any live-model limitation in the final report
without weakening the deterministic stage-runtime gate.

---

## Plan Self-Review

### Spec Coverage

- Paradigm framing: Task 5.
- LLM plan compilation: Task 5.
- Typed semantic stage registry: Task 2.
- Bounded per-stage model/tool loops: Tasks 4 and 5.
- Incremental evidence and claim state: Task 3.
- Explicit safe plan patches: Task 6.
- Eight plan families and nine golden cases: Task 7.
- Conversation-state input and patch output: Tasks 1, 3, and 6.
- Optional inspectable stage artifacts: Tasks 6 and 8.
- Deterministic verification: Tasks 3 and 6.
- MiniMax smoke: Task 8.

### Placeholder Scan

The plan contains no `TBD`, `TODO`, or unspecified implementation step. Every task names exact
files, interfaces, test commands, and expected behavior.

### Type Consistency

The plan consistently uses:

```text
ParadigmStageHarness.run_turn(dataset, turn_frame) -> JsonMap
ParadigmPlanner.frame(turn_frame) -> ParadigmFrame
ParadigmPlanner.compile(turn_frame, paradigm_frame, available_tools) -> SemanticStagePlan
StageExecutor.execute(turn_frame, stage_spec, research_state, stage_tool_gate) -> StageResult
apply_stage_result(research_state, stage_spec, stage_result) -> ResearchState
```

No later task renames these interfaces.
