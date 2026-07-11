# Python Research Agent Harness

This is a fast prototype of the research harness as a Python module.

It has three runnable modes:

- `GoldenFixtureHarness`: deterministic behavior fixture used to validate authored v2 cases and scorer output.
- `ResearchAgentHarness`: paradigm-driven semantic-stage harness backed by MiniMax through the product DB provider config.
- `LiveResearchChatHarness`: multi-turn chat wrapper over the same stage runtime for current product DB papers.

The real agent harness keeps one external interface:

```python
ResearchAgentHarness(model).run_case(dataset, golden_case) -> HarnessRun
```

For committed golden cases, `agent-run` uses:

```python
LiveResearchChatHarness(model).run_case(dataset, golden_case) -> HarnessRun
```

That path builds a `ConversationState` from each case's prior `messages`, then calls `run_turn`
with the final user message. Follow-up cases therefore receive the same conversation history used by
live chat instead of being flattened into a single prompt.

The trace artifacts are emitted separately so the frontend can make each pane optional:

```text
IntentFrame
RetrievalPlan
StageTrace
EvidenceLedger
ClaimGraph
ReasoningArtifact
VerificationPass
ResearchAnswer
```

## Runtime Shape

The production path no longer uses one unrestricted top-level ReAct loop. Each turn runs as:

```text
current message + bounded conversation context + active task + pending interaction
-> one forced structured TurnDecision: direct | clarify | research
-> direct answer, one persisted clarification, or one explicit paradigm plan
-> sequential online semantic stages for research only
-> deterministic evidence/claim verification
-> ResearchAnswer
```

`TurnDecision` is the single owner of conversation interpretation. A reply to a pending choice is
merged into the authoritative `active_task` and immediately resumes research; it is not reclassified
as an isolated question and cannot complete with a choice acknowledgement. Greetings and other
non-research turns bypass the semantic-stage runtime entirely.

A stage receives only its semantic goal, current research state, compact output contract, and
stage-scoped tools. Evidence and claims are applied to live shared state before the next stage.
`stage_trace.json` records inspectable goals, decisions, tool calls, evidence links, status, and
missing obligations; it does not persist chain-of-thought.

Open-ended requests are action-first: broadness and preference gaps become a stated coverage
assumption rather than a clarification loop. Clarification is reserved for unresolved paper identity,
unresolved conversational reference, or a missing hard constraint. A corpus recommendation or
paper-content claim must observe the corpus through its stage tools before it can complete.

All 22 paradigms from `research/remake.md` now have short explicit semantic-stage plans. The
golden-validated slice currently covers these nine paradigms as primary intents:

- `precision_fact_extraction`
- `methodology_reproduction`
- `deep_comparison`
- `association_influence_genealogy`
- `complex_multihop_reasoning`
- `uncertainty_knowledge_boundary`
- `ambiguity_resolution`
- `contradiction_resolution`
- `context_specific_brainstorming`

Each paradigm is a short explicit stage list in `stage_prototype/plans.py`; there is no generic
workflow compiler or keyword router. The other thirteen plans are implemented but remain provisional
until dedicated golden cases and live corpus smokes are added.

## Behavior Scoring

`BehaviorScorer` scores observable behavior, not runtime internals. A case `hard_pass` is true only
when every configured dimension avoids `fail`. The four dimensions are:

- `outcome`: the observed answer outcome matches the authored expectation.
- `retrieval`: required and forbidden papers or evidence anchors are respected.
- `content`: expected scalar facts are present with matching normalized values.
- `grounding`: citation policy, cited evidence ids, and supported claim grounding are valid.

Claim rubrics and review strings are carried forward as `review.status=not_run` until human review or
a later semantic judge exists. Stage order, tool count, intent labels, and answer prose are not golden
expectations.

## Tools

The agent receives OpenAI-compatible tool definitions for:

- `search_paper_candidates`
- `find_papers_by_identity`
- `find_reading_locations`
- `read_locations`
- `get_citation_edges` when the corpus has citation-edge data

The enforced retrieval path is `paper candidate -> reading location -> citeable evidence`.
Candidate cards and location previews are navigation only; `read_locations` is the sole producer of
paper-content evidence. Candidate search reports complete or truncated in-corpus coverage, strict
identity lookup returns resolved, ambiguous, or not-found, and ambiguous matches cannot authorize
reading. Tools operate over PaperLoom reading-model JSON and paper-pack metadata without
paper-specific branches.

## MiniMax Provider

The default live provider source is the product database:

```bash
python3 -m harness_py --manifest research/golden-data/manifest.yaml agent-run \
  --case-id transformer_adam_params_001 \
  --out /tmp/minimax-agent-smoke
```

`DockerMySqlProviderConfigStore` reads the active `llm` provider from `model_provider_configs`,
decrypts `api_key_ciphertext` with the same AES/GCM format as `SecretCryptoService`, and calls
`/chat/completions` on the configured base URL. Secrets are not written to run artifacts.

Environment-only mode is also available:

```bash
MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1 \
MINIMAX_MODEL=MiniMax-M3 \
MINIMAX_API_KEY=... \
python3 -m harness_py --manifest research/golden-data/manifest.yaml agent-run --provider-source env
```

## Live Product DB Chat

The chat mode loads current `READING_MODEL_READY` papers from the product database, adapts
`paper_reading_models` and `paper_reading_elements` into the same in-memory corpus shape as golden
data, and runs the same artifact-producing agent loop without `GoldenCase` scoring:

```bash
python3 -m harness_py chat \
  --question "Which papers in the library discuss agent evaluation?"
```

Scope the corpus when you want a smaller or more inspectable run:

```bash
python3 -m harness_py chat \
  --paper-id 8b9e2f64d565137ca85ae75029e77fff \
  --question "What evaluation dimensions does this survey discuss?" \
  --out /tmp/product-db-chat
```

When `--out` is set, the command writes the same optional frontend panes:
`intent_frame.json`, `retrieval_plan.json`, `stage_trace.json`, `evidence_ledger.json`,
`claim_graph.json`, `verification_pass.json`, and `research_answer.json`.

Persist `ConversationState` when you want real multi-turn follow-ups:

```bash
python3 -m harness_py chat \
  --paper-query "agent evaluation" \
  --question "Which loaded papers discuss evaluation of LLM-based agents?" \
  --state /tmp/product-db-chat-state.json

python3 -m harness_py chat \
  --question "Compare the first two using the same evidence." \
  --state /tmp/product-db-chat-state.json
```

The state stores conversation messages, one authoritative active task, a presentation-only pending
interaction, selected paper and evidence ids, accumulated evidence cards, claim memory, and scope.
The next `TurnDecision` receives that state and merges replies such as `2`, `the third`, corrections,
or confirmations into the active task before research continues. The agent still has to call tools
before making new paper-content claims.

Use `chat-shell` when you want to stay inside one terminal session instead of running one command per
turn:

```bash
python3 -m harness_py chat-shell \
  --paper-query "agent evaluation" \
  --state /tmp/product-db-chat-state.json \
  --out /tmp/product-db-chat-runs
```

Inside the shell, type normal questions. Commands are `/state`, `/save`, and `/exit`.

## Validation

Deterministic behavior fixture validation:

```bash
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
```

Unit tests, including all twelve committed golden cases and all nine primary paradigms through the
semantic-stage runtime:

```bash
python3 -m unittest discover -s harness_py/tests -v
```

The committed cases include three history snapshots for the real chat path:
`bert_choice_followup_001`, `transformer_bert_confirmation_followup_001`, and
`adam_constraint_refinement_followup_001`.
