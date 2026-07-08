# Agent Harness and Golden Data Strategy

This note records the proposed strategy for refactoring the paper RAG agent harness described in `research/remake.md`.

## North Star

The harness should not be a generic chat loop with citations. It should be an auditable research reasoning instrument.

The product should expose the full path from user question to final answer:

```text
question
-> intent frame
-> retrieval plan
-> evidence ledger
-> atomic claims
-> reasoning artifact
-> verification result
-> final answer
```

The benchmark should therefore evaluate the trace, not only the final prose answer.

## Core Recommendation

Do not start by collecting a large number of papers. Start by collecting paper situations: small, deliberately constructed corpora where each question forces a capability the harness must expose.

The 250 questions in `remake.md` should be treated as capability coverage, not as a flat QA set. They span exact fact extraction, definition tracing, comparison, reproduction, critical assessment, citation genealogy, uncertainty handling, ambiguity resolution, contradiction arbitration, structured table/formula queries, and complex constraint filtering.

The golden data must punish fluent unsupported answers and reward inspectable reasoning.

## Harness Artifacts

### 1. IntentFrame

The harness should first produce a typed interpretation of the user request.

Suggested fields:

```text
entities
paper_names
method_names
constraints
answer_type
ambiguity_status
required_evidence_type
```

Suggested answer types:

```text
exact_fact
definition_trace
comparison_matrix
reproduction_protocol
citation_genealogy
contradiction_resolution
uncertainty_boundary
constraint_filter
figure_table_formula_interpretation
```

### 2. RetrievalPlan

The harness should explicitly choose retrieval strategies before fetching evidence.

Candidate strategies:

```text
semantic_search
lexical_search
metadata_filter
citation_graph_traversal
table_search
figure_caption_search
formula_search
appendix_search
author_institution_search
negative_evidence_search
```

The frontend should show not only what was retrieved, but why each retrieval route was chosen.

### 3. EvidenceLedger

The evidence ledger should be immutable and source-grounded.

Each evidence item should record:

```text
paper_id
title
section
page
element_type: paragraph | table | figure | formula | algorithm | appendix | metadata
span_text
bbox_or_cell_ref
retrieval_strategy
relevance_score
confidence_label
supports_claim_ids
refutes_claim_ids
```

The current code already has related concepts such as `EvidenceLedger`, `EvidenceItem`, and `ProductReadingTraceRecorder`. The refactor should deepen this direction rather than replace it with a looser abstraction.

### 4. ClaimGraph

Final answers should be reducible to atomic claims.

Example:

```text
claim_id: c17
text: "Transformer base uses Adam with beta1=0.9, beta2=0.98, epsilon=1e-9."
support: [evidence_003, evidence_004]
status: supported | contradicted | underdetermined
confidence: high
```

The final answer should not contain important unsupported claims.

### 5. ReasoningArtifact

Different question types need different reasoning products:

```text
comparison table
timeline
citation graph
reproduction checklist
conflict matrix
constraint filter table
uncertainty boundary note
```

The reasoning artifact is what lets the user inspect how evidence became an answer.

### 6. VerificationPass

Before answer generation, the harness should verify:

```text
Are all claims supported?
Are there unsupported answer sentences?
Are there known contradictions?
Did we retrieve from the right paper/version?
Did the answer satisfy constraints?
Is abstention required?
```

"I do not know" should not be a fallback. It should be a first-class, verified output: insufficient evidence, with a clear missing-evidence explanation.

## Golden Data Strategy

Build paper packs instead of a single giant corpus.

Each pack should contain target papers, predecessor papers, successor papers, hard distractors, and, where useful, contradictory papers.

Suggested starter packs:

```text
Transformer / BERT / GPT Pack
Vision Architecture Pack
Generative Models Pack
Representation Learning Pack
RL / Planning Pack
Efficient Adaptation Pack
Benchmark / Dataset Pack
Theory Pack
Contradiction / Failure Pack
Ambiguity / Boundary Pack
```

Start with roughly 60-100 papers. Each paper should earn its place by supporting multiple benchmark cases.

## Golden Case Schema

Golden data should be evidence-first, not answer-first.

Example for an exact fact case:

```yaml
id: transformer_adam_params_001
paradigm:
  - precision_fact_extraction
  - methodology_reproduction
question: "Transformer original paper used Adam with what beta1, beta2, and epsilon?"
corpus_scope:
  required_papers:
    - attention_is_all_you_need_2017
  distractor_papers:
    - bert_2018
    - gpt3_2020
answer_contract:
  type: exact_fact
  required_fields:
    - beta1
    - beta2
    - epsilon
gold_answer:
  beta1: "0.9"
  beta2: "0.98"
  epsilon: "1e-9"
gold_evidence:
  - paper_id: attention_is_all_you_need_2017
    section: training
    page: 7
    element_type: paragraph
    required_span: "Adam optimizer with beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9"
required_trace:
  intent_must_include:
    - optimizer_hyperparameter_lookup
  retrieval_must_include:
    - paper_identity_resolution
    - section_or_text_search
  claims_must_include:
    - adam_beta1
    - adam_beta2
    - adam_epsilon
failure_modes:
  - cites BERT instead of Transformer
  - gives default Adam beta2=0.999
  - answers without source span
```

For synthesis questions, do not require exact wording. Require structure.

Example:

```yaml
answer_contract:
  type: comparison_matrix
  required_axes:
    - objective
    - architecture
    - training_signal
    - inference_cost
    - known_limitations
minimum_supported_claims: 8
forbidden_behavior:
  - unsupported broad claims
  - no evidence for comparison cells
```

## Scoring Layers

Score four layers separately.

### 1. Retrieval Quality

```text
evidence_recall@k
required_paper_recall
hard_negative_avoidance
citation_graph_edge_recall
table/figure/formula hit accuracy
```

### 2. Claim Quality

```text
supported_claim_precision
supported_claim_recall
unsupported_claim_count
contradiction_detection
attribution correctness
```

### 3. Reasoning Quality

```text
comparison matrix completeness
timeline order correctness
constraint satisfaction
conflict-resolution correctness
uncertainty calibration
```

### 4. Frontend / Harness Trace Quality

```text
can user inspect every final claim?
can user jump from claim to evidence?
are discarded candidates visible?
are ambiguity decisions visible?
did the system expose uncertainty instead of hiding it?
```

This last layer is essential because the target product is an auditable reasoning cockpit, not just a RAG endpoint.

## First Benchmark Slice

Do not begin with all 250 questions.

Start with:

```text
2 cases per paradigm x 22 paradigms = 44 cases
```

Then add 16 stress cases:

```text
4 contradiction cases
4 ambiguity cases
4 no-answer / boundary cases
4 multi-hop synthesis cases
```

This gives a first benchmark of 60 cases. That is enough to reveal whether the harness architecture is right before scaling the dataset.

## Design Principle

Treat the 22 paradigms as capability tags, not dataset folders.

One hard question can and should exercise multiple tags. This prevents the benchmark from becoming a trivia zoo.

For every question, define trace obligations:

```text
Must resolve paper identity.
Must retrieve the exact table.
Must compare two experimental settings.
Must detect ambiguity.
Must refuse unsupported certainty.
Must include a citation graph path.
Must surface contradiction.
Must ask the user to choose between interpretations when needed.
```

The harness should be built around these obligations. The final answer is only one artifact among several; the real product is the visible, verifiable path from question to evidence-backed conclusion.
