# Harness Golden Data Schema

Schema version: `harness-golden-data/v1`

This schema turns the strategy in `research/harness-golden-data-strategy.md` into concrete data
objects for paper-pack construction, golden case authoring, trace evaluation, and run scoring.

The schema is evidence-first. A case is not just a question and a final answer; it is a contract for
what the harness must visibly understand, retrieve, claim, reason over, verify, and expose.

## Resolved Design Decisions

These decisions use the recommended defaults from the strategy note.

1. **Golden Case is the canonical benchmark unit.**
   Existing flat RAG cases can be derived from it, but the richer object is the source of truth.
2. **Paper Packs are curated situations, not topic folders.**
   A pack contains target papers plus predecessors, successors, hard distractors, contradictory
   papers, and boundary cases.
3. **Evidence Anchors outrank answer text.**
   Gold data records exact spans, table cells, figures, formulas, algorithms, or metadata facts that
   support or refute claims.
4. **Answer Contracts replace canonical prose.**
   For synthesis questions, the schema requires structure, supported claims, and forbidden behavior
   rather than one exact wording.
5. **Trace Obligations are first-class scoring targets.**
   The benchmark must score whether the harness exposed the right intent, retrieval plan, evidence,
   claim graph, reasoning artifact, and verification pass.
6. **Abstention and clarification are expected results, not fallback behavior.**
   No-answer, ambiguity, and contradiction cases must explain the missing or conflicting evidence.
7. **Current eval JSONL is a compatibility projection.**
   `RagBenchmarkCase`-style fields such as `requiredEvidenceRegex` are useful smoke checks, but they
   are not precise enough to be the canonical schema.

## Recommended File Layout

Keep authoring files under `research/golden-data/` when the dataset is created.

```text
research/
  harness-golden-data-schema.md
  harness-golden-data-strategy.md
  golden-data/
    manifest.yaml
    paper-packs/
      transformer-bert-gpt.yaml
      vision-architecture.yaml
    cases/
      seed-60.yaml
      stress-contradiction.yaml
    run-traces/
      README.md
```

The first runnable smoke dataset, Seed-60 selection file, artifact contract file, and starter
paper-pack manifest now live under `research/golden-data/`.

## Shared Enumerations

### Capability Tag

Use these as tags, not folders. One case may carry several tags.

```yaml
capability_tags:
  - precision_fact_extraction
  - concept_tracing_definition
  - deep_comparison
  - methodology_reproduction
  - contribution_critical_assessment
  - association_influence_genealogy
  - context_specific_brainstorming
  - data_benchmark_provenance
  - hypothetical_discussion
  - multimodal_cross_domain_system_design
  - theory_math_formal_proof
  - author_institution_research_style
  - dataset_contamination_generalization
  - meta_analysis_systematic_review
  - boundary_failure_counterexample
  - future_prediction_open_problem
  - complex_multihop_reasoning
  - uncertainty_knowledge_boundary
  - ambiguity_resolution
  - contradiction_resolution
  - structured_info_nontraditional_query
  - complex_constraint_combination
```

### Answer Type

```yaml
answer_type:
  enum:
    - exact_fact
    - definition_trace
    - comparison_matrix
    - reproduction_protocol
    - contribution_assessment
    - citation_genealogy
    - recommendation_with_constraints
    - data_provenance
    - hypothetical_inference
    - system_design
    - formal_explanation
    - research_style_profile
    - contamination_or_generalization_analysis
    - mini_review
    - failure_boundary_analysis
    - future_trend_assessment
    - multihop_chain
    - uncertainty_boundary
    - ambiguity_clarification
    - contradiction_arbitration
    - structured_element_interpretation
    - constraint_filter_result
```

### Evidence Element Type

```yaml
element_type:
  enum:
    - title
    - abstract
    - paragraph
    - section_heading
    - table
    - table_cell
    - figure
    - figure_caption
    - formula
    - algorithm
    - appendix
    - reference
    - metadata
    - code_link
    - dataset_card
    - benchmark_leaderboard
```

### Paper Role

```yaml
paper_role:
  enum:
    - target
    - predecessor
    - successor
    - survey
    - hard_distractor
    - contradiction
    - negative_result
    - boundary
    - metadata_only
```

### Expected Result Kind

```yaml
expected_result_kind:
  enum:
    - answered
    - needs_clarification
    - abstain_insufficient_evidence
    - contradiction_report
    - partial_with_explicit_limits
```

## DatasetManifest

Top-level manifest for a benchmark slice.

```yaml
schema_version: harness-golden-data/v1
dataset_id: seed_60
title: "Seed 60 Harness Golden Cases"
description: "Two cases per capability paradigm plus stress cases."
created_at: "2026-07-08"
owners:
  - name: "PaperLoom"
source_strategy_doc: "research/harness-golden-data-strategy.md"
splits:
  - id: seed
    purpose: "Architecture validation"
  - id: stress
    purpose: "Contradiction, ambiguity, no-answer, and multi-hop stress"
paper_packs:
  - id: transformer_bert_gpt
    path: "paper-packs/transformer-bert-gpt.yaml"
case_files:
  - path: "cases/seed-60.yaml"
scoring_profile: trace_obligation_v1
compatibility_exports:
  - format: rag_benchmark_case_jsonl
    path: "../../eval/rag/generated/harness-seed-60.jsonl"
```

Required fields: `schema_version`, `dataset_id`, `splits`, `paper_packs`, `case_files`,
`scoring_profile`.

## PaperPack

A curated paper situation used by one or more Golden Cases.

```yaml
id: transformer_bert_gpt
title: "Transformer / BERT / GPT Pack"
purpose: "Test identity resolution, pretraining comparisons, exact hyperparameters, and lineage."
capability_tags:
  - precision_fact_extraction
  - deep_comparison
  - association_influence_genealogy
  - complex_multihop_reasoning
papers:
  - paper_id: attention_is_all_you_need_2017
    role: target
  - paper_id: bert_2018
    role: successor
  - paper_id: gpt3_2020
    role: successor
  - paper_id: transformer_xl_2019
    role: hard_distractor
citation_edges:
  - from_paper_id: bert_2018
    to_paper_id: attention_is_all_you_need_2017
    edge_type: cites
    evidence_anchor_id: bert_refs_transformer
known_traps:
  - "Default Adam beta2=0.999 is a distractor for Transformer training hyperparameters."
  - "BERT and GPT use Transformer blocks differently; do not collapse encoder and decoder usage."
```

Required fields: `id`, `title`, `purpose`, `papers`.

## PaperRecord

Canonical identity and source assets for a paper.

```yaml
paper_id: attention_is_all_you_need_2017
identity:
  title: "Attention Is All You Need"
  authors:
    - "Ashish Vaswani"
    - "Noam Shazeer"
  year: 2017
  venue: "NeurIPS"
  doi: null
  arxiv_id: "1706.03762"
  version_label: "arXiv v7 or camera-ready"
source_assets:
  pdf_path: "papers/attention-is-all-you-need.pdf"
  structured_text_path: "parsed/attention-is-all-you-need.json"
  page_images_dir: "page-images/attention-is-all-you-need/"
  parser: "paperloom-reading-model"
ingest_expectations:
  requires_pdf_visual_assets: true
  requires_tables: true
  requires_figures: true
  requires_formulas: true
metadata_quality:
  identity_status: verified
  notes: []
```

Required fields: `paper_id`, `identity.title`, `identity.year`.

Use `null` only in examples. In real authored YAML, omit unknown optional fields instead of writing
`null`.

## EvidenceAnchor

Gold reference to the smallest inspectable source unit that supports or refutes a claim.

```yaml
anchor_id: transformer_adam_training_params_span
paper_id: attention_is_all_you_need_2017
role: supports
element:
  type: paragraph
  section: "Training"
  page: 7
  location_hint: "Optimizer paragraph"
  bbox: null
selector:
  exact_text: "We used the Adam optimizer with beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9."
  regex: "Adam optimizer.*beta_?1\\s*=\\s*0\\.9.*beta_?2\\s*=\\s*0\\.98.*epsilon\\s*=\\s*10\\^-9"
normalized_facts:
  adam_beta1: "0.9"
  adam_beta2: "0.98"
  adam_epsilon: "1e-9"
asset_requirement:
  text: required
  pdf_page: required
  table_cell: not_applicable
  figure: not_applicable
failure_if_missing:
  - "The answer may substitute default Adam values."
  - "The answer may cite a later implementation instead of the original paper."
```

Required fields: `anchor_id`, `paper_id`, `role`, `element.type`, and at least one selector.

`role` values:

```yaml
role:
  enum:
    - supports
    - refutes
    - qualifies
    - background
    - hard_negative
    - missing_expected
```

## GoldenCase

Canonical benchmark item. This is the main authoring object.

```yaml
id: transformer_adam_params_001
schema_version: harness-golden-data/v1
split: seed
question:
  language: en
  text: "Transformer original paper used Adam with what beta1, beta2, and epsilon?"
capability_tags:
  - precision_fact_extraction
  - methodology_reproduction
difficulty: easy
paper_pack_ids:
  - transformer_bert_gpt
corpus_scope:
  retrieval_corpus: EVAL_HARNESS_SEED
  required_paper_ids:
    - attention_is_all_you_need_2017
  allowed_paper_ids:
    - attention_is_all_you_need_2017
    - bert_2018
    - gpt3_2020
  hard_negative_paper_ids:
    - bert_2018
    - gpt3_2020
expected_result:
  kind: answered
  answer_type: exact_fact
expected_intent:
  entities:
    - "Transformer"
    - "Adam"
  paper_identity_hints:
    - title: "Attention Is All You Need"
      year: 2017
  constraints:
    - "Use the original Transformer paper, not later implementations."
  ambiguity_status: unambiguous
  required_evidence_types:
    - paragraph
expected_retrieval_plan:
  required_strategies:
    - paper_identity_resolution
    - lexical_search
    - section_search
  forbidden_strategies:
    - hidden_llm_rerank_without_trace
gold_evidence:
  required_anchor_ids:
    - transformer_adam_training_params_span
  optional_anchor_ids: []
  forbidden_anchor_ids:
    - bert_optimizer_defaults_span
gold_claims:
  - claim_id: adam_beta1
    required: true
    canonical_text: "The Transformer paper used Adam beta1 = 0.9."
    expected_status: supported
    support_anchor_ids:
      - transformer_adam_training_params_span
    exact_value: "0.9"
  - claim_id: adam_beta2
    required: true
    canonical_text: "The Transformer paper used Adam beta2 = 0.98."
    expected_status: supported
    support_anchor_ids:
      - transformer_adam_training_params_span
    exact_value: "0.98"
  - claim_id: adam_epsilon
    required: true
    canonical_text: "The Transformer paper used Adam epsilon = 1e-9."
    expected_status: supported
    support_anchor_ids:
      - transformer_adam_training_params_span
    exact_value: "1e-9"
answer_contract:
  type: exact_fact
  required_fields:
    beta1: "0.9"
    beta2: "0.98"
    epsilon: "1e-9"
  citation_policy:
    every_required_field_must_cite_anchor: true
required_trace:
  obligations:
    - phase: intent
      must_include:
        - "paper identity: Attention Is All You Need"
        - "optimizer hyperparameter lookup"
    - phase: retrieval
      must_include:
        - "retrieval plan includes original-paper identity resolution"
    - phase: evidence
      must_include_anchor_ids:
        - transformer_adam_training_params_span
    - phase: verification
      must_include:
        - "reject default Adam beta2=0.999 unless supported by target paper"
failure_modes:
  - id: wrong_paper
    description: "Cites BERT, GPT, or a framework default instead of the Transformer paper."
  - id: default_adam_values
    description: "Gives beta2=0.999."
  - id: unsupported_answer
    description: "Answers without a source span."
compatibility_projection:
  taskType: METHODOLOGY_REPRODUCTION
  expectedRoute: MANUAL_SOURCE_QA
  requiredEvidenceRegex:
    - "Adam optimizer.*0\\.9.*0\\.98.*10\\^-9|Adam optimizer.*0\\.9.*0\\.98.*1e-9"
  requiredAnswerRegex:
    - "0\\.9"
    - "0\\.98"
    - "1e-?9|10\\^-9"
  expectedPaperIds:
    - attention_is_all_you_need_2017
  requiresCitation: true
```

Required fields: `id`, `schema_version`, `question`, `capability_tags`, `corpus_scope`,
`expected_result`, `expected_intent`, `gold_evidence`, `answer_contract`, `required_trace`.

## AnswerContract

`answer_contract.type` chooses the validation shape. The contract should validate structure and
support, not prose style.

### Exact Fact

```yaml
answer_contract:
  type: exact_fact
  required_fields:
    hidden_size: "768"
    max_sequence_length: "512"
  citation_policy:
    every_required_field_must_cite_anchor: true
```

### Comparison Matrix

```yaml
answer_contract:
  type: comparison_matrix
  compared_entities:
    - BERT
    - GPT
  required_axes:
    - objective
    - architecture_role
    - training_signal
    - downstream_strengths
    - limitations
  minimum_supported_claims: 8
  citation_policy:
    every_matrix_cell_must_have_support: true
```

### Citation Genealogy

```yaml
answer_contract:
  type: citation_genealogy
  required_nodes:
    - attention_is_all_you_need_2017
    - bert_2018
    - roberta_2019
    - xlnet_2019
  required_edges:
    - from: bert_2018
      to: attention_is_all_you_need_2017
      relation: cites_or_builds_on
  output_shape: directed_graph_plus_short_explanation
```

### Contradiction Arbitration

```yaml
answer_contract:
  type: contradiction_arbitration
  required_conflict_axes:
    - claim
    - paper_a_evidence
    - paper_b_evidence
    - experimental_setting_difference
    - current_best_resolution
  must_not_hide_conflict: true
```

### Uncertainty Boundary

```yaml
answer_contract:
  type: uncertainty_boundary
  required_sections:
    - what_is_known
    - what_is_not_supported
    - missing_evidence
    - safe_next_search
  must_abstain_from:
    - unsupported_architecture_claims
```

### Constraint Filter Result

```yaml
answer_contract:
  type: constraint_filter_result
  required_constraints:
    - "published_after: 2023"
    - "gpu_count < 4"
    - "BLEU > 30"
    - "code_available = true"
  each_result_must_explain:
    - matched_constraint_evidence
    - missing_or_weak_constraint_evidence
  allow_empty_result: true
```

## Trace Obligation

Trace obligations are the bridge between golden data and frontend/harness inspection.

```yaml
required_trace:
  obligations:
    - id: resolve_identity
      phase: intent
      severity: critical
      must_include:
        - "paper title"
        - "paper year"
      scoring:
        points: 2
    - id: retrieve_exact_table
      phase: retrieval
      severity: critical
      must_include_strategy:
        - table_search
      must_include_anchor_ids:
        - coco_table_2_result_cell
      scoring:
        points: 4
    - id: expose_discarded_candidates
      phase: frontend_trace
      severity: important
      must_include:
        - "hard distractor papers shown as rejected or not selected"
      scoring:
        points: 1
```

Allowed phases:

```yaml
phase:
  enum:
    - intent
    - retrieval
    - evidence
    - claim_graph
    - reasoning
    - verification
    - final_answer
    - frontend_trace
```

## Expected Run Trace

The harness should emit a run trace that can be scored against a Golden Case.

```yaml
run_trace:
  schema_version: harness-run-trace/v1
  case_id: transformer_adam_params_001
  harness_id: product_reading_research_harness
  started_at: "2026-07-08T12:00:00Z"
  completed_at: "2026-07-08T12:00:20Z"
  result_status: completed
  intent_frame:
    answer_type: exact_fact
    entities: []
    constraints: []
    ambiguity_status: unambiguous
    required_evidence_types: []
  retrieval_plan:
    strategies: []
    rejected_strategies: []
    planned_queries: []
  evidence_ledger:
    items: []
    rejected_items: []
    missing_evidence: []
  claim_graph:
    claims: []
    edges: []
  reasoning_artifacts:
    - type: field_table
      payload: {}
  verification_pass:
    unsupported_claim_count: 0
    contradicted_claim_count: 0
    missing_required_anchor_ids: []
    satisfied_trace_obligation_ids: []
    failed_trace_obligation_ids: []
    abstention_required: false
  final_answer:
    markdown: ""
    cited_anchor_ids: []
  diagnostics:
    token_count: 0
    latency_ms: 0
```

`evidence_ledger.items` should preserve enough location detail to support user inspection:

```yaml
evidence_item:
  evidence_id: run_evidence_003
  matched_anchor_id: transformer_adam_training_params_span
  paper_id: attention_is_all_you_need_2017
  title: "Attention Is All You Need"
  section: "Training"
  page: 7
  element_type: paragraph
  span_text: "..."
  bbox_or_cell_ref: null
  retrieval_strategy: lexical_search
  relevance_score: 0.94
  confidence_label: high
  supports_claim_ids:
    - adam_beta1
  refutes_claim_ids: []
```

## Scorecard

Score retrieval, claims, reasoning, and trace separately.

```yaml
case_score:
  case_id: transformer_adam_params_001
  passed: true
  layer_scores:
    retrieval:
      required_paper_recall: 1.0
      evidence_anchor_recall_at_k: 1.0
      hard_negative_avoidance: 1.0
    claim:
      supported_claim_precision: 1.0
      supported_claim_recall: 1.0
      unsupported_claim_count: 0
      attribution_correctness: 1.0
    reasoning:
      contract_completeness: 1.0
      constraint_satisfaction: 1.0
      contradiction_detection: null
      uncertainty_calibration: null
    trace:
      intent_obligation_pass_rate: 1.0
      retrieval_obligation_pass_rate: 1.0
      evidence_obligation_pass_rate: 1.0
      frontend_trace_obligation_pass_rate: 1.0
  failures: []
```

Layer scores may be `null` only when the metric does not apply to the case. A missing applicable
metric is a scoring error, not a pass.

## Compatibility Projection to Current Eval

The current `RagBenchmarkCase` schema can be derived from a Golden Case:

```yaml
RagBenchmarkCase:
  id: GoldenCase.id
  query: GoldenCase.question.text
  language: GoldenCase.question.language
  taskType: GoldenCase.compatibility_projection.taskType
  scopeMode: "MANUAL_SOURCE" if corpus_scope.required_paper_ids else "AUTO_SOURCE"
  scope.paperIds: corpus_scope.required_paper_ids
  expectedRoute: compatibility_projection.expectedRoute
  requiredAnswerRegex: compatibility_projection.requiredAnswerRegex
  requiredEvidenceRegex: compatibility_projection.requiredEvidenceRegex
  forbiddenAnswerRegex: compatibility_projection.forbiddenAnswerRegex
  forbiddenEvidenceRegex: compatibility_projection.forbiddenEvidenceRegex
  expectedPaperIds: compatibility_projection.expectedPaperIds
  requiresCitation: compatibility_projection.requiresCitation
```

This projection is useful for smoke tests, but a pass on the projection does not prove the full
Golden Case passed. Full success requires trace-obligation and evidence-anchor scoring.

## Authoring Rules

1. Every Golden Case must have at least one `capability_tag` and one `answer_contract`.
2. Every answered case must have at least one required `EvidenceAnchor`.
3. Every required claim must link to support, refutation, or an explicit missing-evidence reason.
4. Ambiguous cases must define the acceptable clarification options and must not allow hidden
   same-turn paper selection.
5. No-answer cases must define what evidence was searched for and why the boundary is real.
6. Contradiction cases must include both sides of the conflict and the experimental or definitional
   axis that explains the disagreement.
7. Structured-element cases must anchor to the table cell, figure, formula, algorithm step, or
   appendix span being tested.
8. Complex-constraint cases must score each constraint independently.
9. Final-answer prose is never enough to pass a case; the trace must satisfy the obligations.
10. If a required field cannot be authored precisely, the case is not ready for the golden set.

## Minimum Seed-60 Coverage

The first benchmark slice should contain:

```yaml
seed_60_coverage:
  paradigm_cases:
    count: 44
    rule: "2 cases per capability tag from remake.md"
  stress_cases:
    contradiction: 4
    ambiguity: 4
    no_answer_or_boundary: 4
    multihop_synthesis: 4
```

Each paper in the first 60-case slice should support multiple cases. Avoid adding a paper that only
exists for a single easy fact lookup unless it is a deliberate hard negative.
