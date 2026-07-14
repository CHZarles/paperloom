# Golden Contract Review: toolsandbox_constraint_selection_001

## Conversation

- **user**: Choose the benchmark in this corpus that combines stateful tools, on-policy conversation, intermediate milestones, and insufficient-information scenarios. Explain why it fits.

## Authored Expectation

```yaml
outcome: answered
papers:
  required:
  - toolsandbox_2024
  forbidden:
  - mint_2024
  - tau_bench_2024
evidence:
  required:
  - toolsandbox_stateful_on_policy_design
  - toolsandbox_challenge_categories
  forbidden:
  - mint_tools_feedback_protocol
  - tau_database_end_state_evaluation
claims:
- text: ToolSandbox satisfies the combined stateful, on-policy, milestone-based, and
    insufficient-information constraints.
  evidence:
  - toolsandbox_stateful_on_policy_design
  - toolsandbox_challenge_categories
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

### toolsandbox_stateful_on_policy_design

Paper: `toolsandbox_2024`, authored selector: `includes stateful tool execution, implicit state dependencies between tools, a built-in user simulator supporting on-policy conversational evaluation`

Recent large language models (LLMs) advancements sparked a growing research interest in tool assisted LLMs solving real-world challenges, which calls for comprehensive evaluation of tool-use capabilities. While previous works focused on either evaluating over stateless web services (RESTful API), based on a single turn user prompt, or an off-policy dialog trajectory, TOOLSANDBOX<sup>1</sup> includes stateful tool execution, implicit state dependencies between tools, a built-in user simulator supporting on-policy conversational evaluation and a dynamic evaluation strategy for intermediate and final milestones over an arbitrary trajectory. We show that open source and proprietary models have a significant performance gap, and complex tasks like State Dependency, Canonicalization and Insufficient Information defined in TOOLSANDBOX are challenging even the most capable SOTA LLMs, providing brand new insights into tool-use LLM capabilities.

### toolsandbox_challenge_categories

Paper: `toolsandbox_2024`, authored selector: `complex tasks like State Dependency, Canonicalization and Insufficient Information`

Recent large language models (LLMs) advancements sparked a growing research interest in tool assisted LLMs solving real-world challenges, which calls for comprehensive evaluation of tool-use capabilities. While previous works focused on either evaluating over stateless web services (RESTful API), based on a single turn user prompt, or an off-policy dialog trajectory, TOOLSANDBOX<sup>1</sup> includes stateful tool execution, implicit state dependencies between tools, a built-in user simulator supporting on-policy conversational evaluation and a dynamic evaluation strategy for intermediate and final milestones over an arbitrary trajectory. We show that open source and proprietary models have a significant performance gap, and complex tasks like State Dependency, Canonicalization and Insufficient Information defined in TOOLSANDBOX are challenging even the most capable SOTA LLMs, providing brand new insights into tool-use LLM capabilities.

## Human Decision

Choose `keep`, `revise`, or `drop`, then answer:

1. Is the expected outcome the uniquely reasonable action?
2. Are the required papers necessary?
3. Do the required evidence spans cover every requested dimension?
4. Should equivalent evidence from the same paper be accepted?
