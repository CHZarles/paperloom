# Harness Golden Data Strategy

Golden data is the main engineering feedback loop for the paper-RAG harness, together with manual
conversation review. It should stay small, understandable, and behavior-first.

## Case Shape

Each case contains:

```text
conversation messages
paper-pack scope
paradigm capability tag
expected outcome
required and forbidden papers
required and forbidden evidence anchors
optional structured facts
citation policy
short human review criteria
```

The paradigm is an authoring tag used to track coverage. It is not shown to the model and does not
require the runtime to load the matching skill.

## What To Score

Deterministic scoring covers:

- outcome
- required and forbidden papers
- required and forbidden evidence
- structured fact values when the model supplies structured fields
- citation presence and evidence identity

One calibrated LLM judge covers:

- task fulfillment
- semantic grounding
- overall answer quality
- semantic correctness when an otherwise good natural answer omits optional structured fields

Human labels remain the calibration source for the judge. Manual terminal conversations remain the
quality signal for clarity, follow-up behavior, and topic changes.

## What Not To Score

Do not make golden expectations for:

- selected skill ids
- tool order or tool count
- ReAct iterations
- fixed stages
- private reasoning
- exact answer wording

## Expansion Rule

Do not enlarge the corpus merely to make the benchmark look substantial. Add a paper or case only
when it exposes a missing retrieval capability, a grounding failure, a conversation failure, or an
uncovered paradigm from `research/remake.md`.

The next engineering action after a failure should be the smallest relevant change to the prompt,
skill guidance, corpus tool, or paper pack. Do not introduce another router or state machine.
