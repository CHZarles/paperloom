# Python Research Agent Harness

This is a fast prototype of the research harness as a Python module.

It has three runnable modes:

- `ContractDrivenHarness`: deterministic contract fixture used to validate the trace shape.
- `ResearchAgentHarness`: real tool-using agent harness backed by MiniMax through the product DB provider config.
- `LiveResearchChatHarness`: one-turn chat wrapper over the same tool loop for current product DB papers.

The real agent harness keeps one external interface:

```python
ResearchAgentHarness(model).run_case(dataset, golden_case) -> HarnessRun
```

The trace artifacts are emitted separately so the frontend can make each pane optional:

```text
IntentFrame
RetrievalPlan
EvidenceLedger
ClaimGraph
ReasoningArtifact
VerificationPass
ResearchAnswer
```

## Tools

The agent receives OpenAI-compatible tool definitions for:

- `list_papers`
- `find_papers_by_identity`
- `search_reading_locations`
- `read_locations`
- `get_citation_edges`

Tools operate over PaperLoom reading-model JSON and paper-pack metadata. They do not contain
paper-specific branches. In eval mode, returned evidence is generically aligned to golden anchors
by paper/page/text overlap so scoring can inspect whether the correct evidence was found.

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
`intent_frame.json`, `retrieval_plan.json`, `evidence_ledger.json`, `claim_graph.json`,
`verification_pass.json`, and `research_answer.json`.

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

The state stores conversation messages, selected paper ids, selected evidence ids, unresolved
clarification choices, accumulated evidence cards, claim memory, and scope. It is used only as
conversation memory; the agent still has to call tools before making new paper-content claims.

Use `chat-shell` when you want to stay inside one terminal session instead of running one command
per turn:

```bash
python3 -m harness_py chat-shell \
  --paper-query "agent evaluation" \
  --state /tmp/product-db-chat-state.json \
  --out /tmp/product-db-chat-runs
```

Inside the shell, type normal questions. Commands are `/state`, `/save`, and `/exit`.

Environment-only mode is also available:

```bash
MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1 \
MINIMAX_MODEL=MiniMax-M3 \
MINIMAX_API_KEY=... \
python3 -m harness_py --manifest research/golden-data/manifest.yaml agent-run --provider-source env
```

## Validation

Deterministic contract smoke:

```bash
python3 -m harness_py --manifest research/golden-data/manifest.yaml validate
```

Unit tests:

```bash
python3 -m unittest harness_py.tests.test_harness_py -v
```
