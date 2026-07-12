# Harness Onboarding

This guide is the ownership map for the Python research harness. The shortest useful mental model
is: Java supplies an authorized paper scope and conversation history; Python runs one model-driven
research turn; deterministic code validates the submitted answer and returns the answer, evidence,
usage, and updated research memory.

The harness is intentionally centered on model orchestration. Golden-data analysis and LLM-judge
work are offline concerns. Runtime eval capture only persists an ordered, deduplicated journal and a
terminal result; the live path never reads those files back.

## Start Here

Run commands from the repository root, `/home/charles/PaiSmart`:

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

.venv-harness/bin/python -m harness_py --help
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

Read these files in order:

1. `orchestration/live_chat.py`: request-level wrapper and the boundary between a turn and stored conversation state.
2. `orchestration/runtime.py`: the small runtime interface and runtime selection.
3. `orchestration/agents/runtime.py`: construction and execution of the OpenAI Agents SDK loop.
4. `orchestration/agents/tools.py`: tool dispatch, progress events, answer validation, and eval events.
5. `corpus/tools.py`: the paper discovery and evidence authorization state machine.
6. `orchestration/conversation.py`: the durable conversation state accepted and returned by the harness.
7. `transport/service.py`: the internal HTTP contract used by Java.

## Directory Map

```text
harness_py/
  core/                    shared data shapes, contracts, statuses, errors
  corpus/                  corpus loading, parsing, retrieval, evidence creation
  orchestration/           conversation state and runtime-neutral turn orchestration
    agents/                default OpenAI Agents SDK implementation
    legacy/                rollback hand-written loop and direct MiniMax client
  evaluation/              Golden loading, fixtures, audits, scoring, judge, eval recorder
  transport/               provider configuration and internal HTTP service
  tests/                   unit and integration tests
  cli.py                   command-line composition root
  __main__.py              `python -m harness_py` entry point
  README.md                short package entry point
  ONBOARDING.md            this ownership guide
```

The dependency direction to preserve is:

```text
transport/cli -> orchestration -> corpus/core
evaluation   -> orchestration/corpus/core
```

`LiveResearchChatHarness.run_case()` and `EvalRecorder` are the two deliberate evaluation hooks in
the live path. Do not let scoring, judging, calibration, or offline analysis enter the runtime loop.

## Public Entry Points

| Entry point | Owner | Purpose |
| --- | --- | --- |
| `python3 -m harness_py` | `cli.py` | Local validation, Golden runs, chat, service, judge calibration |
| `LiveResearchChatHarness.run_turn()` | `orchestration/live_chat.py` | Canonical one-turn API |
| `HarnessRuntime.run_turn()` | `orchestration/runtime.py` | Runtime seam implemented by Agents SDK and legacy runtimes |
| `ResearchHarnessService.run_job()` | `transport/service.py` | Java-facing request adapter |
| `/v1/research/stream` | `transport/service.py` | NDJSON progress and terminal response |
| `/v1/research/turn` | `transport/service.py` | Synchronous local diagnostic endpoint |

The default runtime is `agents_sdk`. `legacy` is a rollback path, not the design target.

## One Turn, End To End

1. `ResearchHarnessService` validates `conversation_id`, `user_message`, and the paper IDs already authorized by Java.
2. `DockerMySqlProductCorpusStore` builds a request-scoped `GoldenDataset` for those papers.
3. The request history and prior evidence become a `ConversationState`.
4. `LiveResearchChatHarness` scopes the dataset, allocates a `run_id`, and optionally opens an `EvalRecorder`.
5. The harness converts durable state into `TurnExecutionInput`: text history, selected papers, prior evidence, progress callback, cancellation check, and recorder.
6. `AgentsSdkHarnessRuntime` creates a fresh `ResearchRunContext`, request-backed SDK session, agent, tools, and model adapter.
7. The model chooses its own research path. There is no fixed stage pipeline and no harness-defined ReAct round limit.
8. Tool calls mutate only request-scoped authorization and evidence state in `ReadingCorpusTools`.
9. The model must finish with `submit_research_answer` as the only tool call in that model step.
10. Python validates the draft against evidence actually read during this or a previous turn. Invalid drafts return a tool error to the same model loop for repair.
11. The accepted draft is normalized into the harness run artifact, then `ConversationState.updated_from_run()` keeps only conversation text and cited evidence needed by a later turn.
12. The service maps the run into the Java response and emits the final NDJSON result.

## State Authority

Three state objects have different jobs. Keeping them distinct prevents most memory bugs.

| State | Lifetime | Authority |
| --- | --- | --- |
| `ConversationState` | Across turns | User/assistant messages, selected papers, cited evidence, last run reference |
| `RequestBackedSession` | One run | Agents SDK view of request-provided text history plus current input |
| `ResearchRunContext` | One run | Tool authorization, newly read evidence, trace, progress, token and latency counters |

Important rules:

- Java or the CLI owns persistence of `ConversationState`; the Agents SDK session is never an independent memory store.
- Only user and assistant text is replayed as chat history. Full tool traces are not copied into later prompts.
- Prior cited evidence is passed separately as compact evidence cards and pre-authorizes its paper and location for follow-up turns.
- `ConversationState.updated_from_run()` advances memory only from the accepted run artifact.
- Resetting a conversation must clear history, selected papers, selected evidence, and the last run reference together.

## Tool Authorization

`ReadingCorpusTools` enforces a disclosure ladder so the model cannot invent internal identifiers:

```text
search_paper_candidates / find_papers_by_identity
  -> authorizes returned paper IDs
find_reading_locations
  -> accepts only authorized paper IDs and discloses location refs
read_locations
  -> accepts only disclosed location refs and creates citeable evidence IDs
submit_research_answer
  -> accepts only evidence IDs present in prior memory or this run
```

`get_citation_edges` is available only when the dataset contains citation edges. It requires an
authorized paper and authorizes connected papers for subsequent navigation. Search cards, location
previews, and graph edges are navigation metadata; only `read_locations` creates paper-content
evidence.

## Final Answer Contract

The final tool schema is defined by `_final_answer_tool()` in `orchestration/legacy/harness.py` and
is shared by both runtimes. Validation is also shared through `_answer_validation_error()`.

An answer is accepted only when:

- `submit_research_answer` is the sole tool call in the final model step;
- its structure and status/outcome combination are valid;
- cited evidence IDs exist in prior memory or the current run;
- paper-content claims use the required `[[evidence_id]]` citation syntax;
- citations and the evidence ledger are internally consistent.

The MiniMax adapter converts a text-only model response into an internal continuation tool call. This
keeps the SDK loop alive and instructs the model to finish through the required submission tool.

## Eval Capture

Eval capture is deliberately simple and write-only from the runtime's perspective. Enable it with
`--eval-dump DIR` or `EVAL_DUMP_DIR=DIR`.

```text
DIR/<run_id>/events.jsonl   append-only ordered events
DIR/<run_id>/result.json   atomic terminal result
```

`EvalRecorder` gives every event a stable ID, rejects duplicate IDs, assigns a monotonically
increasing sequence, flushes each JSONL record, and atomically renames the terminal result. Recorded
events include model requests/responses, tool start/completion/error, answer validation, and run
start/error. Authorization headers and API keys are not recorded.

Capture failure does not change the model result, but it increments `eval_capture_failures`. A CLI
`agent-run` requested with `--eval-dump` exits with code 2 if capture failed. All interpretation,
aggregation, reward construction, and research analysis belongs in offline code outside the live
orchestration path.

## Golden Evaluation

The Golden path has four separate layers:

| Layer | Module | Model call? | Question answered |
| --- | --- | --- | --- |
| Fixture validation | `evaluation/golden_fixture.py`, `evaluation/scoring.py` | No | Are authored expectations and scoring internally coherent? |
| Anchor audit | `evaluation/audit.py` | No | Do authored page/anchor expectations match parsed corpus content? |
| Live execution | `LiveResearchChatHarness.run_case()` | Yes | Does the real runtime satisfy observable Golden behavior? |
| Judge calibration | `evaluation/judge.py` | Yes | Does the judge agree with fixed human labels on qualitative criteria? |

Golden expectations cover observable outcome, retrieval, structured facts, and grounding. They do
not prescribe skill choice, tool order, tool count, or exact prose. See
`research/golden-data/README.md` for the runbook and manifest split.

## Where To Change Things

| Goal | Start here | Usually also touch |
| --- | --- | --- |
| Change the agent instructions | `orchestration/legacy/harness.py` | Golden live cases |
| Add or change a corpus tool | `corpus/tools.py` | `orchestration/agents/tools.py`, tool tests |
| Add a research skill | `orchestration/research_skills.py` | skill count/registry tests |
| Change final-answer validation | `orchestration/legacy/harness.py` | Agents tool tests, Golden scoring |
| Change conversation memory | `orchestration/conversation.py` | service and follow-up tests |
| Change MiniMax/SDK transport | `orchestration/agents/model.py` | model adapter tests |
| Add a runtime | `orchestration/runtime.py` | `cli.py`, service runtime option |
| Change product DB loading | `corpus/product_db_dataset.py` | corpus summary and service tests |
| Change Java HTTP contract | `transport/service.py` | Java caller and service tests |
| Add Golden data | `research/golden-data/` | deterministic validation, audit, selected live run |
| Change scoring | `evaluation/scoring.py` | fixture and scorer tests, never live orchestration |

## Debugging Order

When a run is wrong, inspect it in this order:

1. Confirm Java or CLI supplied the intended `scope.paper_ids`, history, and prior evidence.
2. Inspect `ConversationState` and the request-scoped dataset before blaming the model.
3. Inspect `react_trace` for tool arguments and model-visible tool results.
4. Inspect `evidence_ledger` and `citation_validation` for deterministic rejection reasons.
5. Inspect `diagnostics.finish_reason`, model-call count, usage, and latency.
6. If enabled, inspect `events.jsonl` by `sequence`, then compare it with `result.json`.
7. Reproduce against one Golden case with repeated `--case-id` flags before running a whole manifest.

Common failure boundaries:

- `paper_not_authorized_for_reading`: the model skipped paper discovery or identity resolution.
- `location_not_disclosed_for_reading`: the model skipped location search or reused an unknown ref.
- final validation error: the draft cited an unread ID, used invalid syntax, or submitted final alongside another tool.
- `FAILED_TECHNICAL`: provider, transport, SDK, or unexpected runtime failure; inspect diagnostics and eval events.
- correct hard score but questionable prose: run the separately calibrated judge rather than adding runtime heuristics.

## Verification Commands

Stable deterministic Golden validation and anchor audit:

```bash
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py audit --out /tmp/paismart-anchor-audit.json
```

Focused orchestration tests:

```bash
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_agents_model \
  harness_py.tests.test_agents_tools \
  harness_py.tests.test_agents_runtime \
  harness_py.tests.test_service \
  harness_py.tests.test_eval_recorder
```

All Python tests:

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

One live Golden case with eval capture:

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --runtime agents_sdk \
  --eval-dump /tmp/paismart-agent-eval \
  --out /tmp/paismart-agent-run
```

## Design Guardrails

- Keep `HarnessRuntime` small. Runtime implementations should consume `TurnExecutionInput` and return a normalized run.
- Keep model choice in the model loop. Do not reintroduce a fixed intent/retrieval/claim pipeline around it.
- Keep deterministic enforcement at trust boundaries: scope, tool authorization, final answer, citations, and persistence.
- Keep eval persistence append-only and analysis-free. New research fields should normally be derived offline.
- Keep Java responsible for authentication, Redis state, reconnect behavior, permissions, cancellation ownership, and usage settlement.
- Keep the legacy runtime working as a rollback path, but put new orchestration behavior into the Agents SDK runtime first.
- Prefer extending existing data shapes and tools over adding orchestration abstractions with no current caller.

