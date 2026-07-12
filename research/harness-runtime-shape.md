# Research Harness Runtime Shape

This is the authoritative runtime proposal for the Python paper-RAG exploration harness. It
supersedes the earlier fixed semantic-stage pipeline.

## Objective

Keep the harness simple, reliable, and flexible without replacing the model's semantic judgment
with routers or state machines.

```text
persisted conversation
+ one model-driven ReAct loop
+ 22 optional research skills
+ paper-corpus tools
+ deterministic citation validation
```

The primary interface is:

```python
run_turn(dataset, conversation_state, user_message)
    -> (harness_run, updated_conversation_state)
```

## Runtime

```text
conversation history + current message + previously cited evidence
                            |
                            v
                 one continuous ReAct loop
                            |
       answer | clarify | load skills | search | read | continue
                            |
                  submit_research_answer
                            |
                 deterministic citation gate
                            |
       natural answer + evidence + optional trace + persisted state
```

There is no separate intent call, keyword router, fixed stage sequence, clarification counter,
retrieval-plan compiler, or ReAct-round limit. Provider timeouts and user cancellation are
operational controls, not semantic iteration limits.

## Responsibility

The model owns semantic decisions:

- Interpret the current turn using conversation history.
- Detect a follow-up or topic change.
- Answer directly, ask one blocking clarification, research, partially answer, or abstain.
- Select zero, one, or several research skills.
- Choose and adapt tool calls and decide when evidence is sufficient.
- Write the user-facing answer.

The Python harness owns mechanics and integrity:

- Persist messages, selected papers, cited evidence, and per-turn traces.
- Expose and authorize tools against the current corpus scope.
- Execute tools and preserve stable evidence identities.
- Validate final citation references and return validation errors to the same loop for repair.
- Persist optional inspection artifacts and report technical failures separately.

No second router or judge participates in the live answer path. LLM-as-judge is evaluation only.

## Research Skills

The 22 paradigms in `research/remake.md` are advisory skills, not routes. Each skill contains when
to use it, retrieval guidance, an evidence standard, and answer-shape guidance. The model may load
none, one, or several through `get_research_skill`.

Skill selection is not a golden expectation. A question that does not yet define an actionable
research task should receive one natural clarification question. The assistant must not expose
machine obligations or repeatedly ask for optional constraints.

## Tools

The textual MVP exposes:

```text
get_research_skill
search_paper_candidates
find_papers_by_identity
find_reading_locations
read_locations
get_citation_edges       # only when graph data exists
submit_research_answer
```

Candidate cards and location previews are navigation only. Paper-content claims cite exact passages
returned by `read_locations` or evidence already persisted in the conversation.

New table, figure, formula, PDF, or external-search tools should be added only when a concrete
golden failure requires that capability. They do not require a new orchestration architecture.

## Citation Gate

The model cites evidence with `[[evidence_id]]`. Before accepting the final answer, Python verifies:

- The outcome and answer body are valid.
- Every cited id exists and is citeable.
- A corpus-grounded answered or partial result contains a citation.
- `submit_research_answer` is the sole final tool call.

This gate proves reference integrity, not semantic entailment. Task fulfillment and grounding are
measured by golden data, calibrated LLM-as-judge, and manual chat review rather than more runtime
machinery.

## Conversation

The durable state stores full user/assistant messages, selected papers, cited evidence, and tool
traces. The newest user message naturally controls the current focus. There is no active-task or
pending-choice state machine.

`/clear` resets the current context. `/new` starts a fresh context. Full-history persistence is the
prototype policy; compaction is deferred until an observed context-window problem requires it.

## Inspectable Output

The default interface shows only the natural answer. Optional panes are derived from actual events:

- cited passages
- paper candidates
- ReAct tool calls
- skills used
- corpus gaps
- usage and latency diagnostics

The runtime does not manufacture `IntentFrame`, `RetrievalPlan`, `StageTrace`, `ClaimGraph`,
`ReasoningArtifact`, or `VerificationPass` objects. Raw chain-of-thought is never exposed.

The canonical run artifacts are documented in `research/harness-artifact-contracts.md`.

## Evaluation Gate

Golden cases grade observable outcomes, papers, evidence anchors, supplied structured fields, and
citations. Semantic correctness in natural prose is judged separately. Golden scoring does not
grade skill selection, tool order, tool count, stage order, or exact prose.

The practical release signal is deliberately small:

1. Existing golden cases remain valid and score correctly.
2. Greeting, clarification, follow-up, grounded research, corpus-gap, and topic-change chat behavior
   remains understandable.
3. LLM-as-judge broadly agrees with the fixed human labels.

The harness should evolve only from a concrete golden failure or a concrete user-experience failure.
