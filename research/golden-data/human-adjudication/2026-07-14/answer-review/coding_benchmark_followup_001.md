# Blind Answer Review: coding_benchmark_followup_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Help me choose one benchmark: GAIA for general assistants, WebArena for web agents, or SWE-bench for coding agents.
- **assistant**: Which target matters most: general assistance, web interaction, or software engineering?
- **user**: The coding one.

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

Choose **SWE-bench**.

Since your priority is **coding agents**, SWE-bench is the best fit: it is explicitly an evaluation framework for real-world software engineering problems drawn from GitHub issues and pull requests, where a model edits a codebase to resolve an issue. [1] It also emphasizes large, complex codebases and issue descriptions that require software-engineering skills beyond traditional code-generation benchmarks. [2]

The other two are aimed elsewhere: **WebArena** focuses on agents performing tasks on the web in functional website environments, [3] while **GAIA** is a broader benchmark for general AI assistants involving reasoning, multimodality, web browsing, and tool use. [4]

**Recommendation: use SWE-bench.**

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.3 FEATURES OF SWE-BENCH, p. 3
[3] WebArena: A Realistic Web Environment for Building Autonomous Agents, ABSTRACT, p. 1
[4] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1

### Evidence cited by Answer A

#### A1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### A2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.3 FEATURES OF SWE-BENCH)

Real-world software engineering tasks. Since each task instance in SWE-bench consists of a large and complex codebase and a description of a relevant issue, solving SWE-bench requires demonstrating sophisticated skills and knowledge possessed by experienced software engineers but are not commonly evaluated in traditional code generation benchmarks.

#### A3. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 1, ABSTRACT)

With advances in generative AI, there is now potential for autonomous agents to manage daily tasks via natural language commands. However, current agents are primarily created and tested in simplified synthetic environments, leading to a disconnect with real-world scenarios. In this paper, we build an environment for language-guided agents that is highly realistic and reproducible. Specifically, we focus on agents that perform tasks on the web, and create an environment with fully functional websites from four common domains: e-commerce, social forum discussions, collaborative software development, and content management. Our environment is enriched with tools (e.g., a map) and external knowledge bases (e.g., user manuals) to encourage human-like task-solving. Building upon our environment, we release a set of benchmark tasks focusing on evaluating the functional correctness of task completions. The tasks in our benchmark are diverse, long-horizon, and designed to emulate tasks that humans routinely perform on the internet. We experiment with several baseline agents, integrating recent techniques such as reasoning before acting. The results demonstrate that solving complex tasks is challenging: our best GPT-4-based agent only achieves an end-to-end task success rate of 14.41%, significantly lower than the human performance of 78.24%. These results highlight the need for further development of robust agents, that current state-of-the-art large language models are far from perfect performance in these real-life tasks, and that WebArena can be used to measure such progress. Our code, data, environment reproduction resources, and video demonstrations are publicly available at https://webarena.dev/.

#### A4. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

## Answer B

Declared outcome: `answered`

Pick **SWE-bench** for evaluating coding agents. It fits your stated target — software engineering — while the other two benchmarks target different capabilities (general assistant reasoning across diverse real-world tasks, and web-environment interaction).

What SWE-bench actually tests, from the original paper:

- The dataset contains **2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories**. [1]
- Each task gives the model **an issue text description and a complete codebase**, and the model must produce an edit — represented as a patch file — that resolves the issue. [2]
- Evaluation is execution-based: the generated patch is applied with `patch`, and the task's unit and system tests are run; **the metric is the percentage of task instances whose patch applies and whose tests all pass**. [3]
- Resolving these issues "frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks." [1]

If you want to go further, I can pull the data-construction and harness details from the appendix, or look up follow-on variants in the corpus.

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3

### Evidence cited by Answer B

#### B1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### B2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Model input. A model is given an issue text description and a complete codebase. The model is then tasked to make an edit to the codebase to resolve the issue. In practice, we represent edits as patch files, which specify which lines in the codebase to modify in order to resolve the issue.

#### B3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Evaluation metrics. To evaluate a proposed solution, we apply the generated patch, using unix’s patch program, to the codebase and then execute the unit and system tests associated with the task instance. If the patch applies successfully and all of these tests pass we consider the proposed solution to have successfully resolved the issue. The metric for our benchmark is the percentage of task instances that are resolved. Additional technical details in Appendix A.4.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
