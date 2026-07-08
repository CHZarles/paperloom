# Research Harness V0 And Golden Data Plan

Date: 2026-07-08

## Purpose

Refactor the paper retrieval agent harness into an auditable research reasoning system that can handle the question paradigms listed in `research/remake.md`.

The new harness should not be optimized first for fluent final answers. It should be optimized for visible, inspectable research work:

```text
Question
-> IntentFrame
-> RetrievalPlan
-> EvidenceLedger
-> ClaimGraph
-> ReasoningArtifact
-> VerificationPass
-> FinalAnswer
```

The existing harness can be deleted or rewritten aggressively. Compatibility with the current ReAct implementation is not a goal when it conflicts with the new trace contract.

## Product Principle

The product is not a chat bot with citations. It is a research cockpit where users can inspect how an answer was produced.

Every important answer should expose:

- What the system understood the question to mean.
- Which retrieval strategies it chose and why.
- Which papers, sections, tables, figures, formulas, or metadata records it inspected.
- Which atomic claims were extracted.
- Which claims support, refute, or fail to support the final answer.
- Which uncertainties or ambiguities remain.
- Why the system answered, asked for clarification, or abstained.

Unsupported confidence is a failure. Abstention with precise missing-evidence diagnostics is a valid success state.

## Harness V0 Artifacts

### IntentFrame

Structured interpretation of the user question.

Required fields:

- `questionId`
- `rawQuestion`
- `normalizedQuestion`
- `entities`
- `paperMentions`
- `methodMentions`
- `datasetMentions`
- `constraints`
- `answerType`
- `ambiguityStatus`
- `requiredEvidenceTypes`
- `requiredCapabilities`

Initial `answerType` values:

- `exact_fact`
- `definition_trace`
- `comparison_matrix`
- `reproduction_protocol`
- `citation_genealogy`
- `critical_assessment`
- `meta_review`
- `contradiction_resolution`
- `uncertainty_boundary`
- `ambiguity_resolution`
- `constraint_filter`
- `figure_table_formula_interpretation`
- `multi_hop_chain`

### RetrievalPlan

Explicit search strategy selected from the intent frame.

Required fields:

- `planId`
- `targetEntities`
- `strategySteps`
- `expectedEvidenceTypes`
- `requiredRecallTargets`
- `hardNegativePolicy`
- `stopConditions`

Initial retrieval strategies:

- `paper_identity_resolution`
- `semantic_search`
- `lexical_search`
- `metadata_filter`
- `citation_graph_traversal`
- `table_search`
- `figure_caption_search`
- `formula_search`
- `algorithm_search`
- `appendix_search`
- `author_institution_search`
- `negative_evidence_search`
- `version_comparison`

### EvidenceLedger

Immutable evidence record for all retrieved and accepted evidence.

Required fields per item:

- `evidenceId`
- `paperId`
- `title`
- `paperVersion`
- `section`
- `page`
- `location`
- `elementType`
- `spanText`
- `bboxOrCellRef`
- `retrievalStrategy`
- `relevanceScore`
- `evidenceQuality`
- `supportsClaimIds`
- `refutesClaimIds`

Initial `elementType` values:

- `paragraph`
- `table`
- `figure`
- `formula`
- `algorithm`
- `appendix`
- `metadata`
- `citation_edge`

### ClaimGraph

Atomic, verifiable claims extracted from evidence and used by the final answer.

Required fields per claim:

- `claimId`
- `text`
- `claimType`
- `supportingEvidenceIds`
- `refutingEvidenceIds`
- `status`
- `confidence`
- `dependsOnClaimIds`

Initial claim statuses:

- `supported`
- `contradicted`
- `underdetermined`
- `ambiguous`

Every substantive final-answer sentence must map to one or more claims, or the verification pass must flag it.

### ReasoningArtifact

Question-specific synthesis product.

Initial artifact types:

- `comparison_table`
- `timeline`
- `citation_graph`
- `reproduction_checklist`
- `conflict_matrix`
- `constraint_filter_table`
- `uncertainty_boundary_report`
- `definition_evolution_trace`
- `multi_hop_chain`

The frontend should render this artifact directly instead of hiding it inside prose.

### VerificationPass

Pre-answer validation report.

Required checks:

- Every required capability was attempted.
- Required evidence types were retrieved or explicitly missing.
- All final-answer claims are supported, contradicted, or marked underdetermined.
- Paper identity and version are explicit when relevant.
- Constraints were satisfied.
- Contradictory evidence was surfaced when present.
- Ambiguity was resolved, exposed, or routed to clarification.
- Abstention was used when evidence was insufficient.

## Golden Data Strategy

Build evidence-first golden data. Do not begin with a large undifferentiated paper corpus.

Each golden case should specify:

- What capability is being tested.
- Which papers are required.
- Which distractors should not be used.
- Which evidence spans, table cells, figures, formulas, metadata records, or citation edges matter.
- Which atomic claims must be produced.
- Which reasoning artifact is expected.
- Which failure modes should be detected.

### Golden Case Schema

```yaml
id:
question:
paradigm_tags:
required_capabilities:
corpus_scope:
  required_papers:
  optional_papers:
  distractor_papers:
answer_contract:
  type:
  required_fields:
gold_answer:
gold_evidence:
  - paper_id:
    section:
    page:
    element_type:
    required_span:
    cell_ref:
    bbox:
gold_claims:
  - claim_id:
    text:
    status:
    required_evidence:
expected_reasoning_artifact:
  type:
  required_axes:
  required_nodes:
  required_edges:
required_trace:
  intent_must_include:
  retrieval_must_include:
  verification_must_include:
failure_modes:
```

The schema should be sparse in practice: cases only fill fields relevant to their capability.

## Seed Benchmark

Start with 60 cases:

- 44 cases: two cases for each of the 22 paradigms in `research/remake.md`.
- 16 stress cases:
  - 4 ambiguity cases.
  - 4 contradiction cases.
  - 4 no-answer or boundary cases.
  - 4 multi-hop synthesis cases.

This is the first harness-shape gate. It is large enough to reveal architectural errors and small enough to curate carefully.

## Paper Packs

Collect papers as capability packs, not as a paper ocean.

Initial packs:

1. Transformer / BERT / GPT pack.
2. Vision architecture pack.
3. Generative models pack.
4. Representation learning pack.
5. Reinforcement learning and planning pack.
6. Efficient adaptation and compression pack.
7. Dataset and benchmark provenance pack.
8. Theory and fairness pack.
9. Failure, contradiction, and negative-results pack.
10. Ambiguity and knowledge-boundary pack.

Target V0 size: 60-100 papers.

Every paper must serve at least one golden case as a target, predecessor, successor, contradiction source, version variant, or hard distractor.

## Scoring

Score the harness in four layers.

### Retrieval Quality

- Required paper recall.
- Evidence recall at K.
- Hard-negative avoidance.
- Citation-edge recall.
- Table, figure, formula, and algorithm hit accuracy.

### Claim Quality

- Supported-claim precision.
- Supported-claim recall.
- Unsupported-claim count.
- Attribution correctness.
- Contradiction detection.
- Abstention correctness.

### Reasoning Quality

- Comparison matrix completeness.
- Timeline order correctness.
- Citation graph correctness.
- Constraint satisfaction.
- Conflict-resolution correctness.
- Uncertainty calibration.

### Trace And Frontend Quality

- Final claims can be inspected.
- Claims jump to source evidence.
- Retrieval strategy choices are visible.
- Discarded or ambiguous candidates are visible when relevant.
- Missing evidence is explained.
- The user can verify the answer without trusting the model blindly.

## Implementation Sequence

1. Write the artifact contracts for `IntentFrame`, `RetrievalPlan`, `EvidenceLedger`, `ClaimGraph`, `ReasoningArtifact`, `VerificationPass`, and `ResearchAnswer`.
2. Write the golden case schema and validate a few hand-authored examples.
3. Select the first 60 benchmark questions from `research/remake.md`.
4. Create the first paper-pack manifest with required papers and distractors.
5. Implement a trace-first harness pipeline that emits the artifact sequence even before retrieval is sophisticated.
6. Replace the current product harness path with the new research harness path.
7. Add an eval runner that scores trace obligations before final-answer quality.
8. Add frontend views for the artifact sequence:
   - intent view
   - retrieval plan view
   - evidence ledger view
   - claim graph view
   - reasoning artifact view
   - verification view
   - final answer view
9. Improve retrieval strategies only after trace obligations are measurable.

## Non-Goals

- No compatibility-preserving migration of the old harness.
- No generic fallback answer path.
- No answer-only benchmark as the primary gate.
- No large paper crawl before the golden schema exists.
- No hidden reasoning claims that cannot be linked to evidence.
- No silent downgrade from precise evidence retrieval to vague semantic similarity.

## Immediate Next Step

Create the first version of the golden case schema and hand-author 6 cases:

1. One exact fact case.
2. One comparison matrix case.
3. One citation genealogy case.
4. One contradiction-resolution case.
5. One ambiguity-resolution case.
6. One no-answer boundary case.

These six cases should be used to test whether the proposed artifacts are expressive enough before scaling to the 60-case seed benchmark.
