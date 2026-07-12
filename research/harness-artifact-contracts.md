# Research Harness Artifact Contracts

Canonical machine-readable contract: `research/golden-data/artifact-contracts.yaml`.

The skill-guided ReAct harness persists only artifacts that actually exist during execution:

```text
HarnessRun
|- ResearchAnswer
|- EvidenceLedger
|- PaperCandidates
|- SkillsUsed
|- ReActTrace
|- CitationValidation
`- Diagnostics
```

## HarnessRun

One conversation turn. It carries execution status, timestamps, the natural answer, evidence,
optional inspection data, and the state update required by the next turn.

## ResearchAnswer

The model-authored user-facing response accepted through `submit_research_answer`. Its outcome is
`answered`, `needs_clarification`, `partial`, or `abstained`. Technical failure is an execution
status and does not pretend to be a research outcome.

## EvidenceLedger

The passages actually read during the turn plus persisted passages cited again by a follow-up.
Candidate cards and location previews are not evidence. Every cited id must resolve to a citeable
ledger item.

## ReActTrace

The actual skill and corpus tool calls. It exists for debugging, evaluation, and an optional
frontend pane. It is not a predeclared plan and is not exposed as private chain-of-thought.

## CitationValidation

The deterministic result of checking citation existence, citeability, and the minimum citation
requirement after corpus use. It is a reference-integrity check, not a semantic truth certificate.

## ScoreReport

Eval-only deterministic scoring for outcome, retrieval, structured content, and grounding. Skill
selection, tool sequence, tool count, and exact prose are deliberately excluded. The calibrated
LLM judge remains a separate offline quality signal.
