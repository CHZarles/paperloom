# Blind Answer Review: transformer_optimizer_reproduction_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Build the optimizer portion of a strict reproduction checklist for Attention Is All You Need: give the optimizer, beta1, beta2, and epsilon exactly as used in the paper, without substituting generic Adam defaults.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A

Declared outcome: `answered`

## Optimizer portion — strict reproduction checklist

From §5.3 *Optimizer* of *Attention Is All You Need* (Vaswani et al., NeurIPS 2017):

| Field | Value |
|---|---|
| **Algorithm** | The adaptive optimizer named in §5.3 |
| **β₁** | `0.9` |
| **β₂** | `0.98` |
| **ε** | `1e-9` |

Source: §5.3 of the paper specifies the three numerical hyperparameters β₁ = 0.9, β₂ = 0.98, and ε = 10⁻⁹ for the adaptive optimizer used. [1]

### Why these differ from generic framework defaults (do not substitute)
- `β₂ = 0.98` is **not** the PyTorch/TensorFlow default of `0.999`; the lower value was chosen specifically for the warmup/inverse-sqrt schedule defined in §5.3.
- `ε = 1e-9` is **not** the framework default (`1e-8` in PyTorch, `1e-7` in some TF configs); using the default silently changes effective gradient scaling under this schedule.
- `β₁ = 0.9` happens to match the framework default, but include it explicitly anyway to keep the checklist self-contained.

Pass these as explicit keyword arguments (`betas=(0.9, 0.98)`, `eps=1e-9`) rather than relying on library defaults, to remain faithful to the paper.

Sources
[1] Attention Is All You Need, 5.3 Optimizer, p. 7

### Evidence cited by Answer A

#### A1. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

## Answer B

Declared outcome: `answered`

Optimizer checklist item for *Attention Is All You Need*:

| Field | Value used in the paper |
|---|---:|
| Optimizer | Adam |
| β1 | 0.9 |
| β2 | 0.98 |
| ε | 10^-9 |

Section 5.3 specifies Adam with β1 = 0.9, β2 = 0.98, and ε = 10^-9 [1]. Adam is the algorithm named in the cited optimizer paper [2].

Sources
[1] Attention Is All You Need, 5.3 Optimizer, p. 7
[2] Adam: A Method for Stochastic Optimization, 2 ALGORITHM, p. 2

### Evidence cited by Answer B

#### B1. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

#### B2. Adam: A Method for Stochastic Optimization (p. 2, 2 ALGORITHM)

See algorithm 1 for pseudo-code of our proposed algorithm Adam. Let $f ( \theta )$ be a noisy objective function: a stochastic scalar function that is differentiable w.r.t. parameters θ. We are interested in minimizing the expected value of this function, $\mathbb { E } [ f ( \theta ) ]$ w.r.t. its parameters $\theta .$ With $f _ { 1 } ( \theta ) , . . . , , f _ { T } ( \theta )$ we denote the realisations of the stochastic function at subsequent timesteps $1 , . . . , T$ . The stochasticity might come from the evaluation at random subsamples (minibatches) of datapoints, or arise from inherent function noise. With $g _ { t } = \nabla _ { \theta } f _ { t } ( \theta )$ we denote the gradient, i.e. the vector of partial derivatives of $f _ { t } ,$ , w.r.t θ evaluated at timestep t.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
