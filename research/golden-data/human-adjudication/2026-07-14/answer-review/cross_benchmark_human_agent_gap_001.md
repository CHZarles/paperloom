# Blind Answer Review: cross_benchmark_human_agent_gap_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Compare the reported human-agent gaps in GAIA and WebArena, but do not imply that their percentages are directly interchangeable.

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

| Benchmark | Reported human performance | Reported agent performance | Human–agent gap | What the percentage means |
|---|---:|---:|---:|---|
| GAIA | 92% | 15% for GPT-4 equipped with plugins | **77 percentage points** | Accuracy on real-world questions requiring reasoning, multimodality, web browsing, and tool use; the paper notes the GPT-4+plugins score should be treated as an oracle because plugins were manually chosen by question [1] [2] |
| WebArena | 78.24% | 14.41% for the best GPT-4 agent | **63.83 percentage points** | End-to-end task success rate for web-based tasks specified by natural-language commands; human performance was measured with five CS graduate students on one task from each of 170 templates [3] [4] |

**Comparison:** both papers report large gaps between humans and agents, with GAIA’s headline gap larger: **77 pp vs. 63.83 pp**. But these percentages should not be treated as directly interchangeable: GAIA reports question-answering accuracy over factual, tool-using assistant questions, whereas WebArena reports end-to-end success at executing web tasks in a realistic web environment [1] [3]. So the safer reading is: *both benchmarks found substantial human-over-agent gaps under their own evaluation setups*, not that a point of GAIA accuracy equals a point of WebArena task-success rate.

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] GAIA: a benchmark for General AI Assistants, 4 LLMs results on GAIA, p. 8
[3] WebArena: A Realistic Web Environment for Building Autonomous Agents, 1 INTRODUCTION, p. 2
[4] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 7

### Evidence cited by Answer A
#### A1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### A2. GAIA: a benchmark for General AI Assistants (p. 8, 4 LLMs results on GAIA)

LLMs, Human and Search engine scores and time to answer for GAIA Figure 4 Scores and time to answer per method and level. As stated in the main text, GPT4 + plugins score should be seen as an oracle since the plugins were chosen manually depending on the question. Human score refers to the score obtained by our annotators when validating the questions.

#### A3. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 2, 1 INTRODUCTION)

We use this benchmark to evaluate several agents that can follow NL command and perform webbased tasks (§4). These agents are implemented in a few-shot in-context learning fashion with powerful large language models (LLMs) such as GPT-4 and PALM-2. Experiment results show that the best GPT-4 agent performance is somewhat limited, with an end-to-end task success rate of only 14.41%, while the human performance is 78.24%. We hypothesize that the limited performance of current LLMs stems from a lack of crucial capabilities such as active exploration and failure recovery to successfully perform complex tasks (§5.1). These outcomes underscore the necessity for further development towards robust and effective agents (LeCun, 2022) in WebArena.

#### A4. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 7, 3.2 EVALUATION ANNOTATION)

Human Performance We sample one task from each of the 170 templates and ask five computer science graduate students to perform these tasks. The human performance is on the right. Overall, the human annotators complete 78.24% of the tasks, with lower performance on information-seeking tasks. Through examining the recorded trajectories, we found that 50% of the failures are due to misinterpreting the intent (e.g., providing travel distance when asked for travel time), incomplete answers (e.g., providing only name when asked for name and email), and incomplete executions (e.g., partially filling the product information), while the remaining instances have more severe failures, where the executions are off-target. More discussions on human annotations can be found in §A.5.

## Answer B
Declared outcome: `answered`

## Reported Human–Agent Gaps: GAIA vs WebArena

### Headline figures as reported by each paper

| Benchmark | Human score | Agent score | Headline gap |
|---|---|---|---|
| **GAIA** | 92% (respondents) [1](https://example.invalid/ev_cc589bd061af6a6d) | 15% (GPT-4 with plugins) [1](https://example.invalid/ev_cc589bd061af6a6d) | ~77 percentage points |
| **WebArena** | 78.24% (overall task success rate) [2](https://example.invalid/ev_57966459e86942f2) | 11.70% (GPT-4 with CoT prompting) [2](https://example.invalid/ev_57966459e86942f2) | ~66.54 percentage points |

Both papers headline a single "human vs. frontier-agent" number in their main results, but the two numbers are not interchangeable for several concrete reasons documented in the source papers.

### Why the two reported gaps are not directly interchangeable

1. **Tasks measured are different in kind.** GAIA poses real-world questions requiring "reasoning, multi-modality handling, web browsing, and generally tool-use proficiency," and the comparison is on **question-level accuracy** [1](https://example.invalid/ev_cc589bd061af6a6d). WebArena evaluates **end-to-end task success rate** on web-navigation tasks spanning long horizons across realistic sites [2](https://example.invalid/ev_57966459e86942f2).

2. **Agent configuration defining the gap differs.** GAIA's 15% is for GPT-4 **equipped with plugins** [1](https://example.invalid/ev_cc589bd061af6a6d). WebArena's 11.70% is for GPT-4 **with chain-of-thought prompting** only [2](https://example.invalid/ev_57966459e86942f2). The two agent setups are not matched, so the relative size of the gap partly reflects the agent configuration rather than inherent benchmark difficulty.

3. **Human evaluation protocol differs.** GAIA's 92% is the validation-phase respondent score, decomposed across difficulty levels (Level 1: 94%, Level 2: 92%, with lower Level 3); a nearby footnote also separately reports "Human non-specialist accuracy … only 34.5%" and "Expert-level human performance is estimated at 89.8%" against MMLU-style numbers [3](https://example.invalid/ev_eb049547e33020e9). WebArena's 78.24% is a timed per-task success rate averaged across 110 seconds of annotator effort, split into **info** tasks (74.68%) and **others** tasks (81.32%) [4](https://example.invalid/ev_6935522ac780edcc).

4. **Difficulty distribution differs.** GAIA is structured into three explicit difficulty levels with per-level human scores; WebArena reports a single overall rate plus an info/others split. The "human" bar aggregates across structurally different difficulty regimes in each paper.

5. **Framing differs.** GAIA frames its gap as evidence that its questions are "conceptually simple for humans yet challenging for most advanced AIs," contrasting with LLMs surpassing humans on professional-skill benchmarks [1](https://example.invalid/ev_cc589bd061af6a6d). WebArena frames its gap as evidence of "the inherent challenges and complexities of executing tasks that span long horizons, particularly in realistic environments" [2](https://example.invalid/ev_57966459e86942f2).

### What can be said safely

- Both papers report a **large** human-vs-frontier-agent gap on their respective benchmarks, in roughly the same order of magnitude (tens of percentage points).
- GAIA's reported gap (~77 pp) is **numerically larger** than WebArena's reported gap (~66.54 pp) using the headline numbers, but the difference is sensitive to which human subgroup, agent configuration, and task subset each paper uses, and should not be read as "GAIA is harder than WebArena" without further matched re-evaluation.
- A direct apples-to-apples comparison would require holding the agent setup, human evaluation protocol, and task distribution constant — none of which holds across the two papers' headline numbers.

## Research document (citation source reference)
(no reference document available)

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, 5 RESULTS, p. 8
[3] GAIA: a benchmark for General AI Assistants, 1 Introduction, p. 1
[4] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 7

### Evidence cited by Answer B
#### B1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### B2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 8, 5 RESULTS)

The main results are shown on the top of Table 2. GPT-4 (OpenAI, 2023) with CoT prompting achieves a modest end-to-end task success rate of 11.70%, which is significantly lower than the human performance of 78.24%. GPT-3.5 (OpenAI, 2022) with CoT prompting is only able to successfully perform 8.75% of the tasks. The explicit reasoning procedure is somewhat helpful, it brings 2.34% improvement over the version without it. Further, TEXT-BISON-001 (Anil et al., 2023) underperforms GPT-3.5, with a success rate of 5.05%. These results underline the inherent challenges and complexities of executing tasks that span long horizons, particularly in realistic environments such as WebArena.

#### B3. GAIA: a benchmark for General AI Assistants (p. 1, 1 Introduction)

<sup>1</sup>GPT4 does 86.4% on MMLU. Human non-specialist accuracy on the benchmark is only 34.5% Expert-level human performance is estimated at 89.8%.

#### B4. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 7, 3.2 EVALUATION ANNOTATION)

Avg. Time | 110s | Success Rateinfo | 74.68% | Success Rateothers | 81.32% | Success Rateall | 78.24%

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
