# Blind Answer Review: agent_evaluation_recommendation_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Recommend one benchmark each for general assistants, realistic web tasks, software engineering, and policy-constrained tool-agent interaction.

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

## One benchmark per category (from this corpus)

| Category | Recommended benchmark | Why it fits |
|---|---|---|
| General assistants | **GAIA** (Mialon et al., ICLR 2024) | Explicitly designed as "a benchmark for General AI Assistants" with questions requiring reasoning, multi-modality, web browsing, and tool-use. [1] |
| Realistic web tasks | **WebArena** (Zhou et al., ICLR 2024) | "A Realistic Web Environment for Building Autonomous Agents" with fully functional websites across e-commerce, social forums, software development, and content management, plus a benchmark of long-horizon tasks emulating routine web activity. [2] |
| Software engineering | **SWE-bench** (Jimenez et al., ICLR 2024) | 2,294 tasks drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories, requiring multi-file edits, long-context processing, and execution-based validation. [3] |
| Policy-constrained tool-agent interaction | **τ-bench** (Yao et al., 2024) | Emulates dynamic conversations between a simulated user and a language agent "provided with domain-specific API tools and policy guidelines," with evaluation against annotated database goal states and a pass^k reliability metric. [4] |

### Notes on the picks

- **GAIA** is the cleanest match for "general assistants" because its design target is explicitly general-assistant use cases (daily personal tasks, science, general knowledge) and its questions are conceptually simple for humans yet hard for LL

Sources
[1] GAIA: a benchmark for General AI Assistants, 1 Introduction, p. 1
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, ABSTRACT, p. 1
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[4] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, Abstract, p. 1

### Evidence cited by Answer A

#### A1. GAIA: a benchmark for General AI Assistants (p. 1, 1 Introduction)

Large Language Models (LLMs) arguably open the way to general purpose systems. Indeed, the latest among them (OpenAI, 2023; Anthropic, 2023; Anil et al., 2023; Touvron et al., 2023) are fluent, knowledgeable, aligned to some extent with human preferences (Ouyang et al., 2022), and can be augmented (Mialon et al., 2023) with tools such as web browsers or code interpreters in a zero or few-shot setting (Brown et al., 2020). However, evaluating these systems is an open problem: given their emerging new capabilities, LLMs are regularly breaking AI benchmarks, at an ever-increasing rate (Kiela et al., 2023).

#### A2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 1, ABSTRACT)

With advances in generative AI, there is now potential for autonomous agents to manage daily tasks via natural language commands. However, current agents are primarily created and tested in simplified synthetic environments, leading to a disconnect with real-world scenarios. In this paper, we build an environment for language-guided agents that is highly realistic and reproducible. Specifically, we focus on agents that perform tasks on the web, and create an environment with fully functional websites from four common domains: e-commerce, social forum discussions, collaborative software development, and content management. Our environment is enriched with tools (e.g., a map) and external knowledge bases (e.g., user manuals) to encourage human-like task-solving. Building upon our environment, we release a set of benchmark tasks focusing on evaluating the functional correctness of task completions. The tasks in our benchmark are diverse, long-horizon, and designed to emulate tasks that humans routinely perform on the internet. We experiment with several baseline agents, integrating recent techniques such as reasoning before acting. The results demonstrate that solving complex tasks is challenging: our best GPT-4-based agent only achieves an end-to-end task success rate of 14.41%, significantly lower than the human performance of 78.24%. These results highlight the need for further development of robust agents, that current state-of-the-art large language models are far from perfect performance in these real-life tasks, and that WebArena can be used to measure such progress. Our code, data, environment reproduction resources, and video demonstrations are publicly available at https://webarena.dev/.

#### A3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### A4. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 1, Abstract)

Existing benchmarks do not test language agents on their interaction with human users or ability to follow domain-specific rules, both of which are vital for deploying them in real world applications. We propose τ -bench, a benchmark emulating dynamic conversations between a user (simulated by language models) and a language agent provided with domain-specific API tools and policy guidelines. We employ an efficient and faithful evaluation process that compares the database state at the end of a conversation with the annotated goal state. We also propose a new metric (pass^k) to evaluate the reliability of agent behavior over multiple trials. Our experiments show that even state-of-the-art function calling agents (like gpt-4o) succeed on < 50% of the tasks, and are quite inconsistent (pass^8 < 25% in retail). Our findings point to the need for methods that can improve the ability of agents to act consistently and follow rules reliably.

## Answer B

Declared outcome: `answered`

| Category | Recommended benchmark | Why this one |
|---|---|---|
| General assistants | **GAIA** | GAIA is explicitly framed as “a benchmark for General AI Assistants” and uses real-world questions requiring reasoning, multimodality, web browsing, and tool-use proficiency; it includes 466 questions and a held-out leaderboard split. [1] |
| Realistic web tasks | **WebArena** | WebArena is a highly realistic, standalone, reproducible web environment with fully functional web applications and organic data, and its benchmark has 812 examples mapping high-level natural-language intents to concrete web interactions with programmatic outcome-based evaluation. [2] |
| Software engineering | **SWE-bench** | SWE-bench consists of 2,294 software-engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories; models must edit a codebase to resolve an issue, often requiring changes across functions, classes, and files. [3] |
| Policy-constrained tool-agent interaction | **τ-bench** | τ-bench evaluates dynamic conversations between a simulated user and an agent equipped with domain-specific API tools and policy guidelines, measuring whether agents interact with users and follow domain-specific rules reliably. [4] It is built around realistic databases/APIs, domain-specific policy documents, diverse user scenarios, and ground-truth annotations, with retail and airline domains as its initial demonstrations. [5] |

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, 7 CONCLUSION, p. 9
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[4] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, Abstract, p. 1
[5] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 1 Introduction, p. 1

### Evidence cited by Answer B

#### B1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### B2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 9, 7 CONCLUSION)

We present WebArena, a highly-realistic, standalone, and reproducible web environment designed for the development and testing of autonomous agents. WebArena includes fully functional web applications and organic data from popular domains. Additionally, we curate a comprehensive benchmark consisting of 812 examples that focus on mapping high-level natural language intents into concrete web interactions. We also offer outcome-based evaluation that programmatically validate the tasks success. Our experiments show that even GPT-4 only achieves a limited end-to-end task success rate of 14.41%, significantly lagging behind the human performance of 78.24%. These findings underscore the need for future research to focus on enhancing the robustness and efficacy of autonomous agents within WebArena environment.

#### B3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### B4. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 1, Abstract)

Existing benchmarks do not test language agents on their interaction with human users or ability to follow domain-specific rules, both of which are vital for deploying them in real world applications. We propose τ -bench, a benchmark emulating dynamic conversations between a user (simulated by language models) and a language agent provided with domain-specific API tools and policy guidelines. We employ an efficient and faithful evaluation process that compares the database state at the end of a conversation with the annotated goal state. We also propose a new metric (pass^k) to evaluate the reliability of agent behavior over multiple trials. Our experiments show that even state-of-the-art function calling agents (like gpt-4o) succeed on < 50% of the tasks, and are quite inconsistent (pass^8 < 25% in retail). Our findings point to the need for methods that can improve the ability of agents to act consistently and follow rules reliably.

#### B5. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 1, 1 Introduction)

In this work, we introduce τ -bench (short for Tool-Agent-User Interaction Benchmark) to measure an agent’s ability to interact with (simulated) human users and programmatic APIs while following domain-specific policies in a consistent manner. τ -bench is built in a modular framework with (1) realistic databases and APIs, (2) domain-specific policy documents, and (3) instructions for diverse user scenarios and corresponding ground truth annotations. As a first demonstration, we focus on the realm of customer service and create two different domains where agents need to assist simulated users with diverse requests (τ -retail and τ -airline). We leverage the generative capabilities of language models (LMs) for data creation and realistic human user simulation [15] in conjunction with manual annotation and verification.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
