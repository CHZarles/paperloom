# Behavior-First Golden Data Design

Date: 2026-07-11
Status: Approved design, pending written-spec review

## Decision

Replace the current implementation-coupled `GoldenCase` authoring format with a small behavior-first format. A case specifies the conversation presented to the harness and the externally observable result that must follow. It does not prescribe intent labels, retrieval stages, tool counts, claim identifiers, or trace order.

The harness may continue to emit rich runtime artifacts for debugging and optional frontend review panes. Those artifacts are not the source of truth for most pass/fail decisions.

## Goals

- Keep a case short enough to author and review manually.
- Keep cases valid when the harness, stages, tools, or model are replaced.
- Test the parts that matter in paper RAG: outcome, source selection, evidence, answer content, and grounding.
- Support single-turn and history-dependent conversations with one input shape.
- Prefer deterministic checks and expose uncertainty instead of hiding it in one composite score.
- Keep parser verification reproducible without mixing generated parser details into hand-authored gold data.

## Non-Goals

- Prescribing the harness's internal reasoning process.
- Requiring a particular stage sequence or tool-call count.
- Treating generated parser offsets as manually curated truth.
- Maintaining compatibility with the current v1 case schema.
- Building a universal academic benchmark before the existing nine cases are useful in live chat.

## Why V1 Is Too Complex

The current format combines four different contracts:

1. Benchmark input and expected behavior.
2. Harness implementation details such as intent and retrieval plans.
3. Runtime trace validation.
4. Parser audit and compatibility-export metadata.

This duplicates the same expectation across `expected_intent`, `expected_retrieval_plan`, `gold_evidence`, `gold_claims`, `answer_contract`, `required_trace`, and `compatibility_projection`. It also makes a correct result fail when a rewritten harness reaches it through a different plan.

## Dataset Layout

Keep three authored concepts and move generated details out of them:

```text
research/golden-data/
├── manifest.yaml
├── cases/
│   └── *.yaml
└── paper-packs/
    └── *.yaml

data/golden/<paper-pack>/
├── pdfs/
├── text/
├── reading-models/
├── visual-assets/
└── generated-audit/
```

`manifest.yaml`, cases, paper identities, and evidence anchors are authored. Parser matches, offsets, hashes, extraction statistics, and compatibility exports are generated.

## GoldenCase V2

### Required Shape

```yaml
schema_version: harness-golden-case/v2
id: transformer_adam_params
paradigm: precision_fact_extraction
paper_pack: transformer_bert_gpt

messages:
  - role: user
    content: "What beta1, beta2, and epsilon did the Transformer paper use?"

expect:
  outcome: answered
  papers:
    required:
      - attention_is_all_you_need_2017
  evidence:
    required:
      - transformer_adam_training_params
  facts:
    beta1: "0.9"
    beta2: "0.98"
    epsilon: "1e-9"
  citations: required
```

Only these top-level fields are required:

- `schema_version`
- `id`
- `paradigm`
- `paper_pack`
- `messages`
- `expect.outcome`

Everything else in `expect` is optional and used only when it expresses a meaningful observable requirement.

### Conversation Input

`messages` contains the complete history visible to the harness before it produces the next assistant turn. The final message must have role `user`.

This single representation covers both direct questions and follow-ups:

```yaml
messages:
  - role: user
    content: "Recommend papers for a systematic review."
  - role: assistant
    content: "Which direction: comparison, reproduction, or systematic review?"
  - role: user
    content: "The third."
```

Each case evaluates one next assistant turn. This makes history resolution deterministic without requiring a scripted assistant to generate intermediate wording during the test.

### Outcomes

Use a small outcome enum:

- `answered`
- `needs_clarification`
- `abstained`
- `partial`

An unhandled technical failure is always a failed run rather than an expected research outcome.

Clarification and abstention cases may add a stable reason code:

```yaml
expect:
  outcome: needs_clarification
  reason: ambiguous_paper_identity
```

The reason code describes the user-visible decision, not an internal intent-model label.

### Papers And Evidence

`papers.required` identifies sources that must contribute accepted evidence. `papers.forbidden` identifies known distractors that must not be used as support.

`evidence.required` and `evidence.forbidden` contain stable anchor IDs from the paper pack. They do not contain reading-model element IDs, chunk IDs, or parser offsets.

```yaml
expect:
  papers:
    required: [attention_is_all_you_need_2017]
    forbidden: [bert_2018]
  evidence:
    required: [transformer_adam_training_params]
    forbidden: [bert_optimizer_params]
```

### Content Expectations

Use `facts` for values that can be compared deterministically after lightweight normalization:

```yaml
facts:
  beta2: "0.98"
  epsilon: "1e-9"
```

Use `claims` only when a comparison, genealogy, contradiction, or multi-hop answer cannot be represented as key-value facts:

```yaml
claims:
  - text: "BERT uses the Transformer encoder rather than the full encoder-decoder architecture."
    evidence:
      - bert_encoder_architecture
      - transformer_encoder_decoder_architecture
```

Gold claims do not assign runtime claim IDs. The harness remains free to decompose its `ClaimGraph` differently.
Claim coverage is part of the separate semantic or human review result, not deterministic `hard_pass`; required papers, anchors, and grounding remain hard checks.

### Optional Review Rubric

Open-ended qualities such as completeness, organization, and usefulness may be recorded separately:

```yaml
review:
  - "Distinguishes architectural inheritance from training-objective differences."
  - "States the limits of the comparison corpus."
```

Review criteria do not affect deterministic `hard_pass`. They are used for human review or one optional semantic judge and are reported separately.

## Paper Pack V2

A paper pack contains stable research identities and human-verifiable evidence anchors:

```yaml
schema_version: harness-paper-pack/v2
id: transformer_bert_gpt

papers:
  - id: attention_is_all_you_need_2017
    title: "Attention Is All You Need"
    year: 2017
    source_url: "https://arxiv.org/pdf/1706.03762v7"

anchors:
  - id: transformer_adam_training_params
    paper: attention_is_all_you_need_2017
    page: 7
    section: "5.3 Optimizer"
    quote: "We used the Adam optimizer with beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9."
    facts:
      beta1: "0.9"
      beta2: "0.98"
      epsilon: "1e-9"
```

Citation edges remain in the pack when they are research evidence needed by genealogy or multi-hop cases.

The following belong in generated audit output rather than the authored pack:

- Reading-model paths derived by convention
- Parser names and versions
- Match regexes
- Character offsets
- Extraction counters
- Visual-asset linkage statistics
- PDF hashes and parser verification results

The audit verifies that every authored anchor can still be located in the current parsed corpus. An audit failure invalidates the dataset build; it does not require rewriting the case.

## Scoring

Report four independent dimensions:

| Dimension | Hard check |
| --- | --- |
| Outcome | Expected answer, clarification, abstention, or partial result |
| Retrieval | Required and forbidden papers and anchors |
| Content | Required structured facts and deterministic prohibitions |
| Grounding | Required citations and evidence-linked supported claims |

The scorer emits:

```yaml
hard_pass: true
scores:
  outcome: pass
  retrieval: pass
  content: pass
  grounding: pass
review:
  status: not_run
```

There is no weighted aggregate score. A failed hard dimension names the exact missing or forbidden item. Optional semantic review is shown separately and never silently changes `hard_pass`.

The scorer must not grade:

- Intent labels
- Retrieval-plan labels
- Stage names or order
- Number of tool calls
- Runtime claim IDs
- Exact answer prose

## Runtime Traces

The harness may continue producing:

```text
IntentFrame
RetrievalPlan
EvidenceLedger
ClaimGraph
ReasoningArtifact
VerificationPass
ResearchAnswer
```

These artifacts support debugging, inspection, and optional frontend panes. Only externally meaningful invariants cross into golden scoring, such as a supported claim citing accepted evidence. The artifact contract remains a runtime contract, not part of GoldenCase authoring.

## Reliability Rules

- Prefer IDs and structured values over answer-string regexes.
- Normalize harmless numeric and mathematical formatting before fact comparison.
- Never use parser chunk IDs or character offsets as stable golden identifiers.
- Keep required and forbidden sets small and intentional.
- Do not require every available relevant paper; require only sources necessary for correctness.
- A parser audit and a harness evaluation are separate runs with separate failures.
- Preserve raw model output and accepted evidence for diagnosis, but do not encode the diagnosis procedure into the case.

## Migration

1. Implement the v2 loader and four-dimension scorer in Python.
2. Convert the existing nine cases to v2 without adding new cases.
3. Run the nine cases and compare failures with current live-chat observations.
4. Delete the v1 case fields, v1 scorer paths, and manually maintained compatibility projections.
5. Add a small set of history-dependent cases covering ordinal selections, confirmations, and topic refinement.
6. Expand paper packs and paradigms only after the migrated cases are useful for harness decisions.

This is a one-way migration. No dual-schema compatibility layer is required because the exploratory harness and current golden data may be replaced freely.

## Acceptance Criteria

- A simple fact case fits in roughly 15 to 30 lines.
- A complex case normally fits in fewer than 60 lines.
- The nine current cases can express their behavior without `expected_intent`, `expected_retrieval_plan`, `required_trace`, or `compatibility_projection`.
- Reordering or replacing harness stages does not require changing cases.
- Single-turn and multi-turn history snapshots use the same case schema.
- Every hard failure is attributable to outcome, retrieval, content, or grounding.
- Parser-audit failures are distinguishable from harness-evaluation failures.
- Optional semantic or human review is reported separately from deterministic pass/fail.
