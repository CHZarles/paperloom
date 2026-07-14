# Blind Answer Review: bert_vs_transformer_comparison_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Compare the original Transformer and BERT on architecture role, directionality, and training objective.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A
Declared outcome: `answered`

| Dimension | Original Transformer (*Attention Is All You Need*) | BERT |
|---|---|---|
| **Architecture role** | A general **sequence transduction** architecture that replaces recurrence/convolutions with attention; it uses an **encoder–decoder** structure for tasks such as machine translation. [1] The encoder maps the input sequence to continuous representations, and the decoder generates the output sequence one symbol at a time. [2] | A **language representation** model built from a **multi-layer bidirectional Transformer encoder** based on the original Transformer implementation. [3] It is designed so the pretrained model can be fine-tuned with one additional output layer for tasks such as question answering and language inference. [4] |
| **Directionality** | Mixed by component: the **encoder** self-attention lets each position attend to all positions in the previous encoder layer. [5] The **decoder** is autoregressive: it attends only up to the current position, using masking to block illegal future-position connections. [6] | Deeply **bidirectional**: BERT jointly conditions on both left and right context in all layers. [4] Its MLM objective lets representations fuse left and right context, unlike left-to-right language-model pretraining. [7] |
| **Training objective** | For generation, the decoder output is converted into **predicted next-token probabilities**, and the model generates outputs autoregressively one element at a time. [8] [2] | Pretraining uses **masked language modeling**: randomly mask input tokens and predict the original vocabulary id from context. [7] BERT also uses **next sentence prediction**, a binary task where sentence B is either the true next sentence or a random sentence. [9] |

**Bottom line:** the original Transformer is an encoder–decoder sequence transduction model with autoregressive decoding, whereas BERT repurposes the Transformer encoder as a bidirectional pretrained representation model trained with MLM plus NSP.

Sources
[1] Attention Is All You Need, Abstract, p. 1
[2] Attention Is All You Need, 3 Model Architecture, p. 2
[3] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[4] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, Abstract, p. 1
[5] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[6] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5
[7] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 1 Introduction, p. 1
[8] Attention Is All You Need, 3.4 Embeddings and Softmax, p. 5
[9] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3.1 Pre-training BERT, p. 4

### Evidence cited by Answer A
#### A1. Attention Is All You Need (p. 1, Abstract)

The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely. Experiments on two machine translation tasks show these models to be superior in quality while being more parallelizable and requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014 Englishto-German translation task, improving over the existing best results, including ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5 days on eight GPUs, a small fraction of the training costs of the best models from the literature. We show that the Transformer generalizes well to other tasks by applying it successfully to English constituency parsing both with large and limited training data.

#### A2. Attention Is All You Need (p. 2, 3 Model Architecture)

Most competitive neural sequence transduction models have an encoder-decoder structure [5, 2, 35]. Here, the encoder maps an input sequence of symbol representations $( x _ { 1 } , . . . , x _ { n } )$ to a sequence of continuous representations $\textbf { z } = ~ ( z _ { 1 } , . . . , z _ { n } )$ . Given z, the decoder then generates an output sequence $\left( y _ { 1 } , . . . , y _ { m } \right)$ of symbols one element at a time. At each step the model is auto-regressive [10], consuming the previously generated symbols as additional input when generating the next.

#### A3. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### A4. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, Abstract)

We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers. Unlike recent language representation models (Peters et al., 2018a; Radford et al., 2018), BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers. As a result, the pre-trained BERT model can be finetuned with just one additional output layer to create state-of-the-art models for a wide range of tasks, such as question answering and language inference, without substantial taskspecific architecture modifications.

#### A5. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• The encoder contains self-attention layers. In a self-attention layer all of the keys, values and queries come from the same place, in this case, the output of the previous layer in the encoder. Each position in the encoder can attend to all positions in the previous layer of the encoder.

#### A6. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• Similarly, self-attention layers in the decoder allow each position in the decoder to attend to all positions in the decoder up to and including that position. We need to prevent leftward information flow in the decoder to preserve the auto-regressive property. We implement this inside of scaled dot-product attention by masking out (setting to −∞) all values in the input of the softmax which correspond to illegal connections. See Figure 2.

#### A7. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, 1 Introduction)

In this paper, we improve the fine-tuning based approaches by proposing BERT: Bidirectional Encoder Representations from Transformers. BERT alleviates the previously mentioned unidirectionality constraint by using a “masked language model” (MLM) pre-training objective, inspired by the Cloze task (Taylor, 1953). The masked language model randomly masks some of the tokens from the input, and the objective is to predict the original vocabulary id of the masked word based only on its context. Unlike left-toright language model pre-training, the MLM objective enables the representation to fuse the left and the right context, which allows us to pretrain a deep bidirectional Transformer. In addition to the masked language model, we also use a “next sentence prediction” task that jointly pretrains text-pair representations. The contributions of our paper are as follows:

#### A8. Attention Is All You Need (p. 5, 3.4 Embeddings and Softmax)

Similarly to other sequence transduction models, we use learned embeddings to convert the input tokens and output tokens to vectors of dimension $d _ { \mathrm { m o d e l } }$ . We also use the usual learned linear transformation and softmax function to convert the decoder output to predicted next-token probabilities. In our model, we share the same weight matrix between the two embedding layers and the pre-softmax linear transformation, similar to [30]. In the embedding layers, we multiply those weights by $\sqrt { d _ { \mathrm { { m o d e l } } } }$

#### A9. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 4, 3.1 Pre-training BERT)

Task #2: Next Sentence Prediction (NSP) Many important downstream tasks such as Question Answering (QA) and Natural Language Inference (NLI) are based on understanding the relationship between two sentences, which is not directly captured by language modeling. In order to train a model that understands sentence relationships, we pre-train for a binarized next sentence prediction task that can be trivially generated from any monolingual corpus. Specifically, when choosing the sentences A and B for each pretraining example, 50% of the time B is the actual next sentence that follows A (labeled as IsNext), and 50% of the time it is a random sentence from the corpus (labeled as NotNext). As we show in Figure 1, C is used for next sentence prediction (NSP).<sup>5</sup> Despite its simplicity, we demonstrate in Section 5.1 that pre-training towards this task is very beneficial to both QA and NLI. 6

## Answer B
Declared outcome: `answered`

## Original Transformer vs. BERT

| Dimension | Original Transformer (Vaswani et al., 2017) | BERT (Devlin et al., 2018) |
|---|---|---|
| **Architecture role** | Full **encoder–decoder** sequence-transduction model. The encoder maps an input sequence $(x_1,\dots,x_n)$ to continuous representations $\mathbf{z}=(z_1,\dots,z_n)$, and the decoder then generates an output sequence $(y_1,\dots,y_m)$ one element at a time. Both halves are stacks of self-attention and point-wise fully connected layers. | **Encoder-only**. "BERT's model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017)." No decoder is used; the model is a pre-trained representation that is then fine-tuned with task-specific output layers. |
| **Directionality** | Encoder self-attention is fully bidirectional ("each position in the encoder can attend to all positions"). Decoder self-attention is **masked** so positions can only attend up to and including themselves, preserving the auto-regressive property: "predictions for position $i$ can depend only on the known outputs at positions less than $i$." Decoder also has cross-attention over all encoder positions. | Strictly **deeply bidirectional**. "BERT is designed to pretrain deep bidirectional representations from unlabeled text by jointly conditioning on both left and right context in all layers." No autoregressive masking — the input is processed as a whole so each position can attend to all positions, with bidirectionality achieved via the MLM objective. |
| **Training objective** | **Supervised sequence-to-sequence translation**. Trained end-to-end on labeled parallel data — WMT 2014 English–German (~4.5 M sentence pairs) and English–French (36 M pairs) — to maximize likelihood of the target translation given the source. No pre-training stage; the supervised translation task *is* the training. | **Self-supervised pre-training on unlabeled text**, then supervised fine-tuning. Two pre-training objectives: (1) **Masked Language Model (MLM)** — 15% of WordPiece tokens are masked and the model predicts the original vocabulary id of each masked token from its full left-and-right context (80% replaced by `[MASK]`, 10% random, 10% kept, to reduce pre-train/fine-tune mismatch); (2) **Next Sentence Prediction (NSP)** — a binary classifier predicting whether sentence B is the actual next sentence after A. After pre-training, all parameters are fine-tuned on labeled downstream-task data. |

### Key takeaways grounded in the papers

- The original Transformer was introduced as "the first sequence transduction model based entirely on attention, replacing the recurrent layers most commonly used in encoder-decoder architectures with multi-headed self-attention" [1].
- BERT explicitly reuses only the Transformer **encoder**, dropping the decoder, and is built as a pre-trained language representation model whose pre-trained and downstream architectures are nearly identical [2].
- BERT's authors single out unidirectionality as the main limitation of prior pre-trained models (e.g., OpenAI GPT, "a left-to-right architecture, where every token can only attend to previous tokens"), and design MLM specifically so "the representation [can] fuse the left and the right context" in a deep bidirectional Transformer [3].
- In the Transformer's decoder, left-to-right (autoregressive) behavior is enforced by masking: "We implement this inside of scaled dot-product attention by masking out (setting to −∞) all values in the input of the softmax which correspond to illegal connections" — i.e., preventing leftward information flow [4]. BERT removes this masking entirely so each token sees the entire surrounding context.

Sources
[1] Attention Is All You Need, 7 Conclusion, p. 10
[2] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 3 BERT, p. 3
[3] BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding, 1 Introduction, p. 1
[4] Attention Is All You Need, 3.2.3 Applications of Attention in our Model, p. 5

### Evidence cited by Answer B
#### B1. Attention Is All You Need (p. 10, 7 Conclusion)

In this work, we presented the Transformer, the first sequence transduction model based entirely on attention, replacing the recurrent layers most commonly used in encoder-decoder architectures with multi-headed self-attention.

#### B2. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 3, 3 BERT)

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

#### B3. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (p. 1, 1 Introduction)

In this paper, we improve the fine-tuning based approaches by proposing BERT: Bidirectional Encoder Representations from Transformers. BERT alleviates the previously mentioned unidirectionality constraint by using a “masked language model” (MLM) pre-training objective, inspired by the Cloze task (Taylor, 1953). The masked language model randomly masks some of the tokens from the input, and the objective is to predict the original vocabulary id of the masked word based only on its context. Unlike left-toright language model pre-training, the MLM objective enables the representation to fuse the left and the right context, which allows us to pretrain a deep bidirectional Transformer. In addition to the masked language model, we also use a “next sentence prediction” task that jointly pretrains text-pair representations. The contributions of our paper are as follows:

#### B4. Attention Is All You Need (p. 5, 3.2.3 Applications of Attention in our Model)

• Similarly, self-attention layers in the decoder allow each position in the decoder to attend to all positions in the decoder up to and including that position. We need to prevent leftward information flow in the decoder to preserve the auto-regressive property. We implement this inside of scaled dot-product attention by masking out (setting to −∞) all values in the input of the softmax which correspond to illegal connections. See Figure 2.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
