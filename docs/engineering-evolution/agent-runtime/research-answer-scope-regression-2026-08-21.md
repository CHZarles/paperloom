# Research Answer Scope Regression: Asymmetric Quantization

Date: 2026-08-21
Status: Prompt contract clarified after two production replays; final replay pending
Question: `什么事非对称量化`

## Problem

The production answer passed the deterministic citation Validator but was too long, mixed three different levels of
the topic, and reproduced paper-specific notation without enough explanation:

1. general affine asymmetric quantization;
2. LLM.int8 zeropoint quantization and Transformer outliers;
3. QLoRA NF4's asymmetric codebook construction.

The user asked only for a definition. The Run therefore demonstrated a specification gap: citation validity was
treated as sufficient for publication even though it does not establish relevance, proportional depth, conceptual
consistency, or explanatory quality.

## Baseline

Production trace: `run_27913af8dbb04a649b9f84bddad48349`

| Metric | Baseline |
| --- | ---: |
| Status | `COMPLETED` |
| Model calls | `12` |
| Tool calls | `9` |
| Submission attempts | `4` |
| Provider protocol repairs | `0` |
| Prompt / completion / total Tokens | `161,771 / 10,491 / 172,262` |
| Harness duration | `152,597 ms` |
| Accepted submitted Draft | `4,534` characters |
| Rendered visible answer | `3,492` characters |

The submission sequence shows the failure mode:

```text
model_7:  2,159 chars -> UNCITED_CONTENT_BLOCK
model_10: 4,171 chars -> UNCITED_CONTENT_BLOCK
model_11: 4,288 chars -> UNCITED_CONTENT_BLOCK
model_12: 4,534 chars -> accepted
```

Instead of making a proportional citation correction, MiniMax repeatedly regenerated and expanded the answer until
every factual Markdown block contained a citation. The Validator behaved as designed; the model optimized for its
narrow acceptance signal rather than the user's requested depth.

## Decision

Keep the existing deterministic boundaries:

- paper and location authorization;
- known `source_quote_ref` identity;
- block-level citation presence;
- one final submission Tool Call;
- bounded Provider repairs and Agent turns.

Change only the existing System Prompt. Do not add a classifier, answer template, second Agent, semantic LLM Judge,
or global short character limit.

The Prompt now gives the Agent a goal rather than another fixed workflow:

- answer the user's actual question with the least research and detail needed for a complete answer;
- use the shortest complete answer when the user does not request depth;
- treat a simple definition as a short definition plus at most one useful example or comparison by default;
- let evidence constrain claims without allowing retrieved material to expand the answer's topic;
- avoid reproducing malformed or unexplained notation as a universal definition;
- after rejection, preserve good content and make a correction proportional to the Validator issue.

## Regression Procedure

After deployment, start a new conversation with the exact question:

```text
什么事非对称量化
```

Record the new Agent Trace and compare it with the baseline. A single stochastic replay is mechanism evidence, not a
stable performance benchmark.

Acceptance checks:

1. the Run completes and citation validation passes;
2. the answer directly defines asymmetric quantization;
3. NF4, a paper survey, and Transformer-outlier analysis are absent unless necessary to answer the question;
4. uncertain paper-specific equations are absent;
5. the answer is shorter than `800` visible Chinese characters;
6. a rejected submission does not grow by more than 25% unless the Validator reports missing content rather than a
   citation issue;
7. model calls, submissions, Tokens, duration, and answer length are recorded without claiming stable improvement
   from one replay.

## Production Replay

### First Replay: Directionally Better, Acceptance Failed

Commit `73be742` was deployed to all four Redis Harness Workers and replayed against the same 36-paper scope.

Trace: `run_c8ed36cf4dc34a798a59ad051c62fff7`

| Metric | Baseline | First replay |
| --- | ---: | ---: |
| Status | `COMPLETED` | `COMPLETED` |
| Model calls | `12` | `7` |
| Tool calls | `9` | `8` |
| Submission attempts | `4` | `3` |
| Total Tokens | `172,262` | `81,011` |
| Harness duration | `152,597 ms` | `74,827 ms` |
| Rendered visible answer | `3,492` chars | `1,772` chars |

The change reduced work, but the answer still included an equation, Transformer-specific framing, and a QLoRA NF4
section. It therefore failed checks 3, 4, and 5. These single-Run reductions are diagnostic measurements, not stable
performance claims.

The trace showed why the first Prompt was too weak. MiniMax initially submitted a compact 570-character definition
without reading evidence. After the Validator rejected it for missing evidence, the model searched LLM.int8 and QLoRA,
then allowed the retrieved material to expand the answer despite the new general guidance. The follow-up makes
progressive disclosure explicit for introductory `what is X?` questions, tells the Agent to stop once the requested
scope has direct support, repeats scope guidance in the submission Tool description, and forbids adding sections or
topics for a citation-only repair. No runtime component or Validator rule was added.

### Final Replay

The next replay of commit `513f703` exposed a separate contract-selection interaction rather than an answer-length
result.

Trace: `run_de273932a1a641169c46124af5560e80`

MiniMax correctly recognized the request as an introductory definition and produced a compact answer, but tried to
put that substantive answer in `submit_direct_answer`. The deterministic protocol rejected it with
`DIRECT_QUESTION_NOT_ALLOWED` and locked the Run to the Direct contract. Research tools were no longer legal, so after
five calls the model converged to an unnecessary clarification instead of answering:

```text
你希望了解哪个层面的“非对称量化”？例如：某篇具体论文中的实现方式，或一般性的技术概念定义？
```

This proved that answer depth and evidence contract must be stated independently. “Use the least research” must not
be interpreted as “answer a factual technical question through the Direct contract.” The Prompt now explicitly says
that a short technical definition still uses `RESEARCH`, reads the minimum exact evidence, and submits a short
`submit_research_answer`. The protocol state machine was not weakened or changed.

Pending deployment and replay of this contract clarification.
