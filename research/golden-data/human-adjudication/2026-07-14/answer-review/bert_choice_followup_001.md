# Blind Answer Review: bert_choice_followup_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Help me choose one paper to understand bidirectional Transformer encoders.
- **assistant**: Choose one: (1) Attention Is All You Need, (2) BERT, or (3) GPT-2.
- **user**: The second.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A

Declared outcome: `answered`

The best paper to read for understanding bidirectional Transformer encoders is:

**BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding** by Devlin et al. (NAACL 2019, arXiv:1810.04805) [1]

## Why BERT fits this goal

BERT's defining contribution is making the Transformer *bidirectional* — every token can attend to both its left and right context in every layer, rather than only the left context as in earlier left-to-right models like OpenAI GPT [2].

Key passages worth reading:

- **Abstract** — Defines BERT as "Bidirectional Encoder Representations from Transformers" and states it is "designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers" [1].
- **§3 Model Architecture** — Describes BERT as "a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017)" [3].
- **§3 footnote** — Clarifies the terminology: the bidirectional version is called a "Transformer encoder" while the left-context-only version is called a "Transformer decoder" [4].
- **§3 comparison to GPT** — Contrasts BERT's bidirectional self-attention against GPT's constrained (left-only) self-attention [2].
- **§1 Introduction** — Explains *how* bidirectionality is achieved at pre-training: the masked language model (MLM) objective lets the representation "fuse the left and the right context," which "allows

Sources
[1] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, Abstract, p. 1
[2] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[3] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[4] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4

### Evidence cited by Answer A

#### A1. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, Abstract)

We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. Unlike recent language representation models (Peters et al., 2018a; Radford et al., 2018), BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers. As a result, the pre-trained BERT model can be finetuned with just one additional output layer to create state-of-the-art models for a wide range of tasks, such as question answering and language inference, without substantial taskspecific architecture modifications.

#### A2. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

BERT<sub>BASE</sub> was chosen to have the same model size as OpenAI GPT for comparison purposes. Critically, however, the BERT Transformer uses bidirectional self-attention, while the GPT Transformer uses constrained self-attention where every token can only attend to context to its left.<sup>4</sup>

#### A3. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### A4. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

former is often referred to as a “Transformer encoder” while the left-context-only version is referred to as a “Transformer decoder” since it can be used for text generation.

## Answer B

Declared outcome: `answered`

Great choice: read **BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding** by Jacob Devlin and Ming-Wei Chang (2018, NAACL; arXiv:1810.04805).

Why this is the right paper for your goal:

- BERT is explicitly about **Bidirectional Encoder Representations from Transformers** and is designed to pretrain representations that condition on both left and right context in all layers. [1]
- Its architecture is a **multi-layer bidirectional Transformer encoder**, so it directly targets your interest in bidirectional Transformer encoders. [2]
- The key mechanism to focus on is **masked language modeling**: BERT masks input tokens and trains the model to predict them from context, which lets the representation use both left and right context. [3]

Suggested reading path:

1. **Abstract** — understand the high-level idea of bidirectional pretraining.
2. **Introduction** — focus on why left-to-right language modeling is limited and why MLM enables bidirectionality.
3. **Section 3: BERT** — read the model architecture description.
4. **Section 3.1: Pre-training BERT** — read masked language modeling carefully; this is the core idea.
5. Skim the experiments only after you understand the encoder and pretraining setup.

A simple takeaway to keep in mind while reading: **BERT is not just “a Transformer”; it is a Transformer encoder trained so each token representation can use context from both directions.**

Sources
[1] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, Abstract, p. 1
[2] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[3] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 1 Introduction, p. 1

### Evidence cited by Answer B

#### B1. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, Abstract)

We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. Unlike recent language representation models (Peters et al., 2018a; Radford et al., 2018), BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers. As a result, the pre-trained BERT model can be finetuned with just one additional output layer to create state-of-the-art models for a wide range of tasks, such as question answering and language inference, without substantial taskspecific architecture modifications.

#### B2. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### B3. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, 1 Introduction)

In this paper, we improve the fine-tuning based approaches by proposing BERT: Bidirectional Encoder Representations from Transformers. BERT alleviates the previously mentioned unidirectionality constraint by using a “masked language model” (MLM) pre-training objective, inspired by the Cloze task (Taylor, 1953). The masked language model randomly masks some of the tokens from the input, and the objective is to predict the original vocabulary id of the masked word based only on its context. Unlike left-toright language model pre-training, the MLM objective enables the representation to fuse the left and the right context, which allows us to pretrain a deep bidirectional Transformer. In addition to the masked language model, we also use a “next sentence prediction” task that jointly pretrains text-pair representations. The contributions of our paper are as follows:

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
