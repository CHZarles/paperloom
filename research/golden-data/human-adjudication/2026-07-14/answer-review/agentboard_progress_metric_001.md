# Blind Answer Review: agentboard_progress_metric_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Why does AgentBoard argue that final success rate is insufficient, and what does its progress-rate metric add?

## Authorized Paper Inventory

- ReAct: Synergizing Reasoning and Acting in Language Models (2023)
- AgentBench: Evaluating LLMs as Agents (2024)
- GAIA: a benchmark for General AI Assistants (2024)
- WebArena: A Realistic Web Environment for Building Autonomous Agents (2024)
- SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (2024)
- MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (2024)
- AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (2024)
- tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (2024)
- ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (2024)

## Answer A

Declared outcome: `answered`

AgentBoard’s argument is that final success rate is too coarse for multi-turn agent tasks. These tasks involve complex intermediate behavior, but final success rate gives only limited insight into the process; in hard environments where many models have near-zero success, it can blur fine-grained differences and hide the mechanisms behind failures [1]. More specifically, a success/failure metric treats a near-complete trajectory and a barely-started trajectory as the same kind of failure, while existing reward scores are not standardized across settings [2].

The progress-rate metric adds a standardized, fine-grained measure of partial goal attainment: it tracks agents’ intermediate progress rather than only whether the whole task is completed [2]. AgentBoard frames it as tracking “minimal improvement” and intermediate progress across agents, which is useful for incremental improvements in agent capability [3]. Empirically, the paper says progress rate is more informative and discriminative: for example, Llama2-13b and Mistral-7b have similarly low success rates, 2.1% vs. 3.9%, but clearer progress-rate separation, 18.9% vs. 24.6% [4].

In short: final success rate answers “did it finish?”, while progress rate helps answer “how far did it get, and where can we distinguish models that mostly fail at the final step?”

Sources
[1] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 1 Introduction, p. 2
[2] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 2.2 Fine-grained Progress Rate, p. 4
[3] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 2 AGENTBOARD – Overview, p. 3
[4] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 4.2 Main Results, p. 7

### Evidence cited by Answer A

#### A1. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 2, 1 Introduction)

Moreover, the inherent complexity in agent tasks characterized by multi-round interactions distinguishes them significantly from other language tasks. Due to this complexity, there is a pressing need to delve into the details and gain a deeper understanding of how models function during the process. Nonetheless, most current evaluations predominantly rely on the final success rate as their metric, which provides limited insights into these intricate processes (Liu et al., 2023a; Wang et al., 2023b; Yao et al., 2023; Liu et al., 2023b; Mialon et al., 2023). This simplified evaluation is particularly inadequate in challenging environments where most models demonstrate nearly zero success rates, consequently blurring finer distinctions and obscuring underlying mechanisms (Liu et al., 2023a).

#### A2. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 4, 2.2 Fine-grained Progress Rate)

Recent studies highlight the predominant use of success rate as the main metric for agent evaluation, which fails to capture the nuances of partial task completion by language model agents (Liu et al., 2023a; Li et al., 2023). This approach does not differentiate between near-complete tasks and minimal task execution, treating both as equivalent failures. Alternative metrics like reward scores are available but lack standardization (Chevalier-Boisvert et al., 2019; Wang et al., 2022). To mitigate this issue, we introduce a progress rate metric to accurately reflect LM agents’ goal attainment at various stages.

#### A3. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 3, 2 AGENTBOARD – Overview)

• Fine-grained Progress Rate, where AGENTBOARD is the first to propose a fine-grained progress rate metric tracking the intermediate progress of different agents. This metric distinguishes our benchmark in tracking minimal improvement in LLM agent performances. Such a capability is crucial in current endeavors to develop stronger open-weight LLMs, providing detailed insights that are essential for incremental advancements in agent capabilities. We provide detailed introduction for this metric and its annotation process in §2.2.

#### A4. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 7, 4.2 Main Results)

Progress Rate is more informative and discriminative than success rate. The success rate and progress rate across various tasks and categories are presented in Table 3. Regarding the overall performance, the progress rate serves as a more effective differentiator between models. For example, Llama2-13b and Mistral-7b exhibit similarly negligible success rates (2.1% and 3.9%, respectively), but their progress rates differ significantly: 18.9% for Llama2-13b and 24.6% for Mistral-7b. This disparity suggests that Mistral-7b generally outperforms Llama2-13b. For models with substantial differences in success rates, such as Text-Davinci-003 outperforming Llama2-70b by 11.7% in success rate, Text-Davinci-003 leads the progress rate by 13.4% as well, which indicates the consistency in performance disparity between significantly different models. Investigating the agent performance on specific tasks, progress rate is often able to differentiate models that have similar success rates – for instance, on the Embodied AI and Game categories, the success rates of most of the open-weight models are similarly low, while they are able to make meaningfully different progresses. Also, the success rate can be influenced by specific characteristics of agents, for example, an agent like CodeLlama-34b often fails to generate the action “finish” when performing tool-using tasks, leading to a higher progress rate and lower success rate compared to CodeLlama-13b. In contrast, progress rate is less susceptible to these agent-specific features as it reflects the overall ability of the agent at each step.

## Answer B

Declared outcome: `answered`

## Why AgentBoard argues final success rate is insufficient

In §2.2 (Fine-grained Progress Rate), the paper makes three concrete critiques of the dominant "success rate" metric:

1. **It conflates very different outcomes.** Success rate "fails to capture the nuances of partial task completion by language model agents" and "does not differentiate between near-complete tasks and minimal task execution, treating both as equivalent failures." [1]
2. **Existing alternatives are non-standardized.** "Alternative metrics like reward scores are available but lack standardization." [1]
3. **It hides progress on long-horizon tasks.** Because success rate is a terminal binary, an agent that almost finishes a multi-step task is reported the same as one that barely started.

## What the progress-rate metric adds

AgentBoard introduces a subgoal-anchored progress rate to "accurately reflect LM agents' goal attainment at various stages." [1] Specifically, the paper documents the following added properties:

- **Joint use with success rate, not as a replacement.** The paper employs progress rate "along with the commonly used success rate metric, which computes the proportion of tasks completed within T interactions." [2]
- **A unified, subgoal-based signal across diverse tasks.** AgentBoard "defined or manually annotated subgoals for each data sample, introducing a unified progress rate metric to track the agents' detailed advancements." [3]
- **Discriminative power among weak models.** In §4.2 the paper reports that "progress rate serves as a more effective differentiator between models. For example, Llama2-13b and Mistral-7b exhibit similarly negligible success rates (2.1% and 3.9%, respectively), but their progress rates differ significantly: 18.9% for Llama2-13b and 24.6% for Mistral-7b." The paper generalizes the point in §1: the metric "uncovers significant progress made by models that would otherwise appear trivial due to negligible differences in success rates." [4] [3]
- **Robustness to agent-specific failure modes.** "The success rate can be influenced by specific characteristics of agents, for example, an agent like CodeLlama-34b often fails to generate the action 'finish' when performing tool-using tasks, leading to a higher progress rate and lower success rate compared to CodeLlama-13b. In contrast, progress rate is less susceptible to these agent-specific features as it reflects the overall ability of the agent at each step." [4]
- **Denser signal than ad-hoc environment rewards.** In the BabyAI environment, "Different from the original reward, our progress rate is more dense and the agent does not need to accomplish many steps before getting a increase in score... our progress rate better measures intermediate progress for agents." [5]
- **Human-validated alignment.** A user study in which four annotators rated 60 model trajectories per task from GPT-4, GPT-3.5-Turbo, and Deepseek-67b found that "the Pearson correlation exceeds 0.95 on all tasks" between human progress scores and the automatic progress rate, with "substantial agreement" among annotators (Fleiss' κ). [6]

In short, the paper's argument is that success rate is a binary, terminal, environment-specific summary that hides partial progress and is brittle to formatting artifacts; the progress-rate metric supplies a unified, subgoal-anchored, step-by-step signal that is denser, more discriminative among weak models, and aligned with human judgment.

Sources
[1] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 2.2 Fine-grained Progress Rate, p. 4
[2] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 2.2 Fine-grained Progress Rate, p. 5
[3] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 1 Introduction, p. 2
[4] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 4.2 Main Results, p. 7
[5] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, L.4 BabyAI, p. 24
[6] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, 3.2 Annotation Verification and Metric Justification, p. 6

### Evidence cited by Answer B

#### B1. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 4, 2.2 Fine-grained Progress Rate)

Recent studies highlight the predominant use of success rate as the main metric for agent evaluation, which fails to capture the nuances of partial task completion by language model agents (Liu et al., 2023a; Li et al., 2023). This approach does not differentiate between near-complete tasks and minimal task execution, treating both as equivalent failures. Alternative metrics like reward scores are available but lack standardization (Chevalier-Boisvert et al., 2019; Wang et al., 2022). To mitigate this issue, we introduce a progress rate metric to accurately reflect LM agents’ goal attainment at various stages.

#### B2. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 5, 2.2 Fine-grained Progress Rate)

We employ progress rate along with the commonly used success rate metric, which computes the proportion of tasks completed within T interactions.

#### B3. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 2, 1 Introduction)

To address these issues, we introduce AGENTBOARD, a benchmark designed for multi-turn LLM agents, complemented by an analytical evaluation board for detailed model assessment beyond final success rates. AGENTBOARD encompasses a diverse set of 9 unique tasks and 1013 exemplary environments, covering a range from embodied AI and game agents to web and tool agents. Each environment, whether newly created or adapted from pre-existing ones, is carefully crafted and authenticated by humans to ensure multi-round and partially observable characteristics in a unified manner. Notably, we have defined or manually annotated subgoals for each data sample, introducing a unified progress rate metric to track the agents’ detailed advancements. As we will demonstrate in §4.2, this metric uncovers significant progress made by models that would otherwise appear trivial due to negligible differences in success rates.

#### B4. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 7, 4.2 Main Results)

Progress Rate is more informative and discriminative than success rate. The success rate and progress rate across various tasks and categories are presented in Table 3. Regarding the overall performance, the progress rate serves as a more effective differentiator between models. For example, Llama2-13b and Mistral-7b exhibit similarly negligible success rates (2.1% and 3.9%, respectively), but their progress rates differ significantly: 18.9% for Llama2-13b and 24.6% for Mistral-7b. This disparity suggests that Mistral-7b generally outperforms Llama2-13b. For models with substantial differences in success rates, such as Text-Davinci-003 outperforming Llama2-70b by 11.7% in success rate, Text-Davinci-003 leads the progress rate by 13.4% as well, which indicates the consistency in performance disparity between significantly different models. Investigating the agent performance on specific tasks, progress rate is often able to differentiate models that have similar success rates – for instance, on the Embodied AI and Game categories, the success rates of most of the open-weight models are similarly low, while they are able to make meaningfully different progresses. Also, the success rate can be influenced by specific characteristics of agents, for example, an agent like CodeLlama-34b often fails to generate the action “finish” when performing tool-using tasks, leading to a higher progress rate and lower success rate compared to CodeLlama-13b. In contrast, progress rate is less susceptible to these agent-specific features as it reflects the overall ability of the agent at each step.

#### B5. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 24, L.4 BabyAI)

The origin implementation of babyai provides a reward score. Different from the original reward, our progress rate is more dense and the agent does not need to accomplish many steps before getting a increase in score. Here we compare the difference between our progress rate and the original reward score, as shown in Table 17. We can see from this case that our progress rate better measures intermediate progress for agents.

#### B6. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 6, 3.2 Annotation Verification and Metric Justification)

After human annotation, we manually verified our labeled subgoals through multiple verification stages to ensure its quality, as detailed in Appendix J. More importantly, we conduct a user study to justify our proposed progress rate metric, asking human annotators to assess the progress of model trajectories and then evaluating its correlation with our automatic progress rate metric. Specifically, we gather 60 model trajectories for each of the 8 tasks from three strong LLMs——GPT-4, GPT-3.5-Turbo, and Deepseek-67b. Each trajectory is assessed by four authors of the paper. The individual human rater is asked to select progress score from {0%, 25%, 50%, 75%, 100%} given trajectories and task descriptions, without seeing the automatic score. Mean of four scores is taken as the final human score for every trajectory. We show the Pearson correlation between human progress score and the progress rate in Figure 3, and report Fleiss’kappa κ to reflect inter-annotator agreement. Results show that progress rate highly correlates with human assessment on the progress where the Pearson correlation exceeds 0.95 on all tasks, and substantial agreement is reached among the annotators.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
