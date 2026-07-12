# Human Calibration Review Guide

Use this guide when annotating `research/golden-data/human-labels.yaml`.

You do not need to understand the 22 harness paradigms, stage plans, tool calls, private field names,
or `answer_type`. Judge only the user-visible response against the fixed corpus and the reference
knowledge below.

## Evaluation Scope

- Treat the five-paper Golden pack as the complete authorized corpus.
- Do not use external knowledge to repair or condemn an answer.
- Review the final answer and the cited evidence excerpts. Do not review hidden reasoning or stage
  order.
- Equivalent passages are acceptable. Exact Golden anchor IDs are not required.
- A stylistic flaw is not a failure unless it materially harms correctness, completeness, or clarity.
- Every material factual claim, including an extra claim not requested by the user, must be supported.

## Dimension Rules

### Decision

Judge whether the harness chose the correct action: answer, clarify, or state a corpus boundary.

- Pass when the action is appropriate for the request and available corpus.
- Fail when it answers an unresolved ambiguity, asks unnecessary clarification, or presents unavailable
  information as known.

### Task Fulfillment

Judge whether the response correctly and completely carries out the chosen action.

- For an answer, check factual correctness, requested coverage, and user constraints.
- For clarification, check that the question would actually resolve the ambiguity.
- For a corpus-boundary response, check that it explicitly identifies what cannot be verified and does
  not substitute predecessor information for the missing answer.

### Grounding

Judge whether cited passages support every material research claim.

- Pass when the cited text semantically entails or directly supports the claims.
- Fail when an important claim lacks support, contradicts its citation, or relies only on irrelevant
  metadata.
- Use `not_applicable` only when the response makes no research claim, such as a pure clarification.

### Overall

Set `overall: pass` only when `decision`, `task_fulfillment`, and every applicable grounding judgment
pass. Otherwise set `overall: fail`.

## Fixed Corpus

The authorized corpus contains exactly these papers:

1. *Attention Is All You Need* (2017)
2. *Adam: A Method for Stochastic Optimization* (2014)
3. *BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding* (2018)
4. *Language Models are Unsupervised Multitask Learners* / GPT-2 (2019)
5. *Language Models are Few-Shot Learners* / GPT-3 (2020)

It contains no GPT-5 paper, system card, or technical report.

## Case Reference Knowledge

### `bert_transformer_role_001`

Question: Does BERT use the Transformer as an encoder or decoder?

Reference facts:

- BERT is encoder-only at the Transformer architecture level.
- The BERT paper calls its architecture a "multi-layer bidirectional Transformer encoder."
- BERT does not instantiate the Transformer decoder stack used for autoregressive sequence
  generation.
- A downstream classification or output layer is not a Transformer decoder.

The essential answer is that BERT uses a bidirectional Transformer encoder, not a Transformer
decoder. Extra architectural details are acceptable only when accurate and grounded.

### `bert_vs_transformer_comparison_001`

Question: Compare the original Transformer and BERT on architecture role, directionality, and training
objective.

Reference comparison:

| Axis | Original Transformer | BERT |
|---|---|---|
| Architecture role | Full encoder-decoder sequence-transduction architecture | Encoder-only representation model |
| Directionality | Encoder self-attention can use the full source sequence; decoder self-attention is causally masked for autoregressive generation | Bidirectional encoder self-attention over left and right context |
| Training objective | Supervised sequence-to-sequence training, presented primarily for machine translation, with autoregressive target prediction | Pre-training with masked language modeling and next-sentence prediction, followed by task fine-tuning |

Important nuance: it is misleading to describe the entire original Transformer as simply
"unidirectional." Its decoder is causal, while its encoder can attend across the source sequence. A
good comparison may focus on the decoder's causal generation, but it should not erase the
encoder-decoder distinction.

Common material errors include:

- Claiming BERT uses the Transformer decoder.
- Claiming the original Transformer is encoder-only.
- Claiming the entire original Transformer uses only left-to-right self-attention.
- Claiming the original Transformer was trained with BERT's masked-language-model objective.
- Omitting one of the three requested comparison axes.

### `attention_paper_ambiguous_001`

User message: "attention paper"

Why it is ambiguous:

- It could refer specifically to *Attention Is All You Need*.
- It could refer broadly to any paper about attention mechanisms.
- It does not say whether the user wants identification, summarization, recommendation, comparison,
  or another operation.
- Several papers in the corpus use Transformer attention, so silently interpreting it as a request for
  a recommendation list is not justified.

The appropriate action is a concise clarification that resolves the paper identity or requested task.
A response may offer likely options, but it should not begin a substantive paper recommendation or
research answer before the ambiguity is resolved.

### `gpt5_architecture_boundary_001`

Question: What are the architecture details of GPT-5?

Reference facts:

- The corpus stops at GPT-3 and has no source describing GPT-5.
- GPT-2 and GPT-3 passages can establish facts about GPT-2 and GPT-3 only.
- BERT and *Attention Is All You Need* provide predecessor context, not GPT-5 architecture evidence.
- GPT-5 layer count, parameter count, attention design, context window, tokenizer, training objective,
  training data, and other architecture details cannot be verified from this corpus.

An acceptable response must explicitly state the GPT-5 corpus gap and avoid inferring GPT-5 details
from predecessor papers. It may mention predecessor context only when clearly labeled as context, not
as an answer about GPT-5.

Judge user-visible meaning rather than the private `partial` versus `abstained` enum. However, a vague
sentence such as "the evidence is insufficient" is not enough unless it identifies GPT-5 and the
corpus boundary clearly.

## Materiality Rule

Use `fail` for an issue that would change a user's understanding, leave a requested part unanswered,
or present an unsupported claim as established. Do not fail for wording, formatting, verbosity, or a
different but equivalent citation passage.
