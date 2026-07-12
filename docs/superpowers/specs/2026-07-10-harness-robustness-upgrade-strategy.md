# Harness Robustness Upgrade Strategy

## Decision

Adopt an **action-first, evidence-required** control policy for the disposable Python harness.

The harness must not predict user topics or maintain a catalogue of question-specific routes. The
LLM still interprets the current turn and selects one of the existing paradigm plans, but the
runtime makes two generic decisions explicit:

1. Is an uncertainty blocking, or can the harness answer usefully under a stated assumption?
2. Does the intended answer make corpus-grounded factual or recommendation claims that require
   fresh corpus observations?

This is a targeted correction to the observed terminal-chat failure. It does not introduce a
dynamic workflow compiler, more research tools, a database migration, frontend work, or a broad
test programme.

## Observed Failure

The July 10 live chat ran five turns, 20 model calls, and about 89k tokens without one tool call
or a paper recommendation. The request evolved from a broad LLM-paper recommendation, through
`3`, `LLM-based agents`, `e`, and `all`, then ended as `INCOMPLETE_PRECISE`.

The failure is architectural:

- intent framing converts any ambiguity into the `ambiguity_resolution` paradigm;
- a `needs_clarification` result stops the complete semantic-stage plan;
- stage-scoped tools are allowed, but identity and recommendation stages do not have to call
  them;
- options that the harness asks the user to choose are rendered as prose, not persisted as a
  typed pending interaction;
- the final turn tried to serialise the whole corpus as selected IDs and yielded an unparseable
  stage result rather than retrieving a small, justified shortlist.

The corpus, model configuration, and tool transport are not the primary defect.

## Adopted Decisions

### 1. Broad Does Not Mean Blocked

**Adopted:** A request is actionable unless its uncertainty changes the target in a material way.

The LLM must label uncertainty as one of:

```text
none                 proceed
non_blocking         proceed with a named assumption or coverage policy
blocking             ask one focused question before research
```

Examples of `blocking` uncertainty are an unresolved same-title paper identity, a required paper
version, incompatible hard constraints, or a question whose answer depends on an unspecified
comparison target. Breadth, preferred sub-area, answer length, or a request for a representative
set are `non_blocking`: retrieve a diversified answer first.

This classification is semantic and LLM-driven. There are no topic keywords such as "LLM agents"
or a special-case recommendation router.

### 2. Clarification Is an Interaction, Not a Research Paradigm

**Adopted:** Keep `ambiguity_resolution` for genuinely blocked research, but do not let it become
the default route for open-ended chat.

When the harness does ask a question, it persists a compact `PendingInteraction` in conversation
state:

```json
{
  "interaction_id": "scope_01",
  "kind": "choice",
  "question": "Which scope should I use?",
  "options": [
    {"id": "overview", "state_patch": {"scope": "overview"}},
    {"id": "broad", "state_patch": {"scope": "broad"}}
  ]
}
```

The terminal may display ordinal shortcuts, but their mapping is bound to this interaction. A
reply such as `3` or `e` is resolved only against the currently pending interaction, before a new
intent frame is requested. It is a narrow conversation protocol, not a content keyword router.
Free-form follow-ups are still interpreted by the LLM against the persisted interaction and recent
turns. Once resolved, the state patch is applied and the interaction is cleared.

The runtime never tries to reconstruct the meaning of a previous choice from assistant prose.

### 3. Evidence Is Required Before a Corpus-Grounded Answer

**Adopted:** If the answer recommends corpus papers or asserts paper facts, its plan must produce
at least one fresh corpus observation before it can complete.

The chosen paradigm controls which semantic stages and tools are useful. The runtime adds only one
cross-cutting completion guard:

- a recommendation must inspect a candidate list from the corpus;
- a justification beyond title/metadata must inspect reading-model evidence;
- factual claims must cite inspected evidence;
- a missing observation produces a precise, named gap, not an empty "completed" answer.

The runtime must not pass the full paper inventory into the intent or stage prompt as a substitute
for tool use. It may provide corpus size and allowed capability names. Tool results become the
only source for paper cards and evidence accepted into the research state.

No new tool is needed in this slice. Reuse `list_papers`, `find_papers_by_identity`,
`search_reading_locations`, and `read_locations`; improve tool semantics later only when golden
data or user experience demonstrates a limitation.

### 4. Recommendation Is a Small Research Answer

**Adopted:** For a broad recommendation, the LLM selects a normal existing paradigm and produces
a short plan that discovers candidates, inspects enough evidence to justify a small diversified
set, then answers. It must not select every scoped paper.

The answer should state its coverage policy, for example: "I treated this as a broad introduction
to LLM agents and selected a survey, evaluation benchmarks, and task-oriented agent papers from
the available corpus." A follow-up question may be offered after the answer, but it must not
block the first useful result.

This is not a special `recommendation` paradigm. It is an answer contract applied to whichever
semantic plan the LLM chooses for the request.

### 5. Stages May Stop Themselves, Not the Whole Turn by Accident

**Adopted:** A nonterminal stage may report a local missing obligation, but only a terminal
blocking interaction, exhausted evidence contract, or technical failure may end the turn.

The orchestration loop must distinguish:

```text
continue                stage completed or locally incomplete; later stage can still act
request_user_input      explicit terminal PendingInteraction
incomplete_precise      required evidence was attempted and remains unavailable
technical_failure       invalid model/tool result after bounded repair
```

An identity-resolution stage that has not used a permitted tool is not a reason to stop. An
evidence-required stage that returns no observation gets one repair instruction, then a named
failure. The runtime never retries by asking the model to list the entire corpus in JSON.

### 6. Optional Review Artefacts Stay Optional

**Adopted:** The chat answer is the primary product. `IntentFrame`, plan, stage trace, evidence
ledger, claim graph, and verification remain available as optional review panes or terminal
artifacts. Do not expose chain-of-thought.

The minimal user-facing trace should show only the selected papers/evidence and a concise final
status. Diagnostic panes are opened deliberately for review, golden-data investigation, and
frontend work.

## Minimal Implementation Slice

Implement only these changes in `harness_py`:

1. Add `actionability` (`none`, `non_blocking`, `blocking`) to the structured intent result and
   stop coercing all ambiguous intents to `ambiguity_resolution`.
2. Add a typed pending-interaction field to `ConversationState`; resolve it before intent
   recognition on the next turn.
3. Change stage completion so only the three terminal conditions in Decision 5 stop the plan.
4. Add the generic corpus-observation guard for recommendation and factual answer contracts.
5. Remove full `available_papers` injection from LLM prompts; keep tool schemas and concise
   corpus metadata.
6. Add a narrow candidate-cap to prevent a stage from returning every paper ID as state.

The 22 explicit paradigm stage lists remain in place. Do not add a new planner, dynamic DAG,
additional database tables, frontend controls, or an expanded tool catalogue during this loop.

## Verification and Closure

Use one new live-chat golden regression based on the reported conversation. Its assertions are:

- the initial broad recommendation is either answered immediately or asks at most one focused,
  persisted clarification;
- option replies resolve against the pending interaction without a repeated question;
- at least one corpus tool is called before paper recommendations;
- the final result is `COMPLETED`, cites corpus observations, and recommends a bounded set rather
  than all scoped papers.

Run that regression and the existing Python harness tests. Then run one manual terminal chat
against the product database. The closure signals are the regression artefact and the user's chat
experience, not synthetic coverage across all 22 paradigms.

## Explicit Non-Goals

- No question-specific string-matching routes.
- No prediction of future user topics or sub-areas.
- No mandatory clarification tree.
- No broad rewrite of the 22-paradigm catalogue.
- No Java execution in this exploration phase.
- No attempt to prove every paradigm through a large new test suite.
