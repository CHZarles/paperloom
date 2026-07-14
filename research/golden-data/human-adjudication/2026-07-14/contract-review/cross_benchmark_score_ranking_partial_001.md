# Golden Contract Review: cross_benchmark_score_ranking_partial_001

## Conversation

- **user**: Rank GAIA, WebArena, and SWE-bench from easiest to hardest using only their reported model success percentages.

## Authored Expectation

```yaml
outcome: partial
papers:
  required:
  - gaia_2024
  - webarena_2024
  - swebench_2024
evidence:
  required:
  - gaia_human_gpt4_gap
  - webarena_gpt4_human_gap
  - swebench_claude2_baseline
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

### gaia_human_gpt4_gap

Paper: `gaia_2024`, authored selector: `human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins`

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

### webarena_gpt4_human_gap

Paper: `webarena_2024`, authored selector: `best GPT-4-based agent only achieves an end-to-end task success rate of 14.41%, significantly lower than the human performance of 78.24%`

With advances in generative AI, there is now potential for autonomous agents to manage daily tasks via natural language commands. However, current agents are primarily created and tested in simplified synthetic environments, leading to a disconnect with real-world scenarios. In this paper, we build an environment for language-guided agents that is highly realistic and reproducible. Specifically, we focus on agents that perform tasks on the web, and create an environment with fully functional websites from four common domains: e-commerce, social forum discussions, collaborative software development, and content management. Our environment is enriched with tools (e.g., a map) and external knowledge bases (e.g., user manuals) to encourage human-like task-solving. Building upon our environment, we release a set of benchmark tasks focusing on evaluating the functional correctness of task completions. The tasks in our benchmark are diverse, long-horizon, and designed to emulate tasks that humans routinely perform on the internet. We experiment with several baseline agents, integrating recent techniques such as reasoning before acting. The results demonstrate that solving complex tasks is challenging: our best GPT-4-based agent only achieves an end-to-end task success rate of 14.41%, significantly lower than the human performance of 78.24%. These results highlight the need for further development of robust agents, that current state-of-the-art large language models are far from perfect performance in these real-life tasks, and that WebArena can be used to measure such progress. Our code, data, environment reproduction resources, and video demonstrations are publicly available at https://webarena.dev/.

### swebench_claude2_baseline

Paper: `swebench_2024`, authored selector: `best-performing model, Claude 2, is able to solve a mere 1.96% of the issues`

Language models have outpaced our ability to evaluate them effectively, but for their future development it is essential to study the frontier of their capabilities. We find real-world software engineering to be a rich, sustainable, and challenging testbed for evaluating the next generation of language models. To this end, we introduce SWE-bench, an evaluation framework consisting of 2,294 software engineering problems drawn from real GitHub issues and corresponding pull requests across 12 popular Python repositories. Given a codebase along with a description of an issue to be resolved, a language model is tasked with editing the codebase to address the issue. Resolving issues in SWE-bench frequently requires understanding and coordinating changes across multiple functions, classes, and even files simultaneously, calling for models to interact with execution environments, process extremely long contexts and perform complex reasoning that goes far beyond traditional code generation tasks. Our evaluations show that both state-ofthe-art proprietary models and our fine-tuned model SWE-Llama can resolve only the simplest issues. The best-performing model, Claude 2, is able to solve a mere 1.96% of the issues. Advances on SWE-bench represent steps towards LMs that are more practical, intelligent, and autonomous.

## Human Decision

Choose `keep`, `revise`, or `drop`, then answer:

1. Is the expected outcome the uniquely reasonable action?
2. Are the required papers necessary?
3. Do the required evidence spans cover every requested dimension?
4. Should equivalent evidence from the same paper be accepted?
