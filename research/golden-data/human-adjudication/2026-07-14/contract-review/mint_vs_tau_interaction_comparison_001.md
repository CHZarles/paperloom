# Golden Contract Review: mint_vs_tau_interaction_comparison_001

## Conversation

- **user**: Compare MINT and tau-bench on interaction design, tool use, feedback, environment state, and reliability measurement.

## Authored Expectation

```yaml
outcome: answered
papers:
  required:
  - mint_2024
  - tau_bench_2024
  forbidden:
  - agentbench_2024
evidence:
  required:
  - mint_tools_feedback_protocol
  - tau_database_end_state_evaluation
  - tau_passk_reliability
  forbidden:
  - agentbench_eight_environments
citations: required
```

## Authorized Paper Inventory

- `react_2023`: ReAct: Synergizing Reasoning and Acting in Language Models (2023)
- `agentbench_2024`: AgentBench: Evaluating LLMs as Agents (2024)
- `gaia_2024`: GAIA: a benchmark for General AI Assistants (2024)
- `webarena_2024`: WebArena: A Realistic Web Environment for Building Autonomous Agents (2024)
- `swebench_2024`: SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (2024)
- `mint_2024`: MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (2024)
- `agentboard_2024`: AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (2024)
- `tau_bench_2024`: tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (2024)
- `toolsandbox_2024`: ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (2024)

## Required Evidence Spans

### mint_tools_feedback_protocol

Paper: `mint_2024`, authored selector: `evaluates LLMs' ability to solve challenging tasks with multi-turn interactions by (1) using tools and (2) leveraging natural language feedback`

To solve complex tasks, large language models (LLMs) often require multiple rounds of interactions with the user, sometimes assisted by external tools. However, current evaluation protocols often emphasize benchmark performance with single-turn exchanges, neglecting the nuanced interactions among the user, LLMs, and external tools, while also underestimating the importance of natural language feedback from users. These oversights contribute to discrepancies between research benchmark evaluations and real-world use cases. We introduce MINT, a benchmark that evaluates LLMs’ ability to solve challenging tasks with multi-turn interactions by (1) using tools and (2) leveraging natural language feedback. To ensure reproducibility, we provide an evaluation framework where LLMs can access tools by executing Python code and receive users’ natural language feedback simulated by GPT-4. We repurpose a diverse set of established evaluation datasets focusing on reasoning, coding, and decision-making and carefully curate them into a compact subset for efficient evaluation. Our analysis of 20 open- and closedsource LLMs offers intriguing findings. (a) LLMs generally benefit from tools and language feedback, with performance gains (absolute, same below) of 1–8% for each turn of tool use and 2–17% with natural language feedback. (b) Better singleturn performance does not guarantee better multi-turn performance. (c) Surprisingly, among the evaluated LLMs, supervised instruction-finetuning (SIFT) and reinforcement learning from human feedback (RLHF) generally hurt multi-turn capabilities. We expect MINT can help measure progress and incentivize research in improving LLMs’ capabilities in multi-turn interactions, especially for opensource communities where multi-turn human evaluation can be less accessible compared to commercial LLMs with a larger user base.

### tau_database_end_state_evaluation

Paper: `tau_bench_2024`, authored selector: `compares the database state at the end of a conversation with the annotated goal state`

Existing benchmarks do not test language agents on their interaction with human users or ability to follow domain-specific rules, both of which are vital for deploying them in real world applications. We propose τ -bench, a benchmark emulating dynamic conversations between a user (simulated by language models) and a language agent provided with domain-specific API tools and policy guidelines. We employ an efficient and faithful evaluation process that compares the database state at the end of a conversation with the annotated goal state. We also propose a new metric (pass^k) to evaluate the reliability of agent behavior over multiple trials. Our experiments show that even state-of-the-art function calling agents (like gpt-4o) succeed on < 50% of the tasks, and are quite inconsistent (pass^8 < 25% in retail). Our findings point to the need for methods that can improve the ability of agents to act consistently and follow rules reliably.

### tau_passk_reliability

Paper: `tau_bench_2024`, authored selector: `pass^8 < 25% in retail`

Existing benchmarks do not test language agents on their interaction with human users or ability to follow domain-specific rules, both of which are vital for deploying them in real world applications. We propose τ -bench, a benchmark emulating dynamic conversations between a user (simulated by language models) and a language agent provided with domain-specific API tools and policy guidelines. We employ an efficient and faithful evaluation process that compares the database state at the end of a conversation with the annotated goal state. We also propose a new metric (pass^k) to evaluate the reliability of agent behavior over multiple trials. Our experiments show that even state-of-the-art function calling agents (like gpt-4o) succeed on < 50% of the tasks, and are quite inconsistent (pass^8 < 25% in retail). Our findings point to the need for methods that can improve the ability of agents to act consistently and follow rules reliably.

## Human Decision

Choose `keep`, `revise`, or `drop`, then answer:

1. Is the expected outcome the uniquely reasonable action?
2. Are the required papers necessary?
3. Do the required evidence spans cover every requested dimension?
4. Should equivalent evidence from the same paper be accepted?
