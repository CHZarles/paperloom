# Blind Answer Review: benchmark_topic_change_followup_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Which benchmark should I use for a general AI assistant?
- **assistant**: GAIA is the direct fit for general-assistant evaluation.
- **user**: Actually, I need to evaluate an agent that fixes GitHub issues.

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

Use **SWE-bench** for an agent that fixes GitHub issues.

It is built specifically around real GitHub issues and corresponding pull requests: the benchmark has **2,294 software engineering problems** from **12 popular Python repositories**, where the model receives a codebase plus an issue description and must edit the codebase to resolve it [1]. The task is explicitly to generate a pull request addressing the issue and passing related tests [2].

For scoring, SWE-bench applies the generated patch, runs the relevant unit/system tests, and reports the **percentage of task instances resolved** [3].

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2 SWE-BENCH, p. 2
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3

### Evidence cited by Answer A

#### A1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### A2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2 SWE-BENCH)

SWE-bench is a benchmark featuring GitHub issues from popular repositories that report bugs or request new features, and pull requests that make changes to the repository to resolve these issues. The task is to generate a pull request that addresses a given issue and passes tests related to the issue.

#### A3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Evaluation metrics. To evaluate a proposed solution, we apply the generated patch, using unix’s patch program, to the codebase and then execute the unit and system tests associated with the task instance. If the patch applies successfully and all of these tests pass we consider the proposed solution to have successfully resolved the issue. The metric for our benchmark is the percentage of task instances that are resolved. Additional technical details in Appendix A.4.

## Answer B

Declared outcome: `answered`

For evaluating an agent that fixes GitHub issues, **SWE-bench** is the direct fit.

SWE-bench is "a benchmark featuring GitHub issues from popular repositories that report bugs or request new features, and pull requests that make changes to the repository to resolve these issues. The task is to generate a pull request that addresses a given issue and passes tests related to the issue." [1]

Key properties that match your use case:

- **Real GitHub issues**: 2,294 problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. [2]
- **Realistic setting**: "a realistic setting that utilizes user-submitted issues and solutions" rather than contrived programming puzzles. [3]
- **Execution-based evaluation**: "a robust framework for execution-based evaluation" — the agent's patch is validated by running tests tied to the issue. [3]
- **Continuous updates**: "the ability to continuously update the benchmark with new instances, requiring minimal human intervention." [3]

If you need codebases beyond the original 12 Python repos or different filtering criteria (e.g., a human-verified subset), let me know and I can check what's available in the corpus.

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2 SWE-BENCH, p. 2
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, ABSTRACT, p. 1
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 1 INTRODUCTION, p. 2

### Evidence cited by Answer B

#### B1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2 SWE-BENCH)

SWE-bench is a benchmark featuring GitHub issues from popular repositories that report bugs or request new features, and pull requests that make changes to the repository to resolve these issues. The task is to generate a pull request that addresses a given issue and passes tests related to the issue.

#### B2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 1, ABSTRACT)

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

#### B3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 1 INTRODUCTION)

SWE-bench offers several advantages over existing LM programming benchmarks. These include, a realistic setting that utilizes user-submitted issues and solutions, diverse inputs featuring unique code problems from 12 repositories, a robust framework for execution-based evaluation, and the ability to continuously update the benchmark with new instances, requiring minimal human intervention.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
