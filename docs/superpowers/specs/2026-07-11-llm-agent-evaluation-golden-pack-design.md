# Targeted LLM-Agent Evaluation Golden Pack Proposal

Date: 2026-07-11
Status: Draft for user review
Implementation status: No PDFs downloaded, no reading models generated, no pack or cases authored

## Decision Summary

Build one compact, benchmark-centric paper pack for evaluating research behavior around LLM agents.
The proposed pack contains nine core papers and thirteen core Golden cases. OSWorld is a reserve paper
for a later multimodal extension rather than part of the first implementation.

The pack is deliberately not a general survey of all agent methods. It focuses on how agents are
evaluated: environment construction, task provenance, interaction protocols, metrics, reliability,
failure modes, and cross-benchmark comparison.

## Evidence From The Current Harness

The current five-paper pack was rerun through MiniMax-M3 before designing this expansion. The
first-attempt result was 3/12, and all nine failing cases remained failing over two additional samples.
The durable report is:

[`docs/qa/golden-v2-live-baseline-2026-07-11.md`](../../qa/golden-v2-live-baseline-2026-07-11.md)

The new pack should not hide the current weaknesses. It should continue to exercise structured
answers, outcome decisions, and complete evidence coverage while adding benchmark-centric research
behaviors.

## Scope Alternatives

### A. Benchmark-Centric Pack (Recommended)

Use one foundational agent method plus eight evaluation papers. This yields a dense set of comparable
benchmark definitions, protocols, metrics, and failure analyses.

Advantages:

- Small enough to inspect manually.
- Strong support for provenance, comparison, reproduction, critical assessment, and boundary cases.
- Most questions can be answered from two to four papers rather than the entire corpus.
- Failures remain attributable to harness behavior rather than corpus breadth.

Trade-off: it does not represent the full agent-method landscape.

### B. Method-to-Benchmark Genealogy Pack

Use ReAct, Toolformer, Reflexion, and planning/memory methods alongside a smaller benchmark set.

Advantages: stronger technical lineage and method comparison.

Trade-off: weaker coverage of evaluation design, benchmark provenance, and reliability metrics. The
pack would test agent algorithms more than agent evaluation.

### C. Broad Environment Pack

Mix web, software engineering, desktop, multimodal, embodied, and enterprise-agent benchmarks.

Advantages: more realistic breadth and stronger recommendation tasks.

Trade-off: twelve to fifteen papers, heterogeneous parser requirements, and too many confounding
variables for the next iteration.

## Recommended Papers

All entries below were verified against their canonical arXiv records. Venue claims are included only
where confirmed by the arXiv record or DBLP/OpenReview in this pass.

| ID | Paper | Status and role | Canonical source |
|---|---|---|---|
| `react_2023` | ReAct: Synergizing Reasoning and Acting in Language Models | ICLR 2023; foundational reasoning-and-action method used as a lineage and baseline anchor | [OpenReview](https://openreview.net/forum?id=WE_vluYUL-X), [arXiv 2210.03629](https://arxiv.org/abs/2210.03629) |
| `agentbench_2024` | AgentBench: Evaluating LLMs as Agents | ICLR 2024; broad evaluation across eight interactive environments and failure categories | [OpenReview](https://openreview.net/forum?id=zAdUB0aCTQ), [arXiv 2308.03688](https://arxiv.org/abs/2308.03688) |
| `gaia_2024` | GAIA: a benchmark for General AI Assistants | ICLR 2024; general-assistant tasks combining reasoning, browsing, tools, and multimodality | [OpenReview](https://openreview.net/forum?id=fibxvahvs3), [arXiv 2311.12983](https://arxiv.org/abs/2311.12983) |
| `webarena_2024` | WebArena: A Realistic Web Environment for Building Autonomous Agents | Realistic reproducible web environment and functional-correctness evaluation | [arXiv 2307.13854](https://arxiv.org/abs/2307.13854), [project](https://webarena.dev/) |
| `swebench_2024` | SWE-bench: Can Language Models Resolve Real-World GitHub Issues? | ICLR 2024; real GitHub issue and pull-request provenance with execution-based evaluation | [OpenReview](https://openreview.net/forum?id=VTF8yNQM66), [arXiv 2310.06770](https://arxiv.org/abs/2310.06770) |
| `mint_2024` | MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback | ICLR 2024; multi-turn tool use and simulated natural-language feedback | [arXiv 2309.10691](https://arxiv.org/abs/2309.10691), [project](https://xingyaoww.github.io/mint-bench) |
| `agentboard_2024` | AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents | NeurIPS 2024 Oral; progress-rate metric and process-level agent analysis | [arXiv 2401.13178](https://arxiv.org/abs/2401.13178) |
| `tau_bench_2024` | tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains | Dynamic user interaction, domain rules, database end-state evaluation, and pass^k reliability | [arXiv 2406.12045](https://arxiv.org/abs/2406.12045) |
| `toolsandbox_2024` | ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities | Stateful tools, on-policy user simulation, milestone evaluation, and insufficient-information tasks | [arXiv 2408.04682](https://arxiv.org/abs/2408.04682), [code](https://github.com/apple/ToolSandbox) |

### Reserve Paper

| ID | Paper | Activation rule | Canonical source |
|---|---|---|---|
| `osworld_2024` | OSWorld: Benchmarking Multimodal Agents for Open-Ended Tasks in Real Computer Environments | Add only after the first nine-paper pack is stable and visual/table extraction has a verified use case | [arXiv 2404.07972](https://arxiv.org/abs/2404.07972), [project](https://os-world.github.io/) |

Toolformer and Reflexion are intentionally excluded from the first pack. ReAct provides the needed
method anchor; additional method papers would dilute the evaluation focus.

## Candidate Evidence Anchors

The following are anchor themes, not authored quotes. Exact quote text, page, section, and uniqueness
must be verified against the pinned PDF and generated reading model after approval.

| Paper | Candidate anchor 1 | Candidate anchor 2 |
|---|---|---|
| ReAct | Interleaving reasoning traces and task actions | ALFWorld and WebShop success-rate improvements |
| AgentBench | Eight-environment benchmark composition | Long-term reasoning, decision-making, and instruction-following failure causes |
| GAIA | 466-question construction and retained-answer setup | Human 92% versus GPT-4-with-plugins 15% gap |
| WebArena | Four realistic web domains and reproducible environment | GPT-4 agent 14.41% versus human 78.24% task success |
| SWE-bench | 2,294 issues from 12 Python repositories | Claude 2 resolving 1.96% in the original evaluation |
| MINT | Multi-turn tools plus natural-language feedback protocol | Reported gains from tools and feedback, plus weak transfer from single-turn quality |
| AgentBoard | Limitation of final success rate alone | Fine-grained progress-rate metric |
| tau-bench | Database end-state comparison and domain policy constraints | pass^k reliability and low pass^8 result |
| ToolSandbox | Stateful, conversational, on-policy evaluation design | State dependency, canonicalization, and insufficient-information difficulty categories |

Target: eighteen authored anchors, normally two per paper. Add a third anchor only when a case cannot
be grounded by the existing two. Every anchor must remain exact, unique, and page-constrained.

## Proposed Golden Cases

The first pack adds thirteen core cases. Each case still evaluates one next assistant turn and uses
the current behavior-first V2 schema.

| Case ID | Primary paradigm | Research obligation | Main papers |
|---|---|---|---|
| `agentbench_environment_inventory_001` | Data and benchmark provenance | State the number and nature of AgentBench environments and identify the paper's reported failure categories | AgentBench |
| `gaia_dataset_gap_001` | Data and benchmark provenance | Explain GAIA's question construction and extract the human/model performance gap | GAIA |
| `webarena_reproduction_protocol_001` | Methodology and reproduction | Produce a concise reproduction checklist for environment domains, initial state, task execution, and functional-correctness evaluation | WebArena |
| `swebench_instance_provenance_001` | Data and benchmark provenance | Trace a benchmark instance from GitHub issue and repository state to the corresponding patch-based evaluation task | SWE-bench |
| `mint_vs_tau_interaction_comparison_001` | Deep comparison | Compare user feedback, tool access, policy constraints, environment state, and reliability metrics | MINT, tau-bench |
| `agentboard_progress_metric_001` | Contribution summary and critical assessment | Explain why final success rate is insufficient and what progress rate adds | AgentBoard |
| `toolsandbox_constraint_selection_001` | Complex constraint combination | Select the benchmark satisfying stateful tools, on-policy conversation, intermediate milestones, and insufficient-information handling | ToolSandbox; MINT and tau-bench as distractors |
| `cross_benchmark_human_agent_gap_001` | Boundaries, failures, and counterexamples | Compare the human-agent gaps reported by GAIA and WebArena without treating their metrics as directly interchangeable | GAIA, WebArena |
| `agent_evaluation_recommendation_001` | Meta-analysis and systematic review | Recommend one benchmark each for general assistants, web tasks, software engineering, and policy-constrained tool interaction | GAIA, WebArena, SWE-bench, tau-bench |
| `react_to_agent_evaluation_genealogy_001` | Association, influence, and genealogy | Trace only directly verified uses or citations of ReAct-style reasoning/action in later evaluation work | ReAct plus later papers whose direct citation is verified after PDF inspection |
| `mint_tau_apparent_conflict_001` | Contradiction resolution | Explain why MINT's gains from tools/feedback do not contradict tau-bench's low multi-trial reliability | MINT, tau-bench |
| `agent_benchmark_ambiguous_001` | Ambiguity resolution | For the prompt `agent benchmark`, ask whether the user means general assistant, web, coding, or tool-policy evaluation | No citations; clarification required |
| `coding_benchmark_followup_001` | Context-specific multi-turn resolution | Resolve `the coding one` from a prior benchmark-choice turn to SWE-bench and provide grounded selection reasons | SWE-bench |

### Conditional Structured Case

`agent_result_table_extraction_001` is not part of the initial thirteen. Add it only if parser
inspection identifies one stable result table represented as typed table content in the reading model.
It must test row/column interpretation and setting constraints, not OCR string matching.

## Hard Checks And Review Checks

Use hard checks for:

- Explicit outcome.
- Required and forbidden papers.
- Required and forbidden anchors.
- Stable scalar facts that are actually named by the answer contract.
- Citation presence and citation-to-evidence validity.

Use semantic or human review for:

- Whether comparisons align equivalent axes.
- Whether apparent conflicts are resolved by protocol differences.
- Whether recommendations match the user's scenario.
- Whether a critical assessment distinguishes metric limitations from benchmark limitations.

Do not score paradigm labels, stage names, tool-call counts, trace order, exact answer prose, or
model-generated claim identifiers.

## Structured-Fact Constraint

The current live baseline exposed a contract problem: all five fact-bearing cases failed, often when
the prose was correct but `answer.fields` was empty or used a semantic alias. New fact expectations
must not silently multiply arbitrary field keys.

Before authoring the new fact-bearing cases, choose one stable rule:

1. The user request explicitly names the output field keys; or
2. The harness derives a task-visible answer schema independently of Golden expectations.

The proposal recommends option 2 for product quality, but that is a separate harness design decision.
Until it is approved, fact-bearing cases may be drafted but must not be used to claim harness quality.

## Proposed File Layout

```text
research/golden-data/
├── manifest.yaml
├── cases/
│   ├── core.yaml
│   └── llm-agent-evaluation.yaml
└── paper-packs/
    ├── transformer-bert-gpt.yaml
    └── llm-agent-evaluation.yaml

data/golden/llm-agent-evaluation/
└── reading-models/
    ├── react_2023.reading-model.json
    ├── agentbench_2024.reading-model.json
    └── ...
```

The current pack and cases remain unchanged. The new pack is independently selectable and does not
turn the five-paper corpus into one mixed fourteen-paper corpus for every case.

## Construction Sequence After Approval

1. Pin the exact canonical PDF version for each of the nine core papers.
2. Download only those nine PDFs.
3. Run the existing paper pipeline to generate nine reading-model JSON files.
4. Inspect parser coverage before authoring anchors, especially tables and section/page metadata.
5. Author paper identities and provisional citation edges.
6. Verify every citation edge from the citing paper's bibliography or text; do not encode thematic
   similarity as a citation edge.
7. Author no more than eighteen initial anchors.
8. Run the independent anchor audit and require exactly one page-constrained match per anchor.
9. Author the thirteen cases without exposing expectations or paradigm labels to the runtime.
10. Run deterministic validation, then one live run per case.
11. Retry only transport failures that produced no score report.
12. Review `score_report.json`, `evidence_ledger.json`, and `research_answer.json`, then conduct five
    terminal-chat sessions using natural phrasings related to the pack.

## Acceptance Gates

### Dataset Health

- Nine pinned papers are present as reading models.
- Every paper identity and source URL is manually verified.
- Every anchor has a positive page and exactly one audit match.
- Every required or forbidden anchor belongs to the case's paper pack.
- No Golden expectation appears in model-facing messages or tool payloads.

### Executability

- All thirteen cases produce a valid harness run and score report.
- Transport failures are reported separately from Golden hard failures.
- No case is marked passing because of a missing run or an empty 0/0 report.

### Harness Signal

- Report outcome, retrieval, content, and grounding separately.
- Treat semantic review as `not_run` until a human review is performed.
- Do not require a perfect first live score for the pack to be useful.
- Do require that failures be attributable to observable harness behavior rather than broken PDFs,
  missing anchors, or invalid case authoring.

## Review Decisions

The user review should decide three things before implementation:

1. Approve the nine core papers and keep OSWorld as a reserve, or replace one paper.
2. Approve the thirteen-case matrix and the conditional table case.
3. Decide the structured-answer-field rule before new fact-bearing cases become hard quality gates.

No paper acquisition or Golden Data implementation should begin before these decisions are reviewed.
