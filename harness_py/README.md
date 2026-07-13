# Python Research Harness

This package owns PaiSmart's model-orchestrated paper research turn. The default runtime uses the
OpenAI Agents SDK with MiniMax, request-scoped conversation memory, guarded corpus tools, and
deterministic final-answer and citation validation. The hand-written `legacy` runtime remains only as
a rollback option.

The implementation deliberately favors small standard-library helpers and explicit state over new
framework layers. Prompts, command defaults, endpoint paths, and artifact paths are treated as
behavioral contracts during refactoring.

Start with [ONBOARDING.md](ONBOARDING.md) for the architecture, one-turn execution flow, state
authority, tool authorization, eval capture, extension points, and debugging guide.

Golden-data structure and test commands live in
[`research/golden-data/README.md`](../research/golden-data/README.md).

## Layout

```text
core/             shared models, contracts, statuses, errors
corpus/           corpus loading and evidence-producing reading tools
orchestration/    conversation state and runtime-neutral turn boundary
  agents/         default OpenAI Agents SDK runtime
  legacy/         rollback hand-written loop and direct MiniMax client
evaluation/       Golden fixtures, audit, scoring, judge, eval recorder
transport/        provider configuration and Java-facing HTTP service
tests/            Python unit and integration tests
cli.py            command-line composition root
```

## Quick Start

Run from the repository root:

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py audit --out /tmp/paismart-anchor-audit.json
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

Run one live Golden case:

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --runtime agents_sdk \
  --eval-dump /tmp/paismart-agent-eval \
  --out /tmp/paismart-agent-run
```

Run the internal service used by Java:

```bash
export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3

.venv-harness/bin/python -m harness_py serve \
  --runtime agents_sdk \
  --host 127.0.0.1 \
  --port 8091
```

Java calls `/v1/research/stream` and consumes NDJSON progress plus one terminal result. Python does
not own authentication, Redis state, reconnect behavior, permissions, or usage settlement.

## Eval Capture

Set `EVAL_DUMP_DIR` or pass `--eval-dump`. Each execution writes only:

```text
<eval-dir>/<run_id>/events.jsonl
<eval-dir>/<run_id>/result.json
```

The runtime never reads or analyzes these files. Evaluation research remains offline.
