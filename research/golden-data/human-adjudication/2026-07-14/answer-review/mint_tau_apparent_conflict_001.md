# Blind Answer Review: mint_tau_apparent_conflict_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: MINT reports gains from tools and language feedback, while tau-bench reports poor multi-trial reliability. Are these findings contradictory?

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

# Are MINT's and tau-bench's Findings Contradictory?

**Short answer: No.** The two findings address different evaluation dimensions and can coexist. They are complementary rather than contradictory.

## What each finding actually measures

**MINT — per-turn gains within a single task instance.**
MINT measures how much performance improves as additional interaction resources are provided *within* a single task run. Its headline result is that "All models benefit from tool interaction and natural language feedback, with absolute performance gains by 1–8% for each additional turn of tool use, and 2–17% with natural language feedback." The unit of analysis is one task; the dependent variable is the gain from each extra turn of tool use / feedback along that single trajectory. [1] MINT frames this as measuring two capabilities: "(1) tool-augmented task-solving; (2) leveraging natural language feedback." [2]

**τ-bench — reliability across independent trials of the same task.**
τ-bench introduces a different metric: "pass^k (pass hat k), defined as the chance that all k i.i.d. task trials are successful, averaged across tasks." [3] Its headline finding is that "the chance of reliably and consistently solving the same task multiple times significantly drops as the number of trials k increases. Even for the best-performing gpt-4o function calling agent which has a > 60% average task success, pass^8 drop to < 25%." [4] The unit of analysis is k independent runs; the dependent variable is the probability that *all* of them succeed.

## Why these are not contradictory

1. **Different axes of evaluation.** MINT's "gain" is a *monotonic, within-instance* effect (each additional turn yields an average improvement). τ-bench's pass^k is a *cross-instance consistency* effect (variance across independent runs). A model can show positive average per-turn gains *and* still have high cross-trial variance — these are independent properties of an agent's behavior.

2. **Different reference points.** MINT's gains are computed relative to the model's earlier-turn performance on the same trajectory. τ-bench's pass^k is computed relative to a fresh, independent trial. A turn-by-turn improvement curve tells you nothing about whether the next *fresh* run will land on the same successful trajectory.

3. **τ-bench itself frames average success and reliability as distinct desiderata.** It explicitly says "it is important and challenging not just to build agents with high average success (pass^1), but with more robustness and consistency (pass^k trend)." [4] By default τ-bench reports "the average reward across tasks, pass^1 = pass@1 = E[r] = E[c/n]" as the main comparison metric, while reserving pass^k for the reliability question. [5] This framing directly accepts that high pass^1 (which is what MINT-style gains contribute to) and high pass^k are separate goals — and that achieving one does not entail the other.

4. **The two findings are mutually reinforcing, not in tension.** τ-bench's conclusion that "even SOTA LMs are far from being reliable for use in real-world settings" [6] is consistent with MINT's broader message that multi-turn / interactive evaluation reveals shortcomings that single-turn metrics hide (e.g., MINT's observation that open-source models still trail closed-source ones even with feedback). Both papers converge on the message that gains in interactive settings exist but are uneven across models and far from sufficient for deployment.

## What the apparent tension actually reflects

The reason the two findings *feel* like they could conflict is that both involve "multi-turn interaction." But MINT's multi-turn axis is *temporal within a task* (turn index → expected gain), while τ-bench's multi-trial axis is *stochastic across re-samples of the same task* (trial index → probability all succeed). They answer different questions:

- MINT asks: "If we let the agent keep going and give it more feedback, does it improve in expectation?"
- τ-bench asks: "If we restart the agent on the same task, will it succeed every time?"

A positive answer to the first question is fully compatible with a negative answer to the second.

## Caveat from the corpus

Within the available papers I did not find any direct cross-citation or explicit joint discussion linking MINT's gain numbers to τ-bench's pass^k numbers on the same model set. The citation graph returned for the two papers in this corpus does not include an edge between them. So the comparison above is a reading of what each paper claims about its own evaluation protocol, not a finding that either paper draws about the other. A head-to-head reconciliation (e.g., the same model evaluated under both protocols and the two metrics compared on the same task) is not directly supported by the corpus and would require additional sources.

Sources
[1] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 1 INTRODUCTION, p. 2
[2] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 1 INTRODUCTION, p. 1
[3] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 4
[4] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5.1 Main results, p. 7
[5] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 5
[6] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 6 Discussion, p. 8

### Evidence cited by Answer A

#### A1. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 2, 1 INTRODUCTION)

• All models benefit from tool interaction and natural language feedback, with absolute performance gains by 1–8% for each additional turn of tool use, and 2–17% with natural language feedback.

#### A2. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 1, 1 INTRODUCTION)

To bridge these gaps, we introduce MINT. It is a benchmark for LLMs that measures their performance during multi-turn interaction, focusing on two particular capabilities (§2.1): (1) toolaugmented task-solving; (2) leveraging natural language feedback. MINT mirrors the real-world user-LLM-tool collaborative problem-solving setting. To solve a problem, the LLM can use external tools by generating and executing Python programs (Wang et al., 2024) and/or collecting natural language feedback to refine its solutions; the feedback is provided by GPT-4 (OpenAI, 2023), aiming to simulate human users in a reproducible and scalable way.<sup>2</sup> For a comprehensive evaluation, we include eight established datasets spanning reasoning, code generation, and decision-making (§2.2). To facilitate affordable multi-turn evaluation, after collecting 29,307 diverse instances from existing datasets (Tab. 1), we construct a subset of 586 challenging and representative instances that require multi-turn interaction to solve<sup>3</sup>.

#### A3. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 4, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

Pass^k metric. For tasks like code generation with good verification techniques (unit tests), the community has defined the pass@k (pass at k) metric as the chance that at least one out of k i.i.d. task trials is successful, which captures the trend of agents enabling discovery of solutions with scaling of inference-time compute [5]. For real-world agent tasks requiring reliability and consistency like customer service, we propose a new metric – pass^k (pass hat k), defined as the chance that all k i.i.d. task trials are successful, averaged across tasks. Therefore, if a task is run for n trials and c of those trials end up successful (r = 1), unbiased estimates for pass^k and pass@k would be:

#### A4. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 7, 5.1 Main results)

Agent consistency via pass^k. As shown in Figure 4, the chance of reliably and consistently solving the same task multiple times significantly drops as the number of trials k increases. Even for the best-performing gpt-4o function calling agent which has a > 60% average task success, pass^8 drop to < 25%. In real-world scenarios, it is important and challenging not just to build agents with high average success (pass^1), but with more robustness and consistency (pass^k trend).

#### A5. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 5, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

In our case, for the same task, the user prompt and database transitions are the same, with just the LM sampling of the user and agent messages generating sufficient stochasticity. Thus, pass^k can capture the reliability of the agent at handling variations in conversations with the same underlying semantics while adhering to the domain policies and rules. By default, we report the average reward across tasks, $\mathrm { p a s s } ^ { \wedge } \mathrm { l } \mathrm { = p a s s } @ 1 \mathrm { = } \mathbb { E } [ r ] = \mathbb { E } [ c / n ]$ , as the main metric for comparing agents.

#### A6. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 8, 6 Discussion)

We have presented τ -bench, a novel benchmark for evaluating the reliability of agents in interacting with humans and tools in dynamic and realistic settings. The benchmark leverages the latest advances in LMs to simulate users, allows for automated testing of agents and provides an assessment of an agent’s ability to follow domain-specific rules in a consistent manner. Our results show that even SOTA LMs are far from being reliable for use in real-world settings.

## Answer B

Declared outcome: `answered`

No—these findings are not necessarily contradictory.

MINT is saying that, within its benchmark, models improve when given tool interaction and natural-language feedback: it focuses on tool-augmented task solving and leveraging feedback, and reports absolute gains of 1–8% per additional tool-use turn and 2–17% with natural-language feedback. [1] [2]

τ-bench is asking a different question: whether an agent can solve the same underlying task reliably across multiple stochastic interaction trials while following domain policies and rules. [3] [4] On that metric, τ-bench finds that consistency drops sharply as the number of required successful trials increases; even the best reported GPT-4o function-calling agent has >60% average task success but <25% pass^8. [5]

So the two results can coexist: tools and feedback can raise average or per-turn task success in MINT, while τ-bench can still find that agents are unreliable when they must succeed repeatedly under conversational stochasticity, partial information, databases, policies, and compound requests. [2] [6]

Sources
[1] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 1 INTRODUCTION, p. 1
[2] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 1 INTRODUCTION, p. 2
[3] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 4
[4] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 5
[5] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5.1 Main results, p. 7
[6] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 1 Introduction, p. 2

### Evidence cited by Answer B

#### B1. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 1, 1 INTRODUCTION)

To bridge these gaps, we introduce MINT. It is a benchmark for LLMs that measures their performance during multi-turn interaction, focusing on two particular capabilities (§2.1): (1) toolaugmented task-solving; (2) leveraging natural language feedback. MINT mirrors the real-world user-LLM-tool collaborative problem-solving setting. To solve a problem, the LLM can use external tools by generating and executing Python programs (Wang et al., 2024) and/or collecting natural language feedback to refine its solutions; the feedback is provided by GPT-4 (OpenAI, 2023), aiming to simulate human users in a reproducible and scalable way.<sup>2</sup> For a comprehensive evaluation, we include eight established datasets spanning reasoning, code generation, and decision-making (§2.2). To facilitate affordable multi-turn evaluation, after collecting 29,307 diverse instances from existing datasets (Tab. 1), we construct a subset of 586 challenging and representative instances that require multi-turn interaction to solve<sup>3</sup>.

#### B2. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 2, 1 INTRODUCTION)

• All models benefit from tool interaction and natural language feedback, with absolute performance gains by 1–8% for each additional turn of tool use, and 2–17% with natural language feedback.

#### B3. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 4, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

Pass^k metric. For tasks like code generation with good verification techniques (unit tests), the community has defined the pass@k (pass at k) metric as the chance that at least one out of k i.i.d. task trials is successful, which captures the trend of agents enabling discovery of solutions with scaling of inference-time compute [5]. For real-world agent tasks requiring reliability and consistency like customer service, we propose a new metric – pass^k (pass hat k), defined as the chance that all k i.i.d. task trials are successful, averaged across tasks. Therefore, if a task is run for n trials and c of those trials end up successful (r = 1), unbiased estimates for pass^k and pass@k would be:

#### B4. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 5, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

In our case, for the same task, the user prompt and database transitions are the same, with just the LM sampling of the user and agent messages generating sufficient stochasticity. Thus, pass^k can capture the reliability of the agent at handling variations in conversations with the same underlying semantics while adhering to the domain policies and rules. By default, we report the average reward across tasks, $\mathrm { p a s s } ^ { \wedge } \mathrm { l } \mathrm { = p a s s } @ 1 \mathrm { = } \mathbb { E } [ r ] = \mathbb { E } [ c / n ]$ , as the main metric for comparing agents.

#### B5. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 7, 5.1 Main results)

Agent consistency via pass^k. As shown in Figure 4, the chance of reliably and consistently solving the same task multiple times significantly drops as the number of trials k increases. Even for the best-performing gpt-4o function calling agent which has a > 60% average task success, pass^8 drop to < 25%. In real-world scenarios, it is important and challenging not just to build agents with high average success (pass^1), but with more robustness and consistency (pass^k trend).

#### B6. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 2, 1 Introduction)

Our experiments reveal that agents built with simple LM constructs (like function calling or ReAct) perform poorly, highlighting the need for more sophisticated agent architectures. For instance, even state-of-the-art LMs like gpt-4o achieve low task success rates (pass^1) using function calling (∼61% on τ -retail and ∼35% on τ -airline). With increasing k, the chance of consistently solving a task drops rapidly, to as low as ∼25% for pass^8 on τ -retail for the same model. This showcases the fragile nature of such agents in handling stochasticity and partial information, which is common in human-agent interaction. Upon analyzing the failure cases, we find that current agents struggle with complex reasoning over databases, understanding and following ad-hoc policies, and handling compound (more than one) requests. We hope that τ -bench enables the evaluation and development of more consistent and capable agents for real-world digital tasks involving human interaction.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
