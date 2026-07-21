# Golden Data Architecture and Retrieval Evolution Review

Date: 2026-07-16

Concrete follow-up: the answer-scoring design in
[`Golden Claim-Evidence Scoring v4 Proposal`](golden-claim-evidence-scoring-v4-proposal-2026-07-20.md)
supersedes this review's suggested schema direction where the two differ. The broader corpus,
retrieval-experiment, and observation-plane analysis in this document remains applicable.

## Executive Answer

PaperLoom's Golden Data is already more than a question-answer file. It is an evidence-first
evaluation package made of paper identities, frozen source assets, canonical Reading Model exports,
authored evidence anchors, conversational cases, saved Harness runs, deterministic scores, and human
adjudication. That shape is suitable for testing an agentic research Harness because it can observe the
whole Candidate -> Read -> Cited -> Outcome path instead of comparing one generated sentence with one
reference answer.

The semantic task contracts and canonical paper evidence now run through the Java/Qdrant product path.
The existing Qdrant benchmark proves that the same 14-paper corpus, 69 saved searches, and 48 evidence
obligations can compare several retrieval methods. `agent-run` no longer exposes an in-memory corpus
backend or compatibility fallback.

The recommended response is not to rebuild the Golden Data from scratch. Preserve the papers, cases,
facts, claims, outcomes, Reading Models, and human labels. Migrate the schema so that a case requires a
semantic evidence obligation, while exact page-and-text anchors become versioned locators for that
obligation. Keep retrieval queries, candidate budgets, ranking outputs, and method configuration in a
separate experiment layer. Then run the same task set through a matrix of retrieval adapters and report
exact-location recall, equivalent-evidence recall, Read, Cited, answer quality, latency, cost, and
technical failures separately.

## 1. Current Golden Data Shape

The current authoring dependency graph is:

```text
manifest
  -> paper packs
       -> paper identities and source assets
       -> citation edges
       -> authored evidence anchors
       -> canonical Reading Model exports
  -> case files
       -> conversation messages
       -> outcome, paper, evidence, fact, claim, and citation obligations

case + runtime + corpus adapter
  -> Harness Run
       -> candidates and tool trace
       -> Evidence Ledger
       -> Research Answer
  -> deterministic score report
  -> optional human labels and calibrated judge analysis
```

### Current Dataset Boundaries

| Dataset | Papers | Cases | Authored anchors | Purpose |
| --- | ---: | ---: | ---: | --- |
| `manifest.yaml` | 5 | 15 | 7 | Stable regression set |
| `manifest-expanded.yaml` | 14 | 30 | 29 | Broader research and retrieval experiments |

The manifests deliberately use different `dataset_id` values. The stable set is not overwritten when
the expanded set changes. This is a sound boundary because exploratory cases cannot silently redefine
an established baseline.

### Authoring Units

`manifest*.yaml` is only an index. It names the Paper Packs and Case files included in a dataset.

`paper-packs/*.yaml` owns paper identity and evidence source truth:

- stable paper IDs, title, selected author metadata, year, venue, source URL, and version label;
- the role of each paper in the pack, such as target, predecessor, foundation, or distractor;
- citation or technical-lineage edges between papers;
- Anchors with a paper ID, positive page number, element type, section, exact quote selector, and
  normalized facts;
- a `data_dir` from which the loader resolves the paper's Reading Model export.

`cases/*.yaml` owns task truth rather than one ideal prose answer:

- single-turn or multi-turn conversation messages;
- a stable case ID, split, paradigm, and Paper Pack;
- expected outcome: answered, needs clarification, abstained, or partial;
- required and forbidden papers;
- required and forbidden evidence anchors;
- exact structured facts when available;
- claim obligations linked to evidence;
- citation policy and additional human-review criteria.

This is the correct general shape for a research agent. Several valid answers may satisfy the same
obligations, so the dataset does not require literal answer-string equality.

### Corpus Assets

The authored YAML is backed by real paper material:

```text
PDF
-> MinerU parser artifacts
-> PaperReadingModelBuilder
-> paperloom-reading-model-export/v1 JSON
-> optional text and visual-asset projections
```

All 14 current Golden papers have `paperloom-reading-model-export/v1` files. Each export contains
source PDF identity and SHA-256, parser identity, model version, diagnostics, physical pages, sections,
formal locations, typed Reading Elements, and retained parsed tables, figures, and formulas. The
Harness loads these exports and normalizes them into `GoldenDataset.paper_records_by_id`,
`anchors_by_id`, and `reading_models_by_paper_id`.

### Run And Review Artifacts

A live or fixture run produces a `HarnessRun`, `EvidenceLedger`, `ResearchAnswer`, and `ScoreReport`.
The live evaluator can additionally preserve raw transport and tool events. Human labels are stored
separately and are bound to saved runs rather than injected into the model context. This separation is
important: an evaluation policy can be changed and old runs rescored without paying for another model
call, provided the original observations were retained.

## 2. What Golden Data Tests Look Like

The repository has several test layers. They answer different questions and must not be collapsed into
one accuracy number.

| Layer | Command or artifact | What it proves | What it does not prove |
| --- | --- | --- | --- |
| Schema and scorer self-consistency | `python3 -m harness_py validate` | The manifest loads, references resolve, a synthetic run can satisfy each authored contract, and the scorer closes over that run | Real retrieval, model behavior, or answer quality |
| Anchor audit | `python3 -m harness_py audit` | Every exact quote can be located on the authored page in the frozen Reading Model | That the Anchor is the only or best evidence |
| Retrieval algorithm benchmark | `qdrant_impact_benchmark.py` | BM25, sparse, dense, fusion, and coverage can be compared on the same corpus, saved queries, cutoffs, and obligations | Java authorization, MySQL hydration, or stable answer quality |
| Product probe | `qdrant_product_probe.py` | Python Gateway -> Java -> Qdrant -> Current Model -> MySQL is wired correctly and can be measured | Broad statistical model quality from three cases |
| Live Harness run | `python3 -m harness_py agent-run` | The real model uses the Agents SDK tools and Java/Qdrant product corpus, reads evidence, cites it, and submits an answer | Broad statistical answer quality from one run per case |
| Human adjudication | Frozen labels over saved runs | Whether the final answer and evidence are actually acceptable under a reviewed rubric | Automatic repeatability without a maintained labeling process |

The distinction around `validate` is especially important. `GoldenFixtureHarness` constructs the
evidence, facts, outcome, and citations directly from the case expectation. A 30/30 result is therefore
a schema and scorer contract test, not a model benchmark.

### Verified Current State

The following checks were rerun against the 2026-07-16 worktree:

```text
stable validate:   15/15 cases pass
expanded validate: 30/30 cases pass
stable audit:       7/7 anchors pass
expanded audit:    29/29 anchors pass
Qdrant preflight:   14 papers, 30 cases, 69 saved queries,
                    48 required-anchor obligations, 789 indexed points,
                    29/29 anchors mapped to the index projection
```

These results prove that the data is structurally executable and that its exact selectors remain
bound to the current Reading Models.

### How To Test The Harness

A reliable Harness evaluation should use the following sequence:

1. Run `validate` after changing a manifest, case, pack, schema, fixture, or deterministic scorer.
2. Run `audit` after changing a PDF, parser, Reading Model build, page mapping, or Anchor selector.
3. Run a retrieval-only suite against every candidate adapter at fixed scope and candidate budgets.
4. Run one or two representative live cases to check tool contracts and saved artifacts before paying
   for a full model run.
5. Run the full stable or held-out suite only after the earlier layers pass.
6. Generate the Candidate -> Read -> Cited -> Outcome funnel from saved artifacts.
7. Apply frozen human labels or a demonstrably calibrated judge to answer semantics.
8. Compare quality together with latency, tokens, retries, timeouts, and infrastructure failures.

This sequence localizes failures. For example, a missing Candidate is a retrieval problem; an exposed
Candidate that was not read is an agent-policy problem; a read that was not cited is a grounding or
submission problem; a well-cited but wrong conclusion is an answer-quality problem.

### How To Know Whether The Test Is Good

A good test system needs four kinds of validity.

**Dataset validity.** Paper identity, source version, PDF hash, Reading Model version, Anchor location,
and task expectation must be reviewable. Current Anchor audits provide strong location validity, but
the existing paper metadata has known limits, such as incomplete author lists for most papers.

**Measurement validity.** A score must measure what its name claims. Current `hard_pass` does not meet
that standard as an answer-accuracy metric. In the saved model comparison, strict Anchor scoring gave
MiniMax-M3 17/30 and GPT-5.5 14/30, while blind human review gave MiniMax-M3 22/30 and GPT-5.5 28/30.
The strict scorer both undercounted valid answers and reversed the model ranking. It should therefore
be named contract or Anchor conformance, not accuracy.

**Experimental validity.** Retrieval comparisons must freeze the corpus, queries, scope, cutoffs,
candidate budget, embedding contract, and scoring policy. They must save raw candidate scores and use
deterministic secondary ordering. Repeated runs are needed when the service cannot promise identical
full rankings.

**Product validity.** At least one gate must execute the real product data path. An offline algorithm
win is insufficient if authorization, Current Generation checks, hydration, timeouts, or operational
limits fail in production. Conversely, a three-case smoke test is useful for wiring but too small for
a stable quality claim.

The scorecard should therefore remain multidimensional:

- exact-location Candidate Recall and MRR;
- semantic evidence-obligation Recall at fixed `top_k`;
- Candidate -> Read and Read -> Cited conversion;
- outcome and citation-contract conformance;
- deterministic fact coverage where the answer schema exposes fields;
- human answer pass, factual-grounding pass, and pairwise preference;
- technical failure rate, latency, tokens, request count, retries, and memory.

No one metric can safely replace this scorecard.

### Does The Current Data Adapt To Qdrant?

Yes at the semantic-data level, only partly at the execution level.

The positive evidence is concrete. The current Qdrant benchmark reuses the expanded Manifest, frozen
Reading Models, 69 real MiniMax location searches, and 48 evidence obligations. It can compare BM25,
Qdrant sparse, dense, RRF, and product-style coverage without rewriting the cases. That demonstrates
that the corpus and task contracts are not inherently tied to a vector database or to BM25.

The limitations are equally concrete:

- `agent-run` tests Java/Qdrant directly and exposes product-path failures without fallback;
- exact Anchor IDs are attached to accepted reads by the offline Golden adapter, while the product
  Java Gateway returns no Golden Anchor IDs;
- Qdrant groups several Reading Elements into Page, Table, or Figure locations, whereas the in-memory
  path can rank individual Reading Elements;
- cases encode one exact required Anchor even when another location supports the same semantic claim;
- saved Qdrant runs have shown ranking variability, and historical artifacts did not always retain
  every raw score needed to explain it.

The July 15 benchmark illustrates the consequence. Under each method's native candidate behavior,
BM25 reached 44/48 exact Anchors and the product-style Qdrant method reached 34/48. Under the same
requested budget the comparison was 42/48 versus 34/48. After manual review of equivalent evidence,
the results were 48/48 versus 47/48. The exact metric exposes a real ranking and granularity change,
but it substantially overstates the loss of answerable evidence. Both views are useful; neither should
erase the other.

## 3. Paper Material And Reading Model Organization

### Papers Used By Golden Data

The stable `transformer_bert_gpt` pack contains five papers:

| Paper ID | Paper | Role |
| --- | --- | --- |
| `attention_is_all_you_need_2017` | Attention Is All You Need | target |
| `adam_2014` | Adam: A Method for Stochastic Optimization | predecessor |
| `bert_2018` | BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding | successor |
| `gpt2_2019` | Language Models are Unsupervised Multitask Learners | successor |
| `gpt3_2020` | Language Models are Few-Shot Learners | hard distractor |

The expanded `llm_agent_evaluation` pack adds nine papers:

| Paper ID | Paper | Role |
| --- | --- | --- |
| `react_2023` | ReAct: Synergizing Reasoning and Acting in Language Models | foundation |
| `agentbench_2024` | AgentBench: Evaluating LLMs as Agents | target |
| `gaia_2024` | GAIA: a benchmark for General AI Assistants | target |
| `webarena_2024` | WebArena: A Realistic Web Environment for Building Autonomous Agents | target |
| `swebench_2024` | SWE-bench: Can Language Models Resolve Real-World GitHub Issues? | target |
| `mint_2024` | MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback | target |
| `agentboard_2024` | AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents | target |
| `tau_bench_2024` | tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains | target |
| `toolsandbox_2024` | ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities | target |

The material is not just these citations. For each paper the repository retains the PDF, a canonical
Reading Model export, and at least a text projection. The stable pack also retains MinerU archives and
visual-asset manifests; the expanded pack is built through a staging and publication workflow that
requires structural and physical-page Anchor gates before release.

### Is There One Unified Reading Model Organization?

There is one unified logical Reading Model, but not one fully unified physical organization or
retrieval projection.

The logical model is unified because:

- all 14 Golden papers use `paperloom-reading-model-export/v1`;
- the export is produced by the production `PaperReadingModelBuilder` and
  `GoldenReadingModelExportWriter`, rather than by a separate test-only parser model;
- every export has the same top-level components: metadata, diagnostics, pages, sections, locations,
  Reading Elements, and parsed structured objects;
- the product database represents the same concepts as `paper_reading_models`, `paper_pages`,
  `paper_sections`, `paper_locations`, and `paper_reading_elements`;
- Qdrant is explicitly a rebuildable projection of Current Reading Model locations, not canonical
  evidence storage.

The physical and operational organization is not fully unified because:

- the stable and expanded packs now share `research/golden-data/corpora/`, but Golden fixtures still
  read versioned JSON exports while production reads normalized MySQL rows;
- Golden fixtures read versioned JSON exports, while production reads normalized MySQL rows;
- offline Harness retrieval ranks in-memory Reading Documents, while production groups Reading
  Elements by formal location and indexes those locations in Qdrant;
- exact Golden Anchors are a separate authoring layer and are not persisted as first-class product
  Reading Model entities;
- `data_dir` is currently both a corpus locator and an implicit asset-layout convention.

This is not evidence that the architecture has no canonical model. It is evidence that the canonical
model has several adapters and projections. That is healthy as long as their contracts are explicit,
versioned, and tested for equivalence. The current gap is not the existence of projections; it is that
their differences in candidate identity, granularity, and Anchor binding still leak into evaluation
meaning.

## 4. Organizing Golden Data For Future Architecture Changes

### Design Principle

Semantic truth must be stable; retrieval projections must be replaceable.

A user task should not need to change because BM25 becomes Qdrant, an embedding model changes, RRF is
reweighted, or a reranker is added. Those changes alter which candidates are returned and in what
order. They do not alter which paper facts would constitute a correct, well-grounded answer.

The current schema comes close to this goal, but `expect.evidence.required` points directly to one
exact Anchor. That makes a retrieval locator serve two roles:

1. a reproducible parser and ranking diagnostic; and
2. the semantic definition of acceptable evidence.

Those roles should be separated.

### Proposed Five-Plane Organization

**1. Canonical corpus plane**

Own immutable or explicitly versioned source identity:

- `paper_id`, source URL, source PDF SHA-256, and licensing/provenance metadata;
- `reading_model_schema_version` and `reading_model_version`;
- canonical pages, sections, locations, Reading Elements, source spans, and text hashes;
- asset inventory and parser diagnostics.

The corpus plane must not contain method-specific ranks or scores.

**2. Semantic task plane**

Own the user-facing contract:

- conversation messages, paradigm, and split;
- expected outcome and citation policy;
- required or forbidden papers;
- facts and claim obligations;
- semantic evidence-obligation IDs;
- human review criteria.

This plane must not mention BM25, Qdrant, an embedding model, RRF weights, or expected rank.

**3. Evidence locator plane**

Bind each semantic obligation to one or more versioned source selectors:

- primary exact quote and page selector;
- alternate exact locations that express the same fact;
- source text hash, Reading Model version, and selector version;
- equivalence scope, such as exact span, same Reading Element, same paper and fact, or manually
  adjudicated equivalent evidence;
- optional hard negatives that look relevant but do not satisfy the obligation.

Exact Anchors remain valuable for parser regression and precise ranking diagnostics. They simply stop
being the only definition of semantic success.

**4. Retrieval experiment plane**

Own method-dependent inputs and outputs:

- saved or generated queries and their originating case/turn;
- authorized paper scope, filters, element-type hints, and page bounds;
- requested and effective `top_k`;
- adapter name and version;
- chunk or point granularity;
- embedding model and dimension;
- sparse algorithm, fusion weights, reranker, and secondary ordering;
- index generation, configuration hash, corpus hash, and run seed where applicable;
- raw dense, sparse, fused, and reranker scores for every retained candidate.

Changing retrieval should create a new experiment run, not a new semantic case.

**5. Observation and adjudication plane**

Own what happened and how it was judged:

- Candidate, Read, Cited, Outcome, final answer, and technical-failure artifacts;
- exact-selector matches and semantic-obligation matches as separate fields;
- human equivalence decisions bound to candidate content hash and corpus version;
- human answer labels and calibrated judge reports;
- latency, tokens, retries, model/provider version, and infrastructure diagnostics.

This plane makes rescoring possible without altering source data or rerunning the model.

### Suggested Schema Direction

The current Anchors can be retained as primary locators while cases migrate to evidence obligations:

```yaml
evidence_obligations:
  - id: transformer_optimizer_parameters
    paper_id: attention_is_all_you_need_2017
    claim: The Transformer training setup used Adam with beta1 0.9, beta2 0.98, and epsilon 1e-9.
    facts:
      optimizer: Adam
      beta1: "0.9"
      beta2: "0.98"
      epsilon: "1e-9"
    acceptance:
      minimum_policy: same_paper_semantic_equivalence
    locators:
      - anchor_id: transformer_adam_training_params_span
        selector_version: 1
        reading_model_version: rm_golden_attention_is_all_you_need_2017
        page: 7
        exact_text: We used the Adam optimizer
```

A case would then use:

```yaml
expect:
  evidence_obligations:
    required: [transformer_optimizer_parameters]
```

The scorer can report both:

```text
exact_anchor_match: false
semantic_obligation_match: true
```

That preserves the signal that ranking moved away from the authored location without incorrectly
declaring the answer unsupported.

### Runner Matrix

The same cases should be runnable through explicit execution profiles:

| Profile | Corpus adapter | Purpose |
| --- | --- | --- |
| `fixture` | Synthetic Golden fixture | Schema and scorer contract |
| `golden-bm25` | Frozen JSON Reading Models and in-memory BM25 | Stable historical baseline |
| `product-qdrant` | Python Gateway, Java, Qdrant, MySQL | Current product behavior |
| `candidate-qdrant-*` | Isolated Qdrant collections and alternative algorithms | Offline retrieval experiments |
| `future-reranker` | Same candidate contract plus reranker | Architecture candidate evaluation |

The execution profile belongs to the run manifest, not to the Golden Case. Promotion requires the
candidate profile to pass the stable and held-out gates against the baseline profile.

### Split And Promotion Policy

Keep at least three data states:

- `stable`: small, reviewed, deterministic regression coverage;
- `development`: visible cases used to diagnose and tune a proposed change;
- `holdout`: sealed case, query, and preferably paper families used only for promotion decisions.

Paper and conversation families should not be split casually across development and holdout. A
retrieval method tuned on paraphrases of the same query or nearby Anchors can appear to generalize
without learning a transferable ranking policy.

Promotion should require all of the following under fixed budgets:

- no regression in semantic evidence-obligation recall on stable and holdout data;
- exact-location and MRR changes reported, even when semantic recall is preserved;
- no regression in Candidate -> Read -> Cited and human answer quality;
- acceptable p50/p95 latency, cost, timeout, and retry behavior;
- repeatable aggregate results across multiple runs;
- successful product-path smoke and operational gates.

### What Must Be Redone When Architecture Changes?

| Change | Rebuild Golden cases? | Required action |
| --- | --- | --- |
| Qdrant ranking weights, sparse formula, fusion, or reranker | No | Rerun retrieval and end-to-end profiles; preserve cases and corpus |
| Embedding model or dimension | No | Rebuild the index projection, record a new embedding contract, rerun gates |
| Candidate `top_k` or coverage policy | No | Run a new fixed-budget experiment; do not edit expected evidence |
| Element Point vs Location Point vs parent-child indexing | Usually no | Rebuild projections and locator mappings; compare exact and semantic recall |
| Parser or Reading Model rebuild with unchanged source PDF | Usually no | Rerun Anchor audit; version and repair locators only where source coordinates changed |
| Source PDF version changes | Possibly | Create a new corpus version, revalidate facts and locators, preserve the old baseline |
| User task semantics or accepted factual conclusion changes | Yes, targeted | Review and version the affected case or evidence obligation |

Thus, most retrieval architecture changes require reindexing and rebaselining, not Golden Data
reauthoring.

### Concrete Migration For This Repository

1. Add a versioned `evidence_obligations` or `anchor_groups` section to Paper Packs. Preserve all 29
   current Anchors as primary exact locators.
2. Migrate `cases/*.yaml` from direct exact-Anchor requirements to semantic obligation IDs, while
   keeping exact-Anchor scoring as a diagnostic projection.
3. Seed alternate-locator review from the existing Qdrant adjudication files, but require source hash
   and Reading Model version binding before treating an alternative as reusable Ground Truth.
4. Move saved queries, budgets, cutoffs, and retrieval method configuration into dedicated
   `retrieval-suites/` manifests instead of letting them emerge only from saved run directories.
5. Add an explicit Golden live-run profile that supplies `JavaCorpusGatewayReader`, so the same case
   set can execute the real product Qdrant path rather than relying on a separate three-case script.
6. Extend candidate artifacts to retain raw dense, sparse, fused, and reranker scores, deterministic
   tie-break keys, `location_ref`, grouped Reading Element IDs, text hash, index generation, and the
   complete retrieval configuration hash.
7. Separate score names: `contract_conformance`, `exact_anchor_recall`,
   `semantic_evidence_recall`, `answer_human_pass`, and `technical_success`. Do not publish
   `hard_pass` as answer accuracy.
8. Introduce sealed holdout cases and query families before tuning the next sparse or fusion strategy.
9. Replace the two implicit corpus roots with one explicit asset catalog or URI resolver. This can be
   done without immediately moving large PDFs: the manifest should describe each asset, while the
   resolver maps the logical identity to its current path.

## 5. Final Decisions

1. **The current Golden Data shape is fundamentally suitable for Harness evaluation.** It captures
   evidence and behavior, not only answer text, and it already supports deterministic, retrieval,
   product-smoke, live-agent, and human-review layers.
2. **The current default score is not a trustworthy answer-accuracy metric.** Exact Anchor conformance
   is useful, but human review has shown substantial false failures and a reversed provider ranking.
3. **The data can support Qdrant, but the main live Golden runner does not yet test Qdrant.** The
   separate Qdrant benchmark and product probe demonstrate reuse, while also exposing a runner gap.
4. **The Reading Model is logically unified but physically projected in several forms.** JSON exports,
   MySQL rows, in-memory documents, and Qdrant points represent one product-owned model at different
   boundaries. Their identity and granularity contracts need stronger normalization.
5. **Do not rebuild all Golden Data for later retrieval changes.** Decouple semantic evidence
   obligations from exact locators, version the corpus and locator bindings, isolate retrieval
   experiment configuration, and rerun a stable adapter matrix.
6. **Keep both exact and semantic metrics.** Exact-location regression can reveal parser, granularity,
   or ranking changes; semantic evidence recall and human answer quality determine whether the user
   task remains well supported.

## Evidence Reviewed

- [Golden Data workspace](../../research/golden-data/README.md)
- [Stable manifest](../../research/golden-data/manifest.yaml) and
  [expanded manifest](../../research/golden-data/manifest-expanded.yaml)
- [Stable Paper Pack](../../research/golden-data/paper-packs/transformer-bert-gpt.yaml) and
  [agent-evaluation Paper Pack](../../research/golden-data/paper-packs/llm-agent-evaluation.yaml)
- [Stable cases](../../research/golden-data/cases/core.yaml) and
  [agent-evaluation cases](../../research/golden-data/cases/llm-agent-evaluation.yaml)
- [Golden dataset loader](../../harness_py/evaluation/dataset.py),
  [fixture generator](../../harness_py/evaluation/golden_fixture.py), and
  [behavior scorer](../../harness_py/evaluation/scoring.py)
- [Reading Model and Harness tools](../architecture/reading-model-and-agent-tools.md)
- [Evidence and citation model](../architecture/evidence-and-citations.md)
- [Evidence-first Golden Case ADR](../adr/0011-use-evidence-first-golden-cases-for-harness-eval.md)
- [Offline-eval-first runtime ADR](../adr/0012-build-golden-schema-runtime-as-offline-eval-first.md)
- [Golden validity and model-bottleneck audit](golden-data-validity-and-model-bottleneck-audit-2026-07-14.md)
- [Qdrant retrieval impact report](qdrant-retrieval-impact-2026-07-15.md)
- [Qdrant index projection](../../src/main/java/io/github/chzarles/paperloom/service/ReadingModelQdrantIndexService.java)
- [Product corpus retrieval](../../src/main/java/io/github/chzarles/paperloom/service/CorpusRetrievalService.java)
