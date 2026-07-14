# Blind Answer Review: transformer_bert_confirmation_followup_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Compare the original Transformer with BERT.
- **assistant**: Should I compare architecture role, directionality, and training objective?
- **user**: Yes, that is my choice.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A

Declared outcome: `answered`

### Short comparison

| Axis | Original Transformer (*Attention Is All You Need*) | BERT |
|---|---|---|
| **Architecture role** | Introduced the Transformer as a sequence-transduction architecture “based solely on attention mechanisms,” removing recurrence and convolutions; the paper frames it around encoder–decoder sequence transduction, especially machine translation. [1] | Uses the Transformer as a language-representation model: BERT is “Bidirectional Encoder Representations from Transformers” and is designed so a pretrained model can be fine-tuned for tasks such as question answering and language inference with little task-specific architectural change. [2] |
| **Encoder/decoder structure** | The original Transformer has an encoder that maps an input sequence to continuous representations and a decoder that generates the output sequence one symbol at a time. [3] | BERT is a “multi-layer bidirectional Transformer encoder” based on the original Transformer implementation. [4] |
| **Directionality / attention** | In the encoder, each position can attend to all positions in the previous encoder layer. [5] In the decoder, self-attention is masked so positions cannot attend to subsequent positions, preserving autoregressive prediction. [6] | BERT conditions on both left and right context in all layers, producing deep bidirectional representations. [2] Its masked language-model objective lets representations fuse left and right context, enabling pretraining of a deep bidirectional Transformer. [7] |
| **Training objective / use case** | The original paper presents the Transformer for sequence transduction and reports machine-translation experiments. [1] Its decoder is autoregressive, consuming previously generated symbols when generating the next output symbol. [3] | BERT pretrains on masked language modeling: some input tokens are masked and the model predicts their original vocabulary IDs from context. [7] It also uses next sentence prediction, where sentence B is either the true next sentence or a random corpus sentence. [8] |
| **Model scale described in paper** | The base Transformer described in the architecture section uses stacks of **N = 6** encoder layers and **N = 6** decoder layers. [9] [6] | The BERT paper reports **BERT_BASE** with 12 layers, hidden size 768, 12 attention heads, 110M parameters, and **BERT_LARGE** with 24 layers, hidden size 1024, 16 heads, 340M parameters. [10] |

### Bottom line

The original Transformer is the general attention-only encoder–decoder architecture for sequence transduction, with an autoregressive decoder for generating outputs. BERT reuses the Transformer encoder side as a bidirectional representation learner, pretraining it with masked language modeling and next sentence prediction so it can be fine-tuned across downstream language-understanding tasks. [1] [3] [4] [7] [8]

Sources
[1] Attention Is All You Need, Abstract, p. 1
[2] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, Abstract, p. 1
[3] Attention Is All You Need, 3 Model Architecture, p. 2
[4] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[5] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[6] Attention Is All You Need, 3.1 Encoder and Decoder Stacks, p. 3
[7] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 1 Introduction, p. 1
[8] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4
[9] Attention Is All You Need, 3.1 Encoder and Decoder Stacks, p. 3
[10] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3

### Evidence cited by Answer A

#### A1. Attention Is All You Need (p. 1, Abstract)

The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely. Experiments on two machine translation tasks show these models to be superior in quality while being more parallelizable and requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014 Englishto-German translation task, improving over the existing best results, including ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5 days on eight GPUs, a small fraction of the training costs of the best models from the literature. We show that the Transformer generalizes well to other tasks by applying it successfully to English constituency parsing both with large and limited training data.

#### A2. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, Abstract)

We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. Unlike recent language representation models (Peters et al., 2018a; Radford et al., 2018), BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers. As a result, the pre-trained BERT model can be finetuned with just one additional output layer to create state-of-the-art models for a wide range of tasks, such as question answering and language inference, without substantial taskspecific architecture modifications.

#### A3. Attention Is All You Need (p. 2, 3 Model Architecture)

Most competitive neural sequence transduction models have an encoder-decoder structure [5, 2, 35]. Here, the encoder maps an input sequence of symbol representations $( x _ { 1 } , . . . , x _ { n } )$ to a sequence of continuous representations $\textbf { z } = ~ ( z _ { 1 } , . . . , z _ { n } )$ . Given z, the decoder then generates an output sequence $\left( y _ { 1 } , . . . , y _ { m } \right)$ of symbols one element at a time. At each step the model is auto-regressive [10], consuming the previously generated symbols as additional input when generating the next.

#### A4. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### A5. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• The encoder contains self-attention layers. In a self-attention layer all of the keys, values and queries come from the same place, in this case, the output of the previous layer in the encoder. Each position in the encoder can attend to all positions in the previous layer of the encoder.

#### A6. Attention Is All You Need (p. 3, 3.1 Encoder and Decoder Stacks)

Decoder: The decoder is also composed of a stack of $N = 6$ identical layers. In addition to the two sub-layers in each encoder layer, the decoder inserts a third sub-layer, which performs multi-head attention over the output of the encoder stack. Similar to the encoder, we employ residual connections around each of the sub-layers, followed by layer normalization. We also modify the self-attention sub-layer in the decoder stack to prevent positions from attending to subsequent positions. This masking, combined with fact that the output embeddings are offset by one position, ensures that the predictions for position i can depend only on the known outputs at positions less than i.

#### A7. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, 1 Introduction)

In this paper, we improve the fine-tuning based approaches by proposing BERT: Bidirectional Encoder Representations from Transformers. BERT alleviates the previously mentioned unidirectionality constraint by using a “masked language model” (MLM) pre-training objective, inspired by the Cloze task (Taylor, 1953). The masked language model randomly masks some of the tokens from the input, and the objective is to predict the original vocabulary id of the masked word based only on its context. Unlike left-toright language model pre-training, the MLM objective enables the representation to fuse the left and the right context, which allows us to pretrain a deep bidirectional Transformer. In addition to the masked language model, we also use a “next sentence prediction” task that jointly pretrains text-pair representations. The contributions of our paper are as follows:

#### A8. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

Task #2: Next Sentence Prediction (NSP) Many important downstream tasks such as Question Answering (QA) and Natural Language Inference (NLI) are based on understanding the relationship between two sentences, which is not directly captured by language modeling. In order to train a model that understands sentence relationships, we pre-train for a binarized next sentence prediction task that can be trivially generated from any monolingual corpus. Specifically, when choosing the sentences A and B for each pretraining example, 50% of the time B is the actual next sentence that follows A (labeled as IsNext), and 50% of the time it is a random sentence from the corpus (labeled as NotNext). As we show in Figure 1, C is used for next sentence prediction (NSP).<sup>5</sup> Despite its simplicity, we demonstrate in Section 5.1 that pre-training towards this task is very beneficial to both QA and NLI. 6

#### A9. Attention Is All You Need (p. 3, 3.1 Encoder and Decoder Stacks)

Encoder: The encoder is composed of a stack of $N = 6$ identical layers. Each layer has two sub-layers. The first is a multi-head self-attention mechanism, and the second is a simple, positionwise fully connected feed-forward network. We employ a residual connection [11] around each of the two sub-layers, followed by layer normalization [1]. That is, the output of each sub-layer is LayerNorm(x + Sublayer(x)), where Sublayer(x) is the function implemented by the sub-layer itself. To facilitate these residual connections, all sub-layers in the model, as well as the embedding layers, produce outputs of dimension $d _ { \mathrm { m o d e l } } = 5 1 2$

#### A10. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

In this work, we denote the number of layers (i.e., Transformer blocks) as L, the hidden size as H, and the number of self-attention heads as A.<sup>3</sup> We primarily report results on two model sizes: BERT<sub>BASE</sub> (L=12, H=768, A=12, Total Parameters=110M) and BERT<sub>LARGE</sub> (L=24, H=1024, A=16, Total Parameters=340M).

## Answer B

Declared outcome: `answered`

# Comparing the Original Transformer and BERT

Both papers are in the corpus: the original Transformer ("Attention Is All You Need," Vaswani et al., NeurIPS 2017) and BERT (Devlin et al., NAACL 2019). The comparison below is organized along the three dimensions you chose: architecture role, directionality, and training objective.

## 1. Architecture role

**Original Transformer — encoder–decoder for sequence transduction.**
The model has an encoder–decoder structure: the encoder maps an input sequence of symbols to continuous representations, and the decoder generates an output sequence one symbol at a time, consuming the previously generated symbols as additional input. [1] Concretely, "The Transformer follows this overall architecture using stacked self-attention and point-wise, fully connected layers for both the encoder and decoder." [2] Each side is a stack of N = 6 identical layers, and the decoder inserts a third sub-layer (in addition to the encoder's two) that performs multi-head attention over the encoder output. [3]

**BERT — encoder only.**
"BERT's model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017)." [4] No decoder is used; only the encoder half is reused. A distinctive design choice is that "there is minimal difference between the pre-trained architecture and the final downstream architecture." [5]

So the original Transformer is a general sequence-transduction backbone with both halves trained, while BERT reuses just the encoder half as a representation model for transfer learning.

## 2. Directionality

**Original Transformer — autoregressive decoder over a bidirectional encoder.**
- Encoder self-attention is unconstrained: "Each position in the encoder can attend to all positions in the previous layer of the encoder." [6]
- The decoder has a second attention sub-layer, "encoder–decoder attention," where "queries come from the previous decoder layer, and the memory keys and values come from the output of the encoder … [allowing] every position in the decoder to attend over all positions in the input sequence." [7]
- Decoder self-attention is explicitly restricted: positions are prevented from attending to subsequent positions by masking illegal connections in the softmax, "to preserve the auto-regressive property." [8] Combined with the one-position offset of output embeddings, "the predictions for position i can depend only on the known outputs at positions less than i." [3]

The original Transformer is therefore left-to-right (unidirectional) at decoding time, while the encoder itself is bidirectional.

**BERT — bidirectional in all layers.**
BERT is "designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers." [9] The motivation is precisely to remove the unidirectionality constraint of prior representation models: "BERT alleviates the previously mentioned unidirectionality constraint by using a 'masked language model' (MLM) pre-training objective." [10] The authors note that "standard conditional language models can only be trained left-to-right or right-to-left, since bidirectional conditioning would allow each word to indirectly 'see itself'" under a naive conditional LM formulation. [11]

In short, the original Transformer is directional at decode time by design, while BERT removes directionality entirely inside the encoder.

## 3. Training objective

**Original Transformer — supervised sequence-transduction / machine translation.**
The model is trained end-to-end on parallel corpora for translation. The training data are WMT 2014 English–German (about 4.5 million sentence pairs, ~37,000-token BPE vocabulary) and WMT 2014 English–French (36M sentences, 32,000 word-piece vocabulary). [12] At each step the decoder is auto-regressive: it "consumes the previously generated symbols as additional input when generating the next." [1] Output probabilities are produced by "the usual learned linear transformation and softmax function to convert the decoder output to predicted next-token probabilities," [13] trained with cross-entropy against the reference translation. It is a task-specific supervised model, not a pre-trained representation.

**BERT — self-supervised pre-training with two objectives.**
BERT is pre-trained on unlabeled text with two tasks, then fine-tuned downstream.

- **Masked Language Model (MLM):** "we simply mask some percentage of the input tokens at random, and then predict those masked tokens." [14] 15% of WordPiece tokens in each sequence are chosen at random; the final hidden vectors at those positions are fed into an output softmax over the vocabulary. The model predicts only the masked tokens, not the entire input. [14] This objective is what enables bidirectional conditioning: "the MLM objective enables the representation to fuse the left and the right context." [10]
- **Next Sentence Prediction (NSP):** "we pre-train for a binarized next sentence prediction task … 50% of the time B is the actual next sentence that follows A (labeled as IsNext), and 50% of the time it is a random sentence from the corpus (labeled as NotNext)." [15] This trains sentence-pair relationships useful for QA and NLI.

The contrast: the original Transformer optimizes supervised per-token next-token cross-entropy on parallel translation data with an autoregressive decoder, whereas BERT optimizes a self-supervised fill-in-the-blank objective (MLM) plus a sentence-relation objective (NSP) over monolingual text, using only the encoder stack.

## Summary

| Dimension | Original Transformer (2017) | BERT (2018) |
|---|---|---|
| Architecture role | Encoder–decoder for sequence transduction; both halves trained [1] [2] | Encoder-only Transformer stack, reused directly for downstream tasks [4] [5] |
| Directionality | Decoder self-attention masked → autoregressive (left-to-right); encoder self-attention unconstrained [3] [8] [6] | Bidirectional in all layers, fusing left and right context via MLM [9] [10] |
| Training objective | Supervised machine translation (WMT 2014 En–De / En–Fr) with next-token cross-entropy via softmax over decoder output [12] [13] | Self-supervised pre-training with MLM (mask 15%, predict masked tokens) and NSP (binary IsNext/NotNext) [14] [15] |

BERT can be viewed as taking the encoder half of the original Transformer and pre-training it with objectives specifically chosen to make those encoder representations bidirectional and reusable across tasks.

Sources
[1] Attention Is All You Need, 3 Model Architecture, p. 2
[2] Attention Is All You Need, 3 Model Architecture, p. 3
[3] Attention Is All You Need, 3.1 Encoder and Decoder Stacks, p. 3
[4] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[5] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[6] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[7] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[8] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[9] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, Abstract, p. 1
[10] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 1 Introduction, p. 1
[11] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4
[12] Attention Is All You Need, 5.1 Training Data and Batching, p. 7
[13] Attention Is All You Need, 3.4 Embeddings and Softmax, p. 5
[14] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4
[15] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4

### Evidence cited by Answer B

#### B1. Attention Is All You Need (p. 2, 3 Model Architecture)

Most competitive neural sequence transduction models have an encoder-decoder structure [5, 2, 35]. Here, the encoder maps an input sequence of symbol representations $( x _ { 1 } , . . . , x _ { n } )$ to a sequence of continuous representations $\textbf { z } = ~ ( z _ { 1 } , . . . , z _ { n } )$ . Given z, the decoder then generates an output sequence $\left( y _ { 1 } , . . . , y _ { m } \right)$ of symbols one element at a time. At each step the model is auto-regressive [10], consuming the previously generated symbols as additional input when generating the next.

#### B2. Attention Is All You Need (p. 3, 3 Model Architecture)

The Transformer follows this overall architecture using stacked self-attention and point-wise, fully connected layers for both the encoder and decoder, shown in the left and right halves of Figure 1, respectively.

#### B3. Attention Is All You Need (p. 3, 3.1 Encoder and Decoder Stacks)

Decoder: The decoder is also composed of a stack of $N = 6$ identical layers. In addition to the two sub-layers in each encoder layer, the decoder inserts a third sub-layer, which performs multi-head attention over the output of the encoder stack. Similar to the encoder, we employ residual connections around each of the sub-layers, followed by layer normalization. We also modify the self-attention sub-layer in the decoder stack to prevent positions from attending to subsequent positions. This masking, combined with fact that the output embeddings are offset by one position, ensures that the predictions for position i can depend only on the known outputs at positions less than i.

#### B4. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### B5. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

A distinctive feature of BERT is its unified architecture across different tasks. There is minimal difference between the pre-trained architecture and the final downstream architecture.

#### B6. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• The encoder contains self-attention layers. In a self-attention layer all of the keys, values and queries come from the same place, in this case, the output of the previous layer in the encoder. Each position in the encoder can attend to all positions in the previous layer of the encoder.

#### B7. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• In "encoder-decoder attention" layers, the queries come from the previous decoder layer, and the memory keys and values come from the output of the encoder. This allows every position in the decoder to attend over all positions in the input sequence. This mimics the typical encoder-decoder attention mechanisms in sequence-to-sequence models such as [38, 2, 9].

#### B8. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• Similarly, self-attention layers in the decoder allow each position in the decoder to attend to all positions in the decoder up to and including that position. We need to prevent leftward information flow in the decoder to preserve the auto-regressive property. We implement this inside of scaled dot-product attention by masking out (setting to −∞) all values in the input of the softmax which correspond to illegal connections. See Figure 2.

#### B9. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, Abstract)

We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. Unlike recent language representation models (Peters et al., 2018a; Radford et al., 2018), BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers. As a result, the pre-trained BERT model can be finetuned with just one additional output layer to create state-of-the-art models for a wide range of tasks, such as question answering and language inference, without substantial taskspecific architecture modifications.

#### B10. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, 1 Introduction)

In this paper, we improve the fine-tuning based approaches by proposing BERT: Bidirectional Encoder Representations from Transformers. BERT alleviates the previously mentioned unidirectionality constraint by using a “masked language model” (MLM) pre-training objective, inspired by the Cloze task (Taylor, 1953). The masked language model randomly masks some of the tokens from the input, and the objective is to predict the original vocabulary id of the masked word based only on its context. Unlike left-toright language model pre-training, the MLM objective enables the representation to fuse the left and the right context, which allows us to pretrain a deep bidirectional Transformer. In addition to the masked language model, we also use a “next sentence prediction” task that jointly pretrains text-pair representations. The contributions of our paper are as follows:

#### B11. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

Task #1: Masked LM Intuitively, it is reasonable to believe that a deep bidirectional model is strictly more powerful than either a left-to-right model or the shallow concatenation of a left-toright and a right-to-left model. Unfortunately, standard conditional language models can only be trained left-to-right or right-to-left, since bidirectional conditioning would allow each word to indirectly “see itself”, and the model could trivially predict the target word in a multi-layered context.

#### B12. Attention Is All You Need (p. 7, 5.1 Training Data and Batching)

We trained on the standard WMT 2014 English-German dataset consisting of about 4.5 million sentence pairs. Sentences were encoded using byte-pair encoding [3], which has a shared sourcetarget vocabulary of about 37000 tokens. For English-French, we used the significantly larger WMT 2014 English-French dataset consisting of 36M sentences and split tokens into a 32000 word-piece vocabulary [38]. Sentence pairs were batched together by approximate sequence length. Each training batch contained a set of sentence pairs containing approximately 25000 source tokens and 25000 target tokens.

#### B13. Attention Is All You Need (p. 5, 3.4 Embeddings and Softmax)

Similarly to other sequence transduction models, we use learned embeddings to convert the input tokens and output tokens to vectors of dimension $d _ { \mathrm { m o d e l } }$ . We also use the usual learned linear transformation and softmax function to convert the decoder output to predicted next-token probabilities. In our model, we share the same weight matrix between the two embedding layers and the pre-softmax linear transformation, similar to [30]. In the embedding layers, we multiply those weights by $\sqrt { d _ { \mathrm { { m o d e l } } } }$

#### B14. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

In order to train a deep bidirectional representation, we simply mask some percentage of the input tokens at random, and then predict those masked tokens. We refer to this procedure as a “masked LM” (MLM), although it is often referred to as a Cloze task in the literature (Taylor, 1953). In this case, the final hidden vectors corresponding to the mask tokens are fed into an output softmax over the vocabulary, as in a standard LM. In all of our experiments, we mask 15% of all WordPiece tokens in each sequence at random. In contrast to denoising auto-encoders (Vincent et al., 2008), we only predict the masked words rather than reconstructing the entire input.

#### B15. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

Task #2: Next Sentence Prediction (NSP) Many important downstream tasks such as Question Answering (QA) and Natural Language Inference (NLI) are based on understanding the relationship between two sentences, which is not directly captured by language modeling. In order to train a model that understands sentence relationships, we pre-train for a binarized next sentence prediction task that can be trivially generated from any monolingual corpus. Specifically, when choosing the sentences A and B for each pretraining example, 50% of the time B is the actual next sentence that follows A (labeled as IsNext), and 50% of the time it is a random sentence from the corpus (labeled as NotNext). As we show in Figure 1, C is used for next sentence prediction (NSP).<sup>5</sup> Despite its simplicity, we demonstrate in Section 5.1 that pre-training towards this task is very beneficial to both QA and NLI. 6

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
