# Golden Contract Review: transformer_bert_confirmation_followup_001

## Conversation

- **user**: Compare the original Transformer with BERT.
- **assistant**: Should I compare architecture role, directionality, and training objective?
- **user**: Yes, that is my choice.

## Authored Expectation

```yaml
outcome: answered
papers:
  required:
  - attention_is_all_you_need_2017
  - bert_2018
evidence:
  required:
  - transformer_encoder_decoder_architecture
  - bert_transformer_encoder_background
  - bert_masked_lm_pretraining
claims:
- text: The comparison covers architecture role, directionality, and training objective.
  evidence:
  - transformer_encoder_decoder_architecture
  - bert_transformer_encoder_background
  - bert_masked_lm_pretraining
citations: required
```

## Authorized Paper Inventory

- `attention_is_all_you_need_2017`: Attention Is All You Need (2017)
- `adam_2014`: Adam: A Method for Stochastic Optimization (2014)
- `bert_2018`: BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- `gpt2_2019`: Language Models are Unsupervised Multitask Learners (2019)
- `gpt3_2020`: Language Models are Few-Shot Learners (2020)

## Required Evidence Spans

### transformer_encoder_decoder_architecture

Paper: `attention_is_all_you_need_2017`, authored selector: `The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder.`

The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder. The best performing models also connect the encoder and decoder through an attention mechanism. We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely. Experiments on two machine translation tasks show these models to be superior in quality while being more parallelizable and requiring significantly less time to train. Our model achieves 28.4 BLEU on the WMT 2014 Englishto-German translation task, improving over the existing best results, including ensembles, by over 2 BLEU. On the WMT 2014 English-to-French translation task, our model establishes a new single-model state-of-the-art BLEU score of 41.8 after training for 3.5 days on eight GPUs, a small fraction of the training costs of the best models from the literature. We show that the Transformer generalizes well to other tasks by applying it successfully to English constituency parsing both with large and limited training data.

### bert_transformer_encoder_background

Paper: `bert_2018`, authored selector: `multi-layer bidirectional Transformer encoder`

Model Architecture BERT’s model architecture is a multi-layer bidirectional Transformer encoder based on the original implementation described in Vaswani et al. (2017) and released in the tensor2tensor library.<sup>1</sup> Because the use of Transformers has become common and our implementation is almost identical to the original, we will omit an exhaustive background description of the model architecture and refer readers to Vaswani et al. (2017) as well as excellent guides such as “The Annotated Transformer.”<sup>2</sup>

### bert_masked_lm_pretraining

Paper: `bert_2018`, authored selector: `We refer to this procedure as a masked LM`

In order to train a deep bidirectional representation, we simply mask some percentage of the input tokens at random, and then predict those masked tokens. We refer to this procedure as a “masked LM” (MLM), although it is often referred to as a Cloze task in the literature (Taylor, 1953). In this case, the final hidden vectors corresponding to the mask tokens are fed into an output softmax over the vocabulary, as in a standard LM. In all of our experiments, we mask 15% of all WordPiece tokens in each sequence at random. In contrast to denoising auto-encoders (Vincent et al., 2008), we only predict the masked words rather than reconstructing the entire input.

## Human Decision

Choose `keep`, `revise`, or `drop`, then answer:

1. Is the expected outcome the uniquely reasonable action?
2. Are the required papers necessary?
3. Do the required evidence spans cover every requested dimension?
4. Should equivalent evidence from the same paper be accepted?
