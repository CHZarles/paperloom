# Blind Answer Review: toolsandbox_constraint_selection_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Choose the benchmark in this corpus that combines stateful tools, on-policy conversation, intermediate milestones, and insufficient-information scenarios. Explain why it fits.

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

The best fit is **ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities**.

Why it fits:

- **Stateful tools:** ToolSandbox explicitly includes “stateful tool execution” and “implicit state dependencies between tools” [1]. The paper defines these as tools that “inspect, depend on or manipulate world states” [2].
- **On-policy conversation:** It has “a built-in user simulator supporting on-policy conversational evaluation” [1], and the conversational section says on-policy rollout is supported by a GPT-4o-powered user simulator over possibly multiple conversation rounds [3].
- **Intermediate milestones:** It uses “a dynamic evaluation strategy for intermediate and final milestones over an arbitrary trajectory” [1] and a “milestone / minefield based system for intermediate and final execution evaluation” [4].
- **Insufficient-information scenarios:** The benchmark defines Insufficient Information as one of its challenging task categories [5]. In those scenarios, a needed tool is deliberately withheld so the agent must recognize it cannot complete the task rather than hallucinating tools or arguments [6].

So, ToolSandbox is the corpus benchmark that combines all four requested properties in one evaluation design.

Sources
[1] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, Abstract, p. 1
[2] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 2.1 Stateful, p. 3
[3] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 2.2 Conversational, p. 4
[4] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 1 Introduction, p. 3
[5] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 3 Test Scenarios, p. 6
[6] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 3 Test Scenarios, p. 6

### Evidence cited by Answer A
#### A1. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 1, Abstract)

Recent large language models (LLMs) advancements sparked a growing research interest in tool assisted LLMs solving real-world challenges, which calls for comprehensive evaluation of tool-use capabilities. While previous works focused on either evaluating over stateless web services (RESTful API), based on a single turn user prompt, or an off-policy dialog trajectory, TOOLSANDBOX<sup>1</sup> includes stateful tool execution, implicit state dependencies between tools, a built-in user simulator supporting on-policy conversational evaluation and a dynamic evaluation strategy for intermediate and final milestones over an arbitrary trajectory. We show that open source and proprietary models have a significant performance gap, and complex tasks like State Dependency, Canonicalization and Insufficient Information defined in TOOLSANDBOX are challenging even the most capable SOTA LLMs, providing brand new insights into tool-use LLM capabilities.

#### A2. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 3, 2.1 Stateful)

To construct challenging reasoning scenarios, TOOLSANDBOX includes a set of carefully designed stateful tools, defined as tools that inspects, depends on or manipulates world states. These world states include:

#### A3. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 4, 2.2 Conversational)

On-policy conversational roll-out is supported by an LLM (GPT-4o) powered user simulator and carefully calibrated prompting design. The simulator represents a human interacting with an Agent, hoping to complete a task through possibly multiple rounds of conversation. When the User simulator decides the task has been completed, or could not be completed, it can terminate the conversation using the end\_conversation tool, which is the single tool available to it. As related studies in user simulation (Zhang et al., 2024; Sekulic et al., 2024) suggest, one should include the user’s overall goal in the simulator’s system prompt. However, we found this is often insufficient for the complex interactions in TOOLSANDBOX, and can lead to two categories of failures. In some cases, it is infeasible for an LLM simulated user to judge task completion, or provide follow-up information with only access to the user goal, and not the expected result, which could lead to hallucination. Also, with only a single system prompt, the simulated user could be derailed by the tool-use agent, failing to follow instructions. Examples of these failures can be found in Appendix A.4.

#### A4. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 3, 1 Introduction)

• Allows for fully interactive, dynamic trajectory collection with a representative set of highly composable tools, and a human authored, milestone / minefield based system for intermediate and final execution evaluation.

#### A5. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 6, 3 Test Scenarios)

Categories including Multiple Tool Call, Multiple User Turn, State Dependency and Insufficient Information are difficult challenges which requires complex reasoning capability from the agent, 85% of TOOLSANDBOX scenarios are associated with at least one of these challenging categories. Further scenario statistics including category wise breakdown and milestone coverage can be found in Appendix B.4.

#### A6. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 6, 3 Test Scenarios)

Insufficient Information The insufficient information category is used for scenarios where the agent is not able to perform the task on purpose, by withholding a tool that would be needed for the task. This category exercises if the agent is able to identify that it cannot complete the task, as opposed to hallucinating tools or tool arguments, as shown in Figure 3. In these scenarios, minefields are defined to evaluate if tools that would imply hallucination are called or not. Comparing to relevance detection in BFCL where provided tools are often irrelevant to the task at hand, this is a much more challenging scenario, which requires the agent to reason over highly relevant tools to figure out the missing pieces. Comparing to solvability in ToolEval, which assumes full credit for any task deemed unsolvable, this is much more fine-grained, testing if the agent would hallucinate when the task is unsolvable.

## Answer B
Declared outcome: `answered`

The benchmark is **ToolSandbox** (Lu, Holleis et al., 2024; arXiv:2408.04682). Its subtitle already names the first three traits — "A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities" — and its scenario taxonomy adds the fourth. Here is why it matches every criterion:

- **Stateful tools.** Section 2.1 defines stateful tools as "tools that inspects, depends on or manipulates world states," and lists examples such as cellular service (e.g., `send_message`) and WiFi (e.g., `search_stock`). [1]
- **On-policy conversation.** Section 2.2 states that "On-policy conversational roll-out is supported by an LLM (GPT-4o) powered user simulator and carefully calibrated prompting design," where the simulator terminates the dialog via an `end_conversation` tool. [2] The Introduction also calls out "realistic, on-policy conversational evaluation to measure the agent's ability on implicit dialog state tracking."
- **Intermediate milestones.** Section 1 advertises "a human authored, milestone / minefield based system for intermediate and final execution evaluation." [3]
- **Insufficient-information scenarios.** Section 3 defines an explicit "Insufficient Information" category for tasks where the agent cannot complete the work because a needed tool is withheld, and uses minefields to check whether the agent hallucinates tools or arguments instead of recognizing the gap. [4] Roughly 85% of ToolSandbox scenarios are tagged with at least one of the challenging categories — Multiple Tool Call, Multiple User Turn, State Dependency, or Insufficient Information. [5]

In short, ToolSandbox is the unique entry in the corpus whose design simultaneously couples stateful tool execution, an LLM-simulated on-policy user, milestone-based intermediate evaluation, and purpose-built insufficient-information scenarios.

Sources
[1] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 2.1 Stateful, p. 3
[2] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 2.2 Conversational, p. 4
[3] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 1 Introduction, p. 3
[4] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 3 Test Scenarios, p. 6
[5] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, 3 Test Scenarios, p. 6

### Evidence cited by Answer B
#### B1. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 3, 2.1 Stateful)

To construct challenging reasoning scenarios, TOOLSANDBOX includes a set of carefully designed stateful tools, defined as tools that inspects, depends on or manipulates world states. These world states include:

#### B2. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 4, 2.2 Conversational)

On-policy conversational roll-out is supported by an LLM (GPT-4o) powered user simulator and carefully calibrated prompting design. The simulator represents a human interacting with an Agent, hoping to complete a task through possibly multiple rounds of conversation. When the User simulator decides the task has been completed, or could not be completed, it can terminate the conversation using the end\_conversation tool, which is the single tool available to it. As related studies in user simulation (Zhang et al., 2024; Sekulic et al., 2024) suggest, one should include the user’s overall goal in the simulator’s system prompt. However, we found this is often insufficient for the complex interactions in TOOLSANDBOX, and can lead to two categories of failures. In some cases, it is infeasible for an LLM simulated user to judge task completion, or provide follow-up information with only access to the user goal, and not the expected result, which could lead to hallucination. Also, with only a single system prompt, the simulated user could be derailed by the tool-use agent, failing to follow instructions. Examples of these failures can be found in Appendix A.4.

#### B3. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 3, 1 Introduction)

• Allows for fully interactive, dynamic trajectory collection with a representative set of highly composable tools, and a human authored, milestone / minefield based system for intermediate and final execution evaluation.

#### B4. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 6, 3 Test Scenarios)

Insufficient Information The insufficient information category is used for scenarios where the agent is not able to perform the task on purpose, by withholding a tool that would be needed for the task. This category exercises if the agent is able to identify that it cannot complete the task, as opposed to hallucinating tools or tool arguments, as shown in Figure 3. In these scenarios, minefields are defined to evaluate if tools that would imply hallucination are called or not. Comparing to relevance detection in BFCL where provided tools are often irrelevant to the task at hand, this is a much more challenging scenario, which requires the agent to reason over highly relevant tools to figure out the missing pieces. Comparing to solvability in ToolEval, which assumes full credit for any task deemed unsolvable, this is much more fine-grained, testing if the agent would hallucinate when the task is unsolvable.

#### B5. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 6, 3 Test Scenarios)

Categories including Multiple Tool Call, Multiple User Turn, State Dependency and Insufficient Information are difficult challenges which requires complex reasoning capability from the agent, 85% of TOOLSANDBOX scenarios are associated with at least one of these challenging categories. Further scenario statistics including category wise breakdown and milestone coverage can be found in Appendix B.4.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
