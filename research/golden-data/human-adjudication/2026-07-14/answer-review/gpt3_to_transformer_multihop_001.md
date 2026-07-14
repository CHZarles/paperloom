# Blind Answer Review: gpt3_to_transformer_multihop_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Using only the available papers, build the two-hop architecture chain from GPT-3 through GPT-2 to Attention Is All You Need. State what each hop establishes and do not invent a direct GPT-3-to-Transformer citation.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A
Declared outcome: `answered`

The two-hop chain is supported directly by the GPT-3 and GPT-2 papers in the corpus, with no fabricated direct GPT-3→Transformer architecture citation needed.

**Hop 1 — GPT-3 → GPT-2 (architecture inheritance)**
The GPT-3 paper's §2.1 "Model and Architectures" states: *"We use the same model and architecture as GPT‑2 [RWC+19], including the modified initialization, pre-normalization, and reversible tokenization described therein, with the exception that we use alternating dense and locally banded sparse attention patterns in the layers of the transformer, similar to the Sparse Transformer [CGRS19]."* The corpus citation graph records a single `uses_gpt2_style_architecture` edge from GPT-3, terminating at GPT-2. What this hop establishes: GPT-3 does not define a new backbone; it reuses the GPT-2 model and its pre‑normalization / modified‑initialization / reversible‑tokenization recipe, deviating only in swapping the attention pattern for Sparse‑Transformer‑style alternating dense/banded‑sparse attention. [1]

**Hop 2 — GPT-2 → "Attention Is All You Need" (Transformer backbone)**
The GPT-2 paper's §2.3 "Model" states: *"We use a Transformer (Vaswani et al., 2017) based architecture for our LMs. The model largely follows the details of the OpenAI GPT model (Radford et al., 2018) with a few modifications."* §2 "Approach" reinforces this: *"…self‑attention architectures like the Transformer (Vaswani et al., 2017)."* The corpus citation graph records a `uses_transformer_decoder_language_model` edge from GPT-2 to "Attention Is All You Need," and that paper's own abstract introduces *"a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely."* What this hop establishes: GPT-2's decoder‑only language model is built on the Transformer architecture introduced by Vaswani et al. (2017), and only the GPT model (Radford et al., 2018) — not the original Transformer paper — is treated as a more direct architectural antecedent. [2] [3] [4]

**Why no direct GPT-3 → Transformer architecture citation is invoked**
GPT-3's architecture section cites GPT-2 as the source of "the same model and architecture" and cites the Sparse Transformer [CGRS19] only for the attention‑pattern modification — not Vaswani et al. (2017). The citation graph from GPT-3 confirms this: the only architecture edge from GPT-3 leads to GPT-2, not to "Attention Is All You Need." The Transformer is reached only transitively, through GPT-2's own §2.3 attribution.

Sources
[1] Language Models are Few-Shot Learners, 2.1 Model and Architectures, p. 8
[2] Language Models are Unsupervised Multitask Learners, 2.3. Model, p. 4
[3] Language Models are Unsupervised Multitask Learners, 2. Approach, p. 2
[4] Attention Is All You Need, Abstract, p. 1

### Evidence cited by Answer A
#### A1. Language Models are Few-Shot Learners (p. 8, 2.1 Model and Architectures)

We use the same model and architecture as $\mathrm { G P T } { - } 2 \ \mathrm { [ R W C ^ { + } 1 9 ] }$ , including the modified initialization, pre-normalization, and reversible tokenization described therein, with the exception that we use alternating dense and locally banded sparse attention patterns in the layers of the transformer, similar to the Sparse Transformer [CGRS19]. To study the dependence of ML performance on model size, we train 8 different sizes of model, ranging over three orders of magnitude from 125 million parameters to 175 billion parameters, with the last being the model we call GPT-3. Previous work $[ \mathrm { K M H ^ { + } 2 0 } ]$ suggests that with enough training data, scaling of validation loss should be approximately a smooth power law as a function of size; training models of many different sizes allows us to test this hypothesis both for validation loss and for downstream language tasks.

#### A2. Language Models are Unsupervised Multitask Learners (p. 4, 2.3. Model)

We use a Transformer (Vaswani et al., 2017) based architecture for our LMs. The model largely follows the details of the OpenAI GPT model (Radford et al., 2018) with a few modifications. Layer normalization (Ba et al., 2016) was moved to the input of each sub-block, similar to a pre-activation residual network (He et al., 2016) and an additional layer normalization was added after the final selfattention block. A modified initialization which accounts for the accumulation on the residual path with model depth is used. We scale the weights of residual layers at initialization by a factor of $1 / \hat { \sqrt { N } }$ where N is the number of residual layers. The vocabulary is expanded to 50,257. We also increase the context size from 512 to 1024 tokens and a larger batchsize of 512 is used.

#### A3. Language Models are Unsupervised Multitask Learners (p. 2, 2. Approach)

This approach allows for tractable sampling from and estimation of $p ( x )$ as well as any conditionals of the form $p ( s _ { n - k } , . . . , s _ { n } \vert s _ { 1 } , . . . , s _ { n - k - 1 } )$ . In recent years, there have been significant improvements in the expressiveness of models that can compute these conditional probabilities, such as self-attention architectures like the Transformer (Vaswani et al., 2017).

#### A4. Attention Is All You Need (p. 1, Abstract)

The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely. Experiments on two machine translation tasks show these models to be superior in quality while being more parallelizable and requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014 Englishto-German translation task, improving over the existing best results, including ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5 days on eight GPUs, a small fraction of the training costs of the best models from the literature. We show that the Transformer generalizes well to other tasks by applying it successfully to English constituency parsing both with large and limited training data.

## Answer B
Declared outcome: `answered`

**Two-hop architecture chain (available papers only):**

1. **GPT-3 → GPT-2**
   This hop establishes that GPT-3 uses GPT-2’s model/architecture as its architectural basis: GPT-3 says it uses “the same model and architecture as GPT-2,” including GPT-2’s modified initialization, pre-normalization, and reversible tokenization, while adding alternating dense and locally banded sparse attention patterns in transformer layers. [1]

2. **GPT-2 → *Attention Is All You Need***
   This hop establishes that GPT-2’s language-model architecture is Transformer-based: GPT-2 states, “We use a Transformer (Vaswani et al., 2017) based architecture for our LMs.” [2] The cited Transformer paper is the one that proposes “a new simple network architecture, the Transformer, based solely on attention mechanisms,” [3] and describes it as using stacked self-attention and point-wise fully connected layers. [4]

**Resulting chain:**
**GPT-3** → uses GPT-2-style architecture → **GPT-2** → uses Transformer architecture from Vaswani et al. 2017 → ***Attention Is All You Need***.

I am not treating this as a direct GPT-3-to-*Attention Is All You Need* citation; the supported architecture lineage here is the two-hop chain through GPT-2.

Sources
[1] Language Models are Few-Shot Learners, 2.1 Model and Architectures, p. 8
[2] Language Models are Unsupervised Multitask Learners, 2.3. Model, p. 4
[3] Attention Is All You Need, Abstract, p. 1
[4] Attention Is All You Need, 3 Model Architecture, p. 3

### Evidence cited by Answer B
#### B1. Language Models are Few-Shot Learners (p. 8, 2.1 Model and Architectures)

We use the same model and architecture as $\mathrm { G P T } { - } 2 \ \mathrm { [ R W C ^ { + } 1 9 ] }$ , including the modified initialization, pre-normalization, and reversible tokenization described therein, with the exception that we use alternating dense and locally banded sparse attention patterns in the layers of the transformer, similar to the Sparse Transformer [CGRS19]. To study the dependence of ML performance on model size, we train 8 different sizes of model, ranging over three orders of magnitude from 125 million parameters to 175 billion parameters, with the last being the model we call GPT-3. Previous work $[ \mathrm { K M H ^ { + } 2 0 } ]$ suggests that with enough training data, scaling of validation loss should be approximately a smooth power law as a function of size; training models of many different sizes allows us to test this hypothesis both for validation loss and for downstream language tasks.

#### B2. Language Models are Unsupervised Multitask Learners (p. 4, 2.3. Model)

We use a Transformer (Vaswani et al., 2017) based architecture for our LMs. The model largely follows the details of the OpenAI GPT model (Radford et al., 2018) with a few modifications. Layer normalization (Ba et al., 2016) was moved to the input of each sub-block, similar to a pre-activation residual network (He et al., 2016) and an additional layer normalization was added after the final selfattention block. A modified initialization which accounts for the accumulation on the residual path with model depth is used. We scale the weights of residual layers at initialization by a factor of $1 / \hat { \sqrt { N } }$ where N is the number of residual layers. The vocabulary is expanded to 50,257. We also increase the context size from 512 to 1024 tokens and a larger batchsize of 512 is used.

#### B3. Attention Is All You Need (p. 1, Abstract)

The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely. Experiments on two machine translation tasks show these models to be superior in quality while being more parallelizable and requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014 Englishto-German translation task, improving over the existing best results, including ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5 days on eight GPUs, a small fraction of the training costs of the best models from the literature. We show that the Transformer generalizes well to other tasks by applying it successfully to English constituency parsing both with large and limited training data.

#### B4. Attention Is All You Need (p. 3, 3 Model Architecture)

The Transformer follows this overall architecture using stacked self-attention and point-wise, fully connected layers for both the encoder and decoder, shown in the left and right halves of Figure 1, respectively.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
