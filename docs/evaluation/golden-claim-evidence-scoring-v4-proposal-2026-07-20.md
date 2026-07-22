# Golden Claim-Evidence Scoring v4 Proposal

Date: 2026-07-20

Status: implementation complete; final benchmark publication blocked by the semantic-judge holdout gate

## 1. Decision

Golden answer scoring must stop requiring one authored Anchor as the definition of a correct,
grounded answer.

The v4 scoring unit is a reusable Golden Claim. A claim states the meaning the answer must express and
the paper evidence required to support it. Each paper requirement accepts one or more current product
`location_ref` values. A different valid location is unresolved until it is labelled through ordinary
Golden Data maintenance; it is not automatically wrong.

Exact Anchors remain useful only for corpus and index integrity checks. They do not participate in v4
answer pass/fail decisions.

## 2. Why v3 Must Be Replaced

The current live path binds evidence to an Anchor only when all of the following match:

- paper ID;
- page;
- normalized exact quote.

The v3 retrieval dimension then requires the authored Anchor ID, and the grounding dimension requires
that exact Anchor to be cited. A passage from the correct paper that proves the same claim can therefore
produce both `REQUIRED_ANCHOR_MISSING` and `REQUIRED_ANCHOR_NOT_CITED`.

This measures conformance to one selected source location. It does not reliably measure whether the
answer is correct and grounded. Frozen human adjudication has already demonstrated false failures and
a provider-ranking reversal under the exact-Anchor score.

The defect cannot be fixed by adding an exception list beside each required Anchor. That would retain
the wrong scoring unit. v4 instead makes the claim and its source requirements first-class data.

## 3. Current Product Facts Preserved By This Proposal

The proposal is based on the current product path rather than a compatibility corpus:

```text
MiniMax
-> Python Harness tools
-> Java Corpus API
-> Qdrant sparse BM25 candidate retrieval
-> location_ref
-> MySQL canonical Reading Model hydration
-> cited Evidence ID
```

The current Qdrant index uses four location types:

| Type | Retrieved unit |
| --- | --- |
| `PAGE` | Parsed text of one PDF page |
| `SECTION` | Parsed text under one heading, possibly across pages |
| `TABLE` | One table caption and parsed table body |
| `FIGURE` | One figure or chart caption/searchable description |

Ordinary paragraphs and formulas do not have independent current product locations. v4 therefore
does not invent paragraph IDs or create a second chunking system. An accepted location means that the
complete text returned by `read_locations(location_ref)` contains sufficient support for the claim.

The expanded 14-paper product corpus currently contains 1,386 active MySQL locations and 1,383 Qdrant
points with usable lexical content:

| Type | Count | Median characters | p90 characters | Maximum characters |
| --- | ---: | ---: | ---: | ---: |
| Page | 481 | 2,736 | 4,547 | 6,397 |
| Section | 596 | 1,370 | 5,072 | 30,048 |
| Table | 175 | 672 | 2,683 | 11,041 |
| Figure | 134 | 197 | 468 | 1,212 |

This granularity is appropriate for navigation and broad source grounding. Improving it is a separate
retrieval/product project, not part of the scoring repair.

## 4. Scope

### In Scope

- add a reusable Golden Claim catalog;
- make cases reference required claim IDs;
- bind each claim to one or more paper-specific evidence requirements;
- accept existing `PAGE`, `SECTION`, `TABLE`, and `FIGURE` locations;
- infer claim-to-citation association from citation placement already present in saved answers;
- distinguish `pass`, `fail`, and `review_required`;
- emit unknown correct-paper locations for Golden Data maintenance;
- retain deterministic typed fact checks;
- use a calibrated semantic judge for paraphrased claim fulfillment and additional material claims;
- rescore saved runs without calling MiniMax again;
- migrate all 10 stable and all 24 expanded cases before v4 is complete.

### Out Of Scope

- changing Qdrant indexing, sparse BM25, location granularity, ranking, or `top_k`;
- changing Java Corpus APIs, MySQL tables, or MiniMax tool definitions;
- adding paragraph or formula locations;
- storing reviewer identities, signatures, approval counts, or an authority model;
- automatically promoting an unseen location into Golden Data;
- allowing a semantic judge to override deterministic source or citation failures;
- rewriting or overwriting historical v2/v3 reports;
- treating exact Anchor recall as answer accuracy.

## 5. Golden Claim Catalog

Claims should be authored once and referenced by cases. This prevents the same fact and accepted
locations from being copied across direct questions, follow-ups, comparisons, and reproduction tasks.

Suggested file organization:

```text
research/golden-data/claims/core.yaml
research/golden-data/claims/llm-agent-evaluation.yaml
```

The manifest adds `claim_files`. Cases use `expect.required_claims` rather than embedding Anchor-backed
claim definitions repeatedly.

### Single-Paper Claim

```yaml
schema_version: harness-golden-claims/v1

claims:
  swebench_issue_provenance:
    statement: >-
      SWE-bench connects real GitHub issues to merged pull-request solutions and evaluates generated
      patches with repository tests.
    required_evidence:
      - paper_id: swebench_2024
        accepted_locations:
          - section_ref_9a8d04f7f6cb47c681f113b6ea0ce602
          - table_ref_0e523a3d08b043ce8c70c4ab9a948746
    forbidden_paper_ids:
      - react_2023
```

### Multi-Paper Claim

Every `required_evidence` entry is mandatory. A comparison that depends on two papers must cite
accepted evidence from both papers.

```yaml
claims:
  bert_transformer_architecture_comparison:
    statement: >-
      BERT uses a bidirectional Transformer encoder, while the original Transformer is presented as
      an encoder-decoder architecture.
    required_evidence:
      - paper_id: bert_2018
        accepted_locations:
          - section_ref_bert_architecture
      - paper_id: attention_is_all_you_need_2017
        accepted_locations:
          - section_ref_transformer_architecture
    forbidden_paper_ids:
      - gpt2_2019
```

### Case Reference

```yaml
cases:
  - id: swebench_instance_provenance_001
    expect:
      outcome: answered
      required_claims:
        - swebench_issue_provenance
      citations: required
```

Existing typed `facts` may remain on cases during v4. They continue to provide deterministic checks
for values such as `beta2 = 0.98`, percentages, counts, and normalized phrases. They complement rather
than replace the reusable semantic claims.

## 6. Evidence Semantics

### Accepted Locations

`accepted_locations` contains product `location_ref` values, not Anchor IDs, quote strings, Qdrant
point IDs, paragraph IDs, or duplicated source text.

A location is acceptable for a claim when the complete canonical text returned through the product
read path supports that claim. Accepted locations may mix pages, sections, tables, and figures.

For a figure, only its returned caption/searchable description counts. v4 must not infer facts from
pixels that were not provided to the evaluator.

### Location Lifecycle

The accepted list is deliberately bound to the current Golden product corpus. A Qdrant collection
rebuild from the same MySQL Reading Model keeps the existing `location_ref` values and does not require
claim maintenance. A parser or Reading Model rebuild may create new location references; the location
audit must then report the old entries as unresolved and the maintainer must label the replacement
locations in a new Golden version.

v4 does not add a compatibility mapping, `paper_version`, or automatic rebinding rule. This is the
explicit cost of using only the current product locations as evidence identity. A benchmark run must
therefore freeze the product corpus while it is being scored and record the corpus/index generation in
its run manifest.

### Required Paper Rule

For each `required_evidence` entry, at least one citation attached to the claim must:

1. resolve to accepted citeable evidence;
2. come from the declared `paper_id`;
3. have a `location_ref` listed in `accepted_locations`.

All entries must pass. One paper cannot satisfy another paper's side of a comparison.

### Claim-Scoped Forbidden Papers

A forbidden paper fails only when its citation is attached to the affected claim. Its name or evidence
may appear elsewhere for a different legitimate statement.

Examples:

```text
Claim X. [required Paper A]
-> pass

Claim X. [required Paper A] [forbidden Paper B]
-> fail

Paper B establishes a different contextual fact. [Paper B]
Claim X. [required Paper A]
-> no forbidden-paper failure
```

v4 does not define a global paper-name ban. Such a user constraint would require an explicit separate
policy rather than overloading grounding.

## 7. Claim-To-Citation Association

The current model already writes inline `[[evidence_id]]` markers. `build_harness_run` converts them to
numbered citations and stores the Evidence IDs in the same first-appearance order:

```json
{
  "markdown": "Claim A. [1]\n\nClaim B. [2]",
  "cited_evidence_ids": ["ev_A", "ev_B"]
}
```

v4 can therefore reconstruct `[1] -> ev_A` and `[2] -> ev_B` without changing the MiniMax tool schema
or historical run artifacts.

The scorer should:

1. remove the generated `Sources` section from scoring input;
2. split the visible answer into Markdown paragraphs, list items, and table rows;
3. retain the numbered citations attached to each block;
4. map each number back to the ordered `cited_evidence_ids` list;
5. expose stable block IDs to deterministic fact matching and the semantic judge.

No citation in another answer block may satisfy the claim. This is the minimum mechanism needed for
claim-scoped source policy without adding model-visible Golden IDs.

## 8. Correctness And Grounding

Evidence presence alone does not prove that the answer states the right conclusion. v4 separates the
following checks.

### Deterministic Typed Facts

Existing typed fact assertions remain deterministic. The scorer locates the answer block containing
the expected labelled value or normalized phrase, then checks citations attached to that block.

### Semantic Required Claims

A calibrated semantic judge receives:

- the user request;
- the authored claim statement;
- numbered answer blocks;
- the evidence IDs attached to each block.

For every required claim it returns:

```yaml
claim_id: swebench_issue_provenance
verdict: expressed | contradicted | missing | uncertain
matched_block_ids: [block_3]
```

The judge decides only whether the answer expresses the authored meaning and which visible blocks do
so. Python code then applies paper, location, citation, and forbidden-source rules deterministically.
The judge cannot approve an unknown location or override a source-policy failure.

`uncertain` and judge technical errors produce `review_required`.

### Additional Material Claims

Required claims are a minimum, not a licence to hallucinate. A materially false or unsupported extra
claim must fail the case even when all required claims pass. The semantic judge evaluates additional
material claims against their cited evidence. Style, formatting, and clearly labelled interpretation
do not fail unless they materially change the user's understanding.

Run-specific support of an additional claim does not add a location to the Golden Claim catalog.

## 9. Unknown Location Workflow

An unseen location from the correct required paper is not automatically wrong and not automatically
correct.

### Scoring Outcome

When the matched answer block cites a correct-paper location that is not in `accepted_locations`, the
claim becomes `review_required`. The scorer emits a candidate containing only the information needed
for ordinary Golden maintenance:

```yaml
claim_id: swebench_issue_provenance
case_id: swebench_instance_provenance_001
run_id: run_live_chat_example
paper_id: swebench_2024
evidence_id: ev_example
location_ref: section_ref_example
returned_text: >-
  The canonical text that MiniMax read and cited.
```

There is no reviewer identity, authority field, signature, mandatory reviewer count, or automatic
semantic approval. The tool verifies that the candidate came from the saved run and cited evidence;
the maintainer decides whether to add its `location_ref` to the claim catalog.

### Maintenance Cycle

```text
frozen Golden vN
-> run or rescore all models
-> emit unknown-location candidates
-> maintain claim catalog
-> publish Golden vN+1
-> rescore every saved model run against vN+1
-> require review_required = 0 before final publication
```

Rejecting a candidate requires no permanent negative registry in v4. If repeated review becomes a
measured problem, a rejection cache can be proposed separately rather than pre-emptively expanding
the schema.

## 10. Score Report v4

v4 must not reuse the historical meaning of `hard_pass`.

```yaml
schema_version: harness-score-report/v4
scorer_contract:
  version: behavior-scorer/v4
case_count: 24
passed_count: 18
failed_count: 4
review_required_count: 2
resolved_count: 22
resolved_pass_rate: 0.8182
review_coverage: 0.9167

scores:
  - case_id: example
    case_status: pass | fail | review_required
    dimensions:
      outcome: pass
      required_claims: pass
      grounding: pass
      source_policy: pass
      additional_claims: pass
```

Case precedence is:

1. any genuine dimension failure -> `fail`;
2. otherwise any unresolved or uncertain dimension -> `review_required`;
3. otherwise -> `pass`.

A final published benchmark requires `review_required_count = 0`. Interim reports must present both
resolved pass rate and review coverage; they must not silently put unresolved cases in either the pass
or fail denominator.

The report records:

- Golden dataset content hash;
- claim catalog content hash;
- scorer contract and hash;
- semantic judge model/provider diagnostics;
- judge prompt version;
- calibration and holdout report paths and hashes;
- source run directory and run-artifact hashes.

Historical `harness-score-report/v2` and `/v3` files remain immutable. A v4 rescore always writes to a
new file or directory.

## 11. Semantic Judge Gate

The semantic judge may affect final v4 results only after passing frozen human-labelled calibration
and holdout gates for the v4 judgment contract.

Required conditions:

- calibration and holdout sources are frozen and hashed;
- model and prompt version are fixed in the report;
- no technical errors;
- no false passes under the accepted calibration policy;
- the configured agreement gate passes;
- every judge disagreement or unavailable result becomes `review_required`.

The existing human-labelled runs and blind-review materials provide the starting corpus. The vertical
slice must add any missing per-claim and matched-block labels needed to prove the new judge contract.
Until that gate passes, semantic cases cannot receive a final v4 pass.

The judge remains offline evaluation infrastructure. It is not added to the online answer path.

## 12. Exact Anchors After v4

Anchors are removed from case answer obligations:

- no `expect.evidence.required` in migrated v4 cases;
- no `expect.evidence.forbidden` in migrated v4 cases;
- no `claims[].evidence` Anchor lists;
- no `REQUIRED_ANCHOR_MISSING` or `REQUIRED_ANCHOR_NOT_CITED` in v4 answer scoring.

Exact selectors may remain in a separate audit dataset to verify that known source text is still
present after parser, Reading Model, or indexing changes. That audit reports corpus/index integrity
only. It cannot change `case_status`.

If the repository later decides that these exact probes no longer have an operational consumer, they
can be deleted without changing the v4 claim contract.

## 13. Implementation Boundary

The scoring repair is confined to Golden authoring and Python evaluation.

### Required Changes

- add claim catalog loading and validation;
- validate case `required_claims` references;
- derive citation blocks from saved Markdown and ordered Evidence IDs;
- add deterministic claim evidence and source-policy scoring;
- add unknown-location candidate export;
- add the v4 semantic judge contract and calibration gate;
- emit `harness-score-report/v4`;
- extend offline `rescore` to write v4 reports without mutating runs.

### No Required Changes

- Java Corpus Gateway;
- Qdrant collection, point payload, sparse vector, or ranking;
- MySQL schema or canonical Reading Model builder;
- `read_locations` and `location_ref` generation;
- MiniMax final-answer tool schema;
- saved v2/v3 run artifacts.

## 14. Migration Plan

### Phase 1: Representative Vertical Slice

Use saved runs and at least these behavior classes:

| Behavior | Representative case |
| --- | --- |
| Deterministic numeric fact | `transformer_adam_params_001` |
| Single-paper semantic claim | `swebench_instance_provenance_001` |
| Multi-paper comparison | `bert_vs_transformer_comparison_001` |
| Claim-scoped forbidden source | `toolsandbox_constraint_selection_001` |
| Table or figure evidence | `agentbench_environment_inventory_001` |

The slice must demonstrate both repaired false failures and preserved real failures before any bulk
migration.

### Phase 2: Full Authoring Migration

- author reusable claims for all active case obligations;
- seed accepted locations from current product reads and existing human equivalence adjudication;
- remove direct Anchor requirements from all 10 stable and 24 expanded cases;
- validate every accepted location through the Java/Qdrant/MySQL product path;
- complete the v4 judge calibration and holdout gate.

### Phase 3: Offline Rescore

- rescore the preserved stable and expanded MiniMax runs;
- rescore preserved comparison-provider runs with the same v4 contract;
- emit unknown-location candidates;
- maintain the catalog and rescore until no unresolved candidates remain;
- preserve every original run and v2/v3 score report.

### Phase 4: Full Benchmark

- run all 10 stable and all 24 expanded cases through the product Qdrant path;
- write to a new timestamped output directory;
- require zero technical failures and zero `review_required` cases for final publication;
- report v4 dimensions, latency, tokens, retries, and cost separately from exact-Anchor audit results.

## 15. Acceptance Gates

The proposal is implemented only when all of the following are proven:

1. All current Golden claims are represented in the reusable catalog.
2. All stable and expanded cases reference catalog claims rather than required Anchors.
3. Every accepted location resolves through the current product corpus path and has one of the four
   supported location types.
4. Single-paper and multi-paper requirements are independently tested.
5. Claim-scoped forbidden papers fail only in the affected answer block.
6. Existing numbered citations are reconstructed correctly for saved runs.
7. Unknown correct-paper locations become `review_required` and produce complete candidate records.
8. Typed fact checks remain deterministic and covered by regression tests.
9. The semantic judge passes frozen calibration and holdout gates for the v4 contract.
10. Material unsupported additional claims still fail.
11. Known exact-Anchor false failures no longer fail solely for using equivalent evidence.
12. Known human-confirmed model failures remain failures.
13. v4 reports contain pass, fail, and review-required accounting with immutable contract hashes.
14. Historical run and score artifacts are byte-for-byte unchanged.
15. The representative slice passes before all 10 stable and 24 expanded cases are migrated.
16. Final full-manifest publication has zero unresolved cases and uses a new output directory.
17. The complete Harness test suite passes without Java, Qdrant, MySQL, or MiniMax contract changes.

## 16. Resulting Rule

The v4 rule is:

> Every required claim must be expressed correctly. For every paper needed to establish that claim,
> the same answer block must cite at least one accepted product location from that paper. A forbidden
> paper attached to that claim fails it. An unseen correct-paper location requires review rather than
> being called wrong. Material unsupported extra claims still fail.

This changes the evaluation from "did the model cite the one sentence selected by the author?" to
"did the model answer the required claim correctly with acceptable evidence from every required
paper?"

## 17. Implementation Record (2026-07-22)

The Python scoring and Golden authoring migration are complete:

- both active manifests use `harness-golden-data/v3`;
- all 10 stable and 24 expanded cases use reusable `required_claims` and no v2 paper/Anchor/inline-claim obligations;
- the catalog contains current `PAGE`, `SECTION`, `TABLE`, and `FIGURE` location references only;
- v4 reconstructs numbered citations by Markdown block, enforces same-block multi-paper evidence and
  claim-scoped forbidden papers, exports unknown-location candidates, and preserves deterministic facts;
- saved-run judgment and rescore commands require immutable input hashes and matching calibration and
  holdout judge identities;
- live product reads no longer attach Anchor IDs; exact Anchors remain retrieval diagnostics only;
- historical v2/v3 runs and reports were not modified.

Verification results after the dedicated claim/block judge replaced the reused v5 answer judge:

```text
stable fixture:   10 pass, 0 fail, 0 review_required
expanded fixture: 24 pass, 0 fail, 0 review_required
product claim-location audit: 77/77 claim references resolved, 33 unique locations, 0 failures
Harness test suite: 176 passed, 1 skipped
```

New product runs now write a content-addressed `run_manifest.json` for the observed corpus locations
and retrieval trace. This records one corpus/index observation without introducing paper versions or
changing the Java, Qdrant, MySQL, or MiniMax contracts.

The MiniMax judge was narrowed further: each required claim is matched in its own text-only call, then
additional grounding is checked separately. This prevents attached evidence or another claim from
changing the claim-expression decision. MiniMax-M3 passed the resulting seven-case calibration gate
at `7/7`, with zero false passes and zero technical errors:
`research/golden-data/local-runs/claim-judge-calibration-20260722-192202.json`.

A fresh seven-case holdout was then frozen from different saved answers that were not used while
developing the prompt. MiniMax-M3 achieved `2/7` full case agreement, with one false pass and zero
technical errors. It missed an explicit contradiction when an answer ranked incomparable benchmark
percentages, and it rejected several human-supported additional-grounding examples. The immutable
report is `research/golden-data/local-runs/claim-judge-holdout-20260722-192327.json`.

The holdout gate therefore remains rejected. The full answering benchmark was not run because the
proposal explicitly requires both gates to pass first. This is an empirical MiniMax publication
blocker, not a fallback to exact-Anchor answer scoring.
