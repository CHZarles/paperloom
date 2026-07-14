# Blind Answer Review: adam_beta2_conflict_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Some sources say Adam beta2 is 0.999, but the Transformer paper used 0.98. Resolve this conflict.

## Authorized Paper Inventory

- Attention Is All You Need (2017)
- Adam: A Method for Stochastic Optimization (2014)
- BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding (2018)
- Language Models are Unsupervised Multitask Learners (2019)
- Language Models are Few-Shot Learners (2020)

## Answer A

Declared outcome: `answered`

There is no real conflict — the two values live at different scopes, and the corpus confirms both.

**The Adam default (0.999).** In Algorithm 1 of the Adam paper (Kingma & Ba, 2014, p. 2), the authors write: "Good default settings for the tested machine learning problems are α = 0.001, β₁ = 0.9, β₂ = 0.999 and ε = 10⁻⁸." [1] So 0.999 is the algorithm's recommended default across the problems the authors tested.

**The Transformer's choice (0.98).** In §5.3 "Optimizer" of the Transformer paper, Vaswani et al. state that they used the Adam optimizer with β₁ = 0.9, β₂ = 0.98, and ε = 10⁻⁹. [2] So the Transformer does, in fact, override the default and use 0.98.

**Adjudication.** The two claims are consistent once their scopes are distinguished:

- 0.999 is Adam's *general default* — a safe starting point recommended in Algorithm 1 of the Adam paper. [1]
- 0.98 is what the Transformer authors *chose* in §5.3 of Vaswani et al. 2017, deliberately overriding that default for their specific architecture and training setup. [2]

Sources that say "0.999" are paraphrasing the Adam paper's general default. Sources that say "0.98" are quoting the Transformer paper. Neither is wrong — they are answering different questions (default vs. specific choice). The apparent conflict dissolves once the scope of each value is made explicit.

Sources
[1] Adam: A Method for Stochastic Optimization, unsectioned, p. 2
[2] Attention Is All You Need, 5.3 Optimizer, p. 7

### Evidence cited by Answer A

#### A1. Adam: A Method for Stochastic Optimization (p. 2, unsectioned)

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

#### A2. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

## Answer B

Declared outcome: `answered`

There isn’t a real conflict: the two numbers refer to different scopes.

- **0.999** is the Adam paper’s stated **“good default”** for the tested machine-learning problems: it lists \(\alpha=0.001\), \(\beta_1=0.9\), \(\beta_2=0.999\), and \(\epsilon=10^{-8}\). [1]
- **0.98** is the **specific hyperparameter choice used in the Transformer paper**: it says they used Adam with \(\beta_1=0.9\), \(\beta_2=0.98\), and \(\epsilon=10^{-9}\). [2]

So: if someone asks for **Adam’s default \(\beta_2\)**, answer **0.999**. If they ask for **the Transformer paper’s Adam \(\beta_2\)**, answer **0.98**.

Sources
[1] Adam: A Method for Stochastic Optimization, unsectioned, p. 2
[2] Attention Is All You Need, 5.3 Optimizer, p. 7

### Evidence cited by Answer B

#### B1. Adam: A Method for Stochastic Optimization (p. 2, unsectioned)

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

#### B2. Attention Is All You Need (p. 7, 5.3 Optimizer)

We used the Adam optimizer [20] with $\beta _ { 1 } = 0 . 9 , \beta _ { 2 } = 0 . 9 8$ and $\epsilon = 1 0 ^ { - 9 }$ . We varied the learning rate over the course of training, according to the formula:

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
