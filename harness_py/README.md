# Python Research Harness

This package is the lightweight exploration harness for paper RAG.

```text
persisted conversation
+ one model-driven ReAct loop
+ 22 optional research skills
+ corpus tools
+ deterministic citation validation
```

The public runtime is `LiveResearchChatHarness.run_turn(...)`. Golden agent runs and terminal chat
use that same runtime.

## Runtime

The model receives the complete stored user/assistant history, the current message, and previously
cited evidence. In one continuous loop it may answer directly, clarify, load skills, search, read,
continue researching, partially answer, or abstain. There is no fixed stage pipeline and no
ReAct-round limit.

Corpus tools:

```text
search_paper_candidates
find_papers_by_identity
find_reading_locations
read_locations
get_citation_edges       # when available
```

Control tools:

```text
get_research_skill
submit_research_answer
```

Paper-content claims cite `[[evidence_id]]`. Python validates citation identity before accepting the
answer and returns citation errors to the same loop for repair.

## Conversation

`ConversationState` persists messages, selected papers, cited evidence, and per-turn traces. The
newest user message may continue or replace the prior topic without a separate context state
machine.

In `chat-shell`:

```text
/history
/state
/clear
/new
/save
/exit
```

`/clear` and `/new` reset model-visible context.

## Commands

Validate authored golden behavior without calling the model:

```bash
python3 -m harness_py validate
python3 -m harness_py audit
```

Run selected golden cases through MiniMax:

```bash
python3 -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --out /tmp/paismart-agent-run
```

Open terminal chat over papers in the product database:

```bash
python3 -m harness_py chat-shell \
  --limit 30 \
  --state /tmp/paismart-chat-state.json \
  --out /tmp/paismart-chat-runs
```

Run the internal service used by the Java application:

```bash
export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3
python3 -m harness_py serve --host 127.0.0.1 --port 8091
```

Java calls `/v1/research/stream` over internal HTTP and consumes an NDJSON stream containing model,
tool, evidence, and terminal-result events. Java owns authentication, Redis generation state,
reconnection, permissions, and usage settlement. The Python harness does not access Redis and has no
overall execution deadline. `/v1/research/turn` remains available for local synchronous diagnostics.

Progress events are derived from model-call lifecycle, tool arguments, and tool results. They do not
use an additional LLM summarization call and do not expose chain-of-thought.

`--limit` caps papers loaded from the product database. `--state` persists conversation context.
`--out` writes optional evidence, skill, ReAct trace, citation-validation, and answer artifacts.

## Evaluation

Golden scoring checks observable outcome, retrieval, structured content, and citations. It does not
grade skill choice, tool sequence, tool count, or exact prose. `judge-calibrate` runs the separate
LLM-as-judge agreement check against fixed human labels.

Focused verification:

```bash
python3 -m unittest harness_py.tests.test_harness_py harness_py.tests.test_golden_v2
```
