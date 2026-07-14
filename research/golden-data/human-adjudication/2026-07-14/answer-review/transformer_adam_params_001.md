# Blind Answer Review: transformer_adam_params_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Transformer original paper used Adam with what beta1, beta2, and epsilon?

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A

Declared outcome: `answered`

In "Attention Is All You Need" (Section 5.3 Optimizer), the authors used the Adam optimizer with β₁ = 0.9, β₂ = 0.98, and ε = 10⁻⁹. [1]

For context, the original Adam paper (Kingma & Ba, 2014) lists its good default settings as α = 0.001, β₁ = 0.9, β₂ = 0.999, and ε = 10⁻⁸. [2]

Comparing the two, the Transformer paper kept β₁ at the Adam default (0.9) but tuned β₂ down to 0.98 (rather than 0.999) and used a smaller ε (10⁻⁹ instead of 10⁻⁸).

Sources
[1] Attention Is All You Need, 5.3 Optimizer, p. 7
[2] Adam: A Method for Stochastic Optimization, unsectioned, p. 2

### Evidence cited by Answer A

#### A1. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

#### A2. Adam: A Method for Stochastic Optimization (p. 2, unsectioned)

Algorithm 1: Adam, our proposed algorithm for stochastic optimization. See section 2 for details,
and for a slightly more efficient (but less clear) order of computation. g _ { t } ^ { 2 } indicates the elementwise
square g _ { t } \odot g _ { t } . Good default settings for the tested machine learning problems are \alpha = 0 . 0 0 1
\bar { \beta _ { 1 } } = 0 \bar { . } 9 , \beta _ { 2 } \bar { = } 0 . 999 and \epsilon = 1 0 ^ { - 8 } . All operations on vectors are element-wise. With \beta _ { 1 } ^ { t } and \beta _ { 2 } ^ { t }
we denote \beta _ { 1 } and \beta _ { 2 } to the power t .
Require: \alpha \mathrm { : } Stepsize
Require: \beta _ { 1 } , \bar { \beta _ { 2 } } \overset { - } { \in } [ 0 , 1 ) : Exponential decay rates for the moment estimates
Require: f ( \theta ) { \mathrm { : } } Stochastic objective function with parameters θ
Require: \theta _ { 0 } \colon Initial parameter vector
m _ { 0 } \gets 0 (Initialize 1 ^ { \mathrm { s t } } moment vector)
v _ { 0 } 0 (Initialize 2 ^ { \mathrm { n d } } moment vector)
t \gets 0 (Initialize timestep)
while \theta _ { t } not converged do
t \gets t + 1
g _ { t } \gets \nabla _ { \theta } f _ { t } ( \theta _ { t - 1 } ) (Get gradients w.r.t. stochastic objective at timestep t)
m _ { t } \gets \beta _ { 1 } \cdot m _ { t - 1 } \dot { + } \left( 1 \bar { - } \beta _ { 1 } \right) \cdot g _ { t } (Update biased first moment estimate)
v _ { t } \beta _ { 2 } \cdot v _ { t - 1 } + ( 1 - \beta _ { 2 } ) \cdot g _ { t } ^ { 2 } (Update biased second raw moment estimate)
\widehat { m } _ { t } \gets m _ { t } / ( 1 - \beta _ { 1 } ^ { t } ) (Compute bias-corrected first moment estimate)
\widehat { v } _ { t } \gets v _ { t } / ( 1 - \beta _ { 2 } ^ { t } ) (Compute bias-corrected second raw moment estimate)
\theta _ { t } \theta _ { t - 1 } - \alpha \cdot \widehat { m } _ { t } / ( \sqrt { v _ { t } } + \epsilon ) (Update parameters)
end while
return { \underline { { \theta _ { t } } } } (Resulting parameters)

## Answer B

Declared outcome: `answered`

In the original Transformer paper, the optimizer settings were **β₁ = 0.9**, **β₂ = 0.98**, and **ε = 10⁻⁹**. [1]

Sources
[1] Attention Is All You Need, 5.3 Optimizer, p. 7

### Evidence cited by Answer B

#### B1. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
