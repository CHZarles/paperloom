# Blind Answer Review: cross_benchmark_score_ranking_partial_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Rank GAIA, WebArena, and SWE-bench from easiest to hardest using only their reported model success percentages.

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

## Ranking from easiest → hardest (by best reported model success rate)

| Rank | Benchmark | Best reported model | Success rate |
|------|-----------|---------------------|--------------|
| 1 (easiest) | **GAIA** | GPT-4 + plugins | **15%** overall |
| 2 | **WebArena** | GPT-4 agent | **14.41%** end-to-end task success |
| 3 (hardest) | **SWE-bench** | Claude 2 | **1.96%** resolved |

### Supporting figures from each paper

**GAIA** — Mialon et al. (2024): "human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins." On a per-level basis, "Even equipped with tools, GPT4 does not exceed a 30% success rate for the easiest of our tasks, and 0% for the hardest." [1] [2]

**WebArena** — Zhou et al. (2024): "the best GPT-4 agent performance is somewhat limited, with an end-to-end task success rate of only 14.41%, while the human performance is 78.24%." With plain CoT prompting, GPT-4 reaches 11.70% and GPT-3.5 reaches 8.75%. [3] [4]

**SWE-bench** — Jimenez et al. (2024): "The best performing model, Claude 2, is only able to resolve 1.96% of the issues" (using BM25 retrieval on 2,294 GitHub issues). [5]

### Note on comparability
These are not perfectly apples-to-apples: the three papers use different models, prompting/retrieval setups, and task formulations. The ranking above uses the **single best headline model success rate reported in each paper**, which is the closest like-for-like comparison available from the published numbers alone.

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] GAIA: a benchmark for General AI Assistants, Level 3, p. 3
[3] WebArena: A Realistic Web Environment for Building Autonomous Agents, 1 INTRODUCTION, p. 2
[4] WebArena: A Realistic Web Environment for Building Autonomous Agents, 5 RESULTS, p. 8
[5] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 5 RESULTS, p. 6

### Evidence cited by Answer A
#### A1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### A2. GAIA: a benchmark for General AI Assistants (p. 3, Level 3)

In spite of being successful at tasks that are dificult for humans, the most capable LLMs do poorly on <sup>GAIA</sup>. Even equipped with tools, GPT4 does not exceed a 30% success rate for the easiest of our tasks, and 0% for the hardest. In the meantime, the average success rate for human respondents is 92%. Consequently, a system capable of solving <sup>GAIA</sup> can be assessed in the context of t-AGI,<sup>4</sup> noting that humans typically take between 6 minutes for the simplest questions to 17 minutes for the most complex ones. From a related perspective, such system would arguably be a competent General AI within the framework recently proposed in Morris et al. (2023), which also appear to be the next milestone in AI research since ChatGPT (OpenAI, 2023) is one level below. This paper covers the composition of <sup>GAIA</sup>, its design choices, and explain how to craft questions and the associated challenges so that the community can further extend the benchmark to target emerging questions such as safety associated to tool use, or multi-modality. We also analyse the successes and shortcomings of some of the most capable assistants to date, illustrating the potential of augmenting LLMs. We release a developer set of 166 annotated questions and release the remaining 300 questions without annotations: the benchmark will be notably hosted as a leaderboard. We hope our methodology will help addressing the problem of open ended generation evaluation in NLP and beyond, and believe the successful resolution of <sup>GAIA</sup> would be an important milestone towards the next generation of AI systems.

#### A3. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 2, 1 INTRODUCTION)

We use this benchmark to evaluate several agents that can follow NL command and perform webbased tasks (§4). These agents are implemented in a few-shot in-context learning fashion with powerful large language models (LLMs) such as GPT-4 and PALM-2. Experiment results show that the best GPT-4 agent performance is somewhat limited, with an end-to-end task success rate of only 14.41%, while the human performance is 78.24%. We hypothesize that the limited performance of current LLMs stems from a lack of crucial capabilities such as active exploration and failure recovery to successfully perform complex tasks (§5.1). These outcomes underscore the necessity for further development towards robust and effective agents (LeCun, 2022) in WebArena.

#### A4. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 8, 5 RESULTS)

The main results are shown on the top of Table 2. GPT-4 (OpenAI, 2023) with CoT prompting achieves a modest end-to-end task success rate of 11.70%, which is significantly lower than the human performance of 78.24%. GPT-3.5 (OpenAI, 2022) with CoT prompting is only able to successfully perform 8.75% of the tasks. The explicit reasoning procedure is somewhat helpful, it brings 2.34% improvement over the version without it. Further, TEXT-BISON-001 (Anil et al., 2023) underperforms GPT-3.5, with a success rate of 5.05%. These results underline the inherent challenges and complexities of executing tasks that span long horizons, particularly in realistic environments such as WebArena.

#### A5. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 6, 5 RESULTS)

We report results for models using different retrieval mechanisms and prompting styles, then provide some analysis and insight into model performance and difficulty. We summarize models’ performance using BM25 retrieval in Table 5. Across the board, models struggle significantly to resolve issues. The best performing model, Claude 2, is only able to resolve 1.96% of the issues.

## Answer B
Declared outcome: `answered`

Using higher reported model success percentage as “easier,” the ranking is:

1. **GAIA** — **15%** for GPT-4 equipped with plugins [1]
2. **WebArena** — **14.41%** end-to-end task success rate for the best GPT-4 agent [2]
3. **SWE-bench** — **1.96%** resolved by the best-performing model, Claude 2, with BM25 retrieval [3]

So: **GAIA → WebArena → SWE-bench** from easiest to hardest.

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, 1 INTRODUCTION, p. 2
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 5 RESULTS, p. 6

### Evidence cited by Answer B
#### B1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### B2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 2, 1 INTRODUCTION)

We use this benchmark to evaluate several agents that can follow NL command and perform webbased tasks (§4). These agents are implemented in a few-shot in-context learning fashion with powerful large language models (LLMs) such as GPT-4 and PALM-2. Experiment results show that the best GPT-4 agent performance is somewhat limited, with an end-to-end task success rate of only 14.41%, while the human performance is 78.24%. We hypothesize that the limited performance of current LLMs stems from a lack of crucial capabilities such as active exploration and failure recovery to successfully perform complex tasks (§5.1). These outcomes underscore the necessity for further development towards robust and effective agents (LeCun, 2022) in WebArena.

#### B3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 6, 5 RESULTS)

We report results for models using different retrieval mechanisms and prompting styles, then provide some analysis and insight into model performance and difficulty. We summarize models’ performance using BM25 retrieval in Table 5. Across the board, models struggle significantly to resolve issues. The best performing model, Claude 2, is only able to resolve 1.96% of the issues.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
