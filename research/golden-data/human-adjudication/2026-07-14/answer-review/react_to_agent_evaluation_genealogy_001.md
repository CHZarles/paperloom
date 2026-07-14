# Blind Answer Review: react_to_agent_evaluation_genealogy_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Trace only directly verified relationships from ReAct into later agent-evaluation papers in this corpus. Do not infer influence from topical similarity.

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

## Directly verified relationships: ReAct → later agent-evaluation papers

The corpus contains 9 papers. The later (2024) agent-evaluation candidates are: AgentBench, AgentBoard, GAIA, MINT, SWE-bench, τ-bench, ToolSandbox, and WebArena. The corpus citation graph formally tracks **exactly two** direct edges originating from these papers and pointing at the ReAct paper (Yao et al., ICLR 2023), whose abstract defines it as generating "both reasoning traces and task-specific actions in an interleaved manner" for question answering and interactive decision making [1].

### 1. AgentBench (Liu et al., ICLR 2024) → ReAct
- **Edge type in corpus graph:** cites the ReAct reasoning+action method.
- **Verified textual evidence:**
  - §5 Related Work calls ReAct "a pioneer work to combine CoT reasoning and actions in agent tasks" [2].
  - §G.1 Dataset Details states that it follows ReAct (Yao et al.) and adopts the few-shot examples and prompts from the corresponding repository [3], with the repository URL pointing to the ReAct GitHub repo [4].
- **Nature of the relationship:** AgentBench adopts ReAct's few-shot examples and prompting template as part of its agent-evaluation protocol, and treats ReAct as the foundational work it situates itself against.

### 2. τ-bench (Yao et al., arXiv 2024) → ReAct
- **Edge type in corpus graph:** evaluates the ReAct agent construct.
- **Verified textual evidence:**
  - §1 Introduction: agents built with simple LM constructs like function calling or ReAct perform poorly, motivating more sophisticated architectures [5].
  - §5 Experiments: explicitly tests text-formatted ReAct alongside an Act-only ablation [6].
  - §5.1 Main results: compares the ReAct column against the Act-only column, observing that adding reasoning traces still consistently helps [7].
- **Nature of the relationship:** τ-bench uses ReAct as an explicit baseline agent construct and includes a controlled Act-only ablation to isolate the contribution of interleaved reasoning traces. (Note: Shunyu Yao is an author of both papers.)

### Textual ReAct mentions WITHOUT a formal citation edge
Two other later agent-evaluation papers textually reference ReAct by name but have no formally tracked citation edge to it in the corpus graph:

- **AgentBoard (NeurIPS Oral 2024)** discusses "ReAct (Yao et al., 2023)" as an alternative framework in its ablation study and runs an explicit Act-vs-ReAct comparison on GPT-3.5-Turbo, concluding that interleaved thoughts give inconsistent improvement under long-horizon interactions [8]. Despite this direct textual reference, the corpus citation graph records no edge between AgentBoard and ReAct.
- **ToolSandbox (arXiv 2024)** describes an additional experiment "comparing the effect of ReAct prompting in tool use agents against the minimal prompting design" [9], and reports that "ReAct has minimal effect to Claude model family, and a small gain for GPT-4 model family" [10]. Again, the corpus citation graph records no formal edge.

### Papers with no direct textual reference to ReAct
The remaining later agent-evaluation papers do not mention ReAct at all in the corpus text:
- **GAIA (ICLR 2024)** discusses general-assistant evaluation and related benchmarks (AgentBench, ToolQA, Gentopia, Gorilla/API-Bank, APIBench, OpenAGI) but never cites ReAct [11] [12].
- **SWE-bench (ICLR 2024)** cites a "Yao et al., 2022" reference in a list of "interactive setting[s] featuring tasks that require multiple steps to solve" alongside WebArena and other interactive environments [13]; this reference concerns the WebShop line of work, not ReAct.
- **WebArena (ICLR 2024)** cites "Yao et al., 2022a" alongside prior environments such as Shi et al., Anderson et al., and Shridhar et al. [14], and its acknowledgements thank Shunyu Yao for providing raw Amazon product data in Webshop [15] — these are WebShop references, not ReAct.
- **MINT (ICLR 2024)** cites "Yao et al. (2022)" in an ALFWorld in-context-example note [16]; whether this resolves to ReAct or to another Yao et al. ALFWorld paper is not unambiguous from the corpus text alone, and no formal citation-graph edge exists.

### Summary
Only **AgentBench** and **τ-bench** have a corpus-graph-verified direct relationship to ReAct: AgentBench adopts ReAct's prompting template as part of its evaluation protocol, and τ-bench evaluates ReAct as a baseline agent construct with an Act-only ablation. AgentBoard and ToolSandbox textually reference ReAct by name (explicit ablation/comparison in AgentBoard; ReAct-prompting comparison in ToolSandbox) but lack a formal citation-graph edge. GAIA contains no ReAct reference; MINT, SWE-bench, and WebArena's textual "Yao et al." references resolve to other works (WebShop and prior interactive-environment benchmarks) rather than to ReAct.

Sources
[1] ReAct: Synergizing Reasoning and Acting in Language Models, ABSTRACT, p. 1
[2] AgentBench: Evaluating LLMs as Agents, 5 RELATED WORK, p. 9
[3] AgentBench: Evaluating LLMs as Agents, G.1 DATASET DETAILS, p. 38
[4] AgentBench: Evaluating LLMs as Agents, G.2 PROMPT EXAMPLE, p. 39
[5] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 1 Introduction, p. 2
[6] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5 Experiments, p. 6
[7] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5.1 Main results, p. 7
[8] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, F Ablation Study of Agent Framework, p. 16
[9] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, D.1 Prompting Experiment, p. 22
[10] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, D.1 Prompting Experiment, p. 22
[11] GAIA: a benchmark for General AI Assistants, A Extended related work, p. 17
[12] GAIA: a benchmark for General AI Assistants, 2 Related work, p. 3
[13] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 6 RELATED WORK, p. 9
[14] WebArena: A Realistic Web Environment for Building Autonomous Agents, WE BAR E N A: A REALISTIC WEB ENVIRONMENT FOR BUILDING AUTONOMOUS AGENTS, p. 1
[15] WebArena: A Realistic Web Environment for Building Autonomous Agents, ACKNOWLEDGEMENT, p. 10
[16] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, F.5.1 DECISION-MAKING, p. 30

### Evidence cited by Answer A
#### A1. ReAct: Synergizing Reasoning and Acting in Language Models (p. 1, ABSTRACT)

While large language models (LLMs) have demonstrated impressive performance across tasks in language understanding and interactive decision making, their abilities for reasoning (e.g. chain-of-thought prompting) and acting (e.g. action plan generation) have primarily been studied as separate topics. In this paper, we explore the use of LLMs to generate both reasoning traces and task-specific actions in an interleaved manner, allowing for greater synergy between the two: reasoning traces help the model induce, track, and update action plans as well as handle exceptions, while actions allow it to interface with and gather additional information from external sources such as knowledge bases or environments. We apply our approach, named ReAct, to a diverse set of language and decision making tasks and demonstrate its effectiveness over state-of-the-art baselines in addition to improved human interpretability and trustworthiness. Concretely, on question answering (HotpotQA) and fact verification (Fever), ReAct overcomes prevalent issues of hallucination and error propagation in chain-of-thought reasoning by interacting with a simple Wikipedia API, and generating human-like task-solving trajectories that are more interpretable than baselines without reasoning traces. Furthermore, on two interactive decision making benchmarks (ALFWorld and WebShop), ReAct outperforms imitation and reinforcement learning methods by an absolute success rate of 34% and 10% respectively, while being prompted with only one or two in-context examples.

#### A2. AgentBench: Evaluating LLMs as Agents (p. 9, 5 RELATED WORK)

LLM-as-Agent. In pre-LLM era, text game environments such as TextWorld (Côté et al., 2019), Jericho (Hausknecht et al., 2020), and LIGHT (Urbanek et al., 2019) are dominant in language agent study which bases on BERT (Devlin et al., 2019) and reinforcement learning. With the advent of LLMs, the study of LLM agents begins to thrive (Huang et al., 2022), especially after Chain-of-Thought (Wei et al., 2022b) came out. ReAct (Yao et al., 2023b) is a pioneer work to combine CoT reasoning and actions in agent tasks. Later, a multitude of advanced reasoning strategies (Kim et al., 2023; Shinn et al., 2023; Wang et al., 2023d; Liu et al., 2023; Yao et al., 2023a; Gu et al., 2023) and applications including frameworks (Richards, 2023; Nakajima, 2023; age, 2023) and multi-agents (Park et al., 2023; Hong et al., 2023; Wu et al., 2023) for LLM-as-Agent have emerged and arouse much public interest. Nevertheless, limited datasets and models and available on the topic, without a standard and comprehensive benchmark. AGENTBENCH presents the first systematic benchmark for evaluating LLM-as-Agent with a broad coverage of tasks and available LLMs. Additionally, it also initiates the idea of adopting agent tasks to measure LLM performance.

#### A3. AgentBench: Evaluating LLMs as Agents (p. 38, G.1 DATASET DETAILS)

Evaluation Setup. Due to the inherent complexity of the problem and the high standards required for the output format, we employ a 1-shot evaluation setting. For each category of problem, we use one relatively simple and complete interact processes of the same category from the training set as an example. Following ReAct (Yao et al., 2023b), we adopt the few-shot examples and prompts in corresponding repository<sup>5</sup>. Additionally, if LLM output format is invalid, we use the BLEU metric to assess the similarity of the output to all valid action options. The option with the highest similarity will be chosen as the action of the model for this round.

#### A4. AgentBench: Evaluating LLMs as Agents (p. 39, G.2 PROMPT EXAMPLE)

<sup>5</sup>https://github.com/ysymyth/ReAct

#### A5. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 2, 1 Introduction)

Our experiments reveal that agents built with simple LM constructs (like function calling or ReAct) perform poorly, highlighting the need for more sophisticated agent architectures. For instance, even state-of-the-art LMs like gpt-4o achieve low task success rates (pass^1) using function calling (∼61% on τ -retail and ∼35% on τ -airline). With increasing k, the chance of consistently solving a task drops rapidly, to as low as ∼25% for pass^8 on τ -retail for the same model. This showcases the fragile nature of such agents in handling stochasticity and partial information, which is common in human-agent interaction. Upon analyzing the failure cases, we find that current agents struggle with complex reasoning over databases, understanding and following ad-hoc policies, and handling compound (more than one) requests. We hope that τ -bench enables the evaluation and development of more consistent and capable agents for real-world digital tasks involving human interaction.

#### A6. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 6, 5 Experiments)

Methods. Our main method for building the agent is through the use of function calling (FC), which is natively supported by all tested LMs except Llama-3. In FC mode, the model’s system prompt is set to be the domain policy, and at each turn, the model autonomously decides to generate a user response message or a tool call. We also test text-formatted ReAct [26] and its Act-only ablation, where the model is instructed to zero-shot generate “Thought: {some reasoning} Action: {some JSON format action argument}” or only the action part. Notably, some agent methods are not suitable for a user-in-the-loop setup, e.g., self-reflection [19] is unrealistic as real-world agents only have one chance to serve the user, and planning approaches [25] might be too slow to help a user in real time.

#### A7. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 7, 5.1 Main results)

Method comparison. Figure 3 shows that natively supported function calling consistently outperforms text-formatted agent methods with the state-of-the-art models. For text-formatted agent methods, adding reasoning traces still consistently helps (compare ReAct vs. Act columns) as it helps bridge the gap between observations and actions that have unfamiliar formats. We have also experimented with adding a “think” function for function-calling agents, but it did not boost performance, perhaps because most FC models have not been trained toward such reasoning.

#### A8. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 16, F Ablation Study of Agent Framework)

One popular alternative for our framework is to use ReAct (Yao et al., 2023) rather than Act, which uses interleaved thoughts in addition to actions to boost planning. However, our experiments on GPT-3.5-Turbo show inconsistent improvement of ReAct over performance of Act, as shown in Table 9. We hypothesized that this is due to we test long-term interactions of LLM Agents up to 30 interactions, and adding thoughts would pressure context length, thus leading to performance drop. Therefore, we use Act rather than ReAct in our benchmark.

#### A9. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 22, D.1 Prompting Experiment)

We conducted additional experiments comparing the effect of ReAct prompting in tool use agents against the minimal prompting design presented in Figure 8, with 4 representative models shown below.

#### A10. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 22, D.1 Prompting Experiment)

From Table 9, we can see that, ReAct has minimal effect to Claude model family, and a small gain for GPT4 model family. This is likely due to the fact that, the baseline ToolSandbox setup, which allows the model to interact with execution environment, recover from error and confirm with simulated user already provides a reasoning process to the model through user and execution environment interactions, additional natural language reasoning would only provide marginal gains. While ReAct provide gains in some cases, relative ranking between models still stands. This backs up our claim in Section 4, where we considered prompt engineering gains orthogonal to the innate model capability surfaced by simpler prompting.

#### A11. GAIA: a benchmark for General AI Assistants (p. 17, A Extended related work)

Large Language Models as General Assistants. Several avenues have been explored to turn LLMs into general-purpose assistants: (i) using single agent LLMs with better capabilities through Chain of Thought prompting or equivalent mechanisms, such as GPT-Engineer (Osika, 2023), AutoGPT (Yang et al., 2023); (ii) using multiple agent LLMs to debate and together reach better conclusions to answer user queries (Li et al., 2023a; Hong et al., 2023; Chan et al., 2023; Talebirad and Nadiri, 2023); (iii) using single agent LLMs augmented with specific tools, such as Blender Bot 3 (Shuster et al., 2022), BOLAA (Liu et al., 2023b) and AssistGPT (Gao et al., 2023) extending LLMs with planning components, Socratic Models (Zeng et al., 2022) or Visual ChatGPT (Wu et al., 2023) extended with multimodal models, WebGPT Nakano et al. (2021) fine-tuned for web-search, or a collection of tools and APIs, such as Toolformer (Schick et al., 2023) fine-tuned for general tool usage, ViperGPT (Sur´ıs et al., 2023) using coding capabilites to generate correct API calls, HuggingGPT (Shen et al., 2023) leveraging calls to the HuggingFace ecosystem to extend its LLM with other ML models capabilities, or even (iv) providing full new API/tooling libraries, such as the OpenAI plugins, SemanticKernel (Microsoft, 2023), Langchain (Chase, 2022) and MiniChain (Rush, 2023).

#### A12. GAIA: a benchmark for General AI Assistants (p. 3, 2 Related work)

Evaluating General Assistants. While there is ongoing efort to turn Large Language Models into generalpurpose assistants (see our discussion in Appendix A), appropriate evaluation is lagging behind. Most evaluations rely on the use of closed systems, specific API calls, and a given “correct way” to attain the answer, or simply repurpose existing evaluation datasets. ToolQA (Zhuang et al., 2023) or Gentopia (Xu et al., 2023a) for example combine existing datasets with human annotations (MMLU, MATH, etc.) at the risk of contamination during training, and without ensuring tool usage is actually tested. Gorilla (Patil et al., 2023) introduces APIBench, which tests how well an agent like system calls its specific API, similarly to API-Bank (Li et al., 2023b), which provides an API pool to help the LLM during its evaluation. AgentBench (Liu et al., 2023a) is more general, and provides a number of closed box environments inside which assistant LLMs can be deployed to answer user queries (from Unix shells to WebShopping APIs). However, because such evaluations rely on closed environments, they risk evaluating how well the assistants have learned to use specific APIs, instead of more general results grounded in real world interactions. By opposition, <sup>GAIA</sup> does not specify possible APIs, and relies on interactions with the real world. OpenAGI (Ge et al., 2023) introduces both a platform and a benchmark, made of a number of multi-steps tasks across modalities and capabilities, and is closer to our work. The core diference with <sup>GAIA</sup> is that their tasks focus on current model capabilities rather than upcoming advancements.

#### A13. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 9, 6 RELATED WORK)

Evaluation of LMs. Several recent works for evaluating LMs have either proposed a collection of mutually distinct tasks spanning across multiple domains (Hendrycks et al., 2021; Liang et al., 2022; Srivastava et al., 2023) or turned to the web as an interactive setting featuring tasks that require multiple steps to solve (Yao et al., 2022; Zhou et al., 2023; Deng et al., 2023; Liu et al., 2023d). There are several drawbacks with such a “potpourri” style setup. First, each task tends to narrowly focus on one or a few skills, resulting in challenges that are typically too simple, pigeonhole the model into a reduced role, and do not provide models with the bandwidth to exercise their versatility or potentially demonstrate new abilities (Srivastava et al., 2023). Consequently, a model’s performance on such task conglomerations may not yield actionable, deep insights regarding its capabilities and how to improve them (Schlangen, 2019; Mart´ınez-Plumed et al., 2021; Bowman & Dahl, 2021). SWEbench addresses these shortcomings, as our work demonstrates that it is significantly challenging, presents a wide range of possibilities for improving LMs to solve this task, and is easy to refresh over time with new task instances, each of which introduce novel, nuanced, and practical challenges.

#### A14. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 1, WE BAR E N A: A REALISTIC WEB ENVIRONMENT FOR BUILDING AUTONOMOUS AGENTS)

Current environments for evaluate agents tend to over-simplify real-world situations. As a result, the functionality of many environments is a limited version of their real-world counterparts, leading to a lack of task diversity (Shi et al., 2017; Anderson et al., 2018; Gordon et al., 2018; Misra et al., 2016; Shridhar et al., 2020; 2021; Yao et al., 2022a). In addition, these simplifications often lower the complexity of tasks as compared to their execution in the real world (Puig et al., 2018; Shridhar et al., 2020; Yao et al., 2022a). Finally, some environments are presented as a static resource (Shi et al., 2017; Deng et al., 2023) where agents are confined to accessing only those states that were previously cached during data collection, thus limiting the breadth and diversity of exploration. For evaluation, many environments focus on comparing the textual surface form of the predicted

#### A15. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 10, ACKNOWLEDGEMENT)

We would like to thank Emmy Liu, Zhiruo Wang, Zhitong Guo for examining our annotations, Shunyu Yao for providing the raw Amazon product data in Webshop, Pengfei Liu, Zaid Sheikh and Aman Madaan for the helpful discussions. We are also grateful to the Center for AI Safety for providing computational resources. This material is partly based on research sponsored in part by the Air Force Research Laboratory under agreement number FA8750-19-2-0200. The U.S. Government is authorized to reproduce and distribute reprints for Governmental purposes notwithstanding any copyright notation thereon. The views and conclusions contained herein are those of the authors and should not be interpreted as necessarily representing the official policies or endorsements, either expressed or implied, of the Air Force Research Laboratory or the U.S. Government. This project was also partially supported by a gift from AWS AI.

#### A16. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 30, F.5.1 DECISION-MAKING)

We modify in-context prompts from Yao et al. (2022) to construct these in-context examples. This is an example of clean type of tasks in ALFWorld.

## Answer B
Declared outcome: `answered`

I found four directly verified ReAct → later agent-evaluation relationships in the corpus. I did **not** count papers merely because they are topically similar.

**Baseline being traced.** ReAct is defined as a paradigm that prompts LLMs to generate “verbal reasoning traces and actions” in an interleaved manner, so the model can reason to act and act to reason through external environments. [1]

| Later paper | Directly verified relationship to ReAct | What the paper does with ReAct |
|---|---|---|
| **AgentBench: Evaluating LLMs as Agents** (2024) | **Positions ReAct as a predecessor in the LLM-agent line.** AgentBench states that “ReAct … is a pioneer work to combine CoT reasoning and actions in agent tasks,” then motivates AgentBench as a systematic benchmark for LLM-as-Agent evaluation. [2] | Genealogical/related-work link, not a ReAct ablation in the cited passage. |
| **AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents** (2024) | **Direct ablation/comparison against Act.** AgentBoard says ReAct is a popular alternative to its Act framework, because ReAct uses interleaved thoughts plus actions to boost planning; it reports inconsistent ReAct-vs-Act improvement and therefore uses Act rather than ReAct. [3] | Empirically compares ReAct vs Act on GPT-3.5-Turbo; the table reports mixed task results for AlfWorld, PDDL, Tool-Query, and Tool-Operation. [4] |
| **τ-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains** (2024) | **Direct method comparison.** τ-bench compares ReAct vs Act for text-formatted agent methods and says adding reasoning traces “still consistently helps” by bridging unfamiliar observation/action formats. [5] | Evaluates ReAct-style reasoning traces as a text-formatted agent method; also distinguishes this from function-calling agents, where a “think” function did not boost performance. [5] |
| **ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities** (2024) | **Direct prompting ablation.** ToolSandbox conducts additional experiments comparing ReAct prompting against its minimal prompting design for tool-use agents. [6] | Finds ReAct has minimal effect for Claude models and small gains for GPT-4 models; the authors interpret this as showing additional natural-language reasoning provides only marginal gains in their setup, while model rankings remain stable. [7] The table reports baseline vs ReAct scores for four models. [8] |

**Not counted as direct ReAct relationships:** I did not infer influence for other later agent-evaluation papers unless the text directly named or compared ReAct. In particular, references to generic chain-of-thought, reasoning traces, tool use, or multi-turn agent evaluation were not treated as ReAct influence without an explicit ReAct link.

Sources
[1] ReAct: Synergizing Reasoning and Acting in Language Models, 1 INTRODUCTION, p. 2
[2] AgentBench: Evaluating LLMs as Agents, 5 RELATED WORK, p. 9
[3] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, F Ablation Study of Agent Framework, p. 16
[4] AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents, F Ablation Study of Agent Framework, p. 16
[5] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5.1 Main results, p. 7
[6] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, D.1 Prompting Experiment, p. 22
[7] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, D.1 Prompting Experiment, p. 22
[8] ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities, D.1 Prompting Experiment, p. 22

### Evidence cited by Answer B
#### B1. ReAct: Synergizing Reasoning and Acting in Language Models (p. 2, 1 INTRODUCTION)

In this work, we present ReAct, a general paradigm to combine reasoning and acting with language models for solving diverse language reasoning and decision making tasks (Figure 1). ReAct prompts LLMs to generate both verbal reasoning traces and actions pertaining to a task in an interleaved manner, which allows the model to perform dynamic reasoning to create, maintain, and adjust high-level plans for acting (reason to act), while also interact with the external environments (e.g. Wikipedia) to incorporate additional information into reasoning (act to reason).

#### B2. AgentBench: Evaluating LLMs as Agents (p. 9, 5 RELATED WORK)

LLM-as-Agent. In pre-LLM era, text game environments such as TextWorld (Côté et al., 2019), Jericho (Hausknecht et al., 2020), and LIGHT (Urbanek et al., 2019) are dominant in language agent study which bases on BERT (Devlin et al., 2019) and reinforcement learning. With the advent of LLMs, the study of LLM agents begins to thrive (Huang et al., 2022), especially after Chain-of-Thought (Wei et al., 2022b) came out. ReAct (Yao et al., 2023b) is a pioneer work to combine CoT reasoning and actions in agent tasks. Later, a multitude of advanced reasoning strategies (Kim et al., 2023; Shinn et al., 2023; Wang et al., 2023d; Liu et al., 2023; Yao et al., 2023a; Gu et al., 2023) and applications including frameworks (Richards, 2023; Nakajima, 2023; age, 2023) and multi-agents (Park et al., 2023; Hong et al., 2023; Wu et al., 2023) for LLM-as-Agent have emerged and arouse much public interest. Nevertheless, limited datasets and models and available on the topic, without a standard and comprehensive benchmark. AGENTBENCH presents the first systematic benchmark for evaluating LLM-as-Agent with a broad coverage of tasks and available LLMs. Additionally, it also initiates the idea of adopting agent tasks to measure LLM performance.

#### B3. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 16, F Ablation Study of Agent Framework)

One popular alternative for our framework is to use ReAct (Yao et al., 2023) rather than Act, which uses interleaved thoughts in addition to actions to boost planning. However, our experiments on GPT-3.5-Turbo show inconsistent improvement of ReAct over performance of Act, as shown in Table 9. We hypothesized that this is due to we test long-term interactions of LLM Agents up to 30 interactions, and adding thoughts would pressure context length, thus leading to performance drop. Therefore, we use Act rather than ReAct in our benchmark.

#### B4. AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents (p. 16, F Ablation Study of Agent Framework)

Table 9: Comparison of Act and ReAct framework using GPT-3.5-Turbo.
Task Name | Act | ReAct | AlfWorld | 35.6/17.2 | 37.9/ 8.2 | PDDL | 25.0/5.0 | 17.1/ 3.3 | Tool-Query | 69.4/45.0 | 70.0/50.0 | Tool-Operation | 37.2/ 7.5 | 54.8/10.0

#### B5. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 7, 5.1 Main results)

Method comparison. Figure 3 shows that natively supported function calling consistently outperforms text-formatted agent methods with the state-of-the-art models. For text-formatted agent methods, adding reasoning traces still consistently helps (compare ReAct vs. Act columns) as it helps bridge the gap between observations and actions that have unfamiliar formats. We have also experimented with adding a “think” function for function-calling agents, but it did not boost performance, perhaps because most FC models have not been trained toward such reasoning.

#### B6. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 22, D.1 Prompting Experiment)

We conducted additional experiments comparing the effect of ReAct prompting in tool use agents against the minimal prompting design presented in Figure 8, with 4 representative models shown below.

#### B7. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 22, D.1 Prompting Experiment)

From Table 9, we can see that, ReAct has minimal effect to Claude model family, and a small gain for GPT4 model family. This is likely due to the fact that, the baseline ToolSandbox setup, which allows the model to interact with execution environment, recover from error and confirm with simulated user already provides a reasoning process to the model through user and execution environment interactions, additional natural language reasoning would only provide marginal gains. While ReAct provide gains in some cases, relative ranking between models still stands. This backs up our claim in Section 4, where we considered prompt engineering gains orthogonal to the innate model capability surfaced by simpler prompting.

#### B8. ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities (p. 22, D.1 Prompting Experiment)

Table 9: A comparison between default prompting and ReAct prompting.
| Baseline Score | ReAct Score | GPT-4o-2024-05-13 | 73.0 | 73.6 | Claude-3-Opus-20240229 | 69.2 | 69.3 | GPT-4-0125-Preview | 64.3 | 65.2 | Claude-3-Sonnet-20240229 | 63.8 | 63.7

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
