# Blind Answer Review: gaia_dataset_gap_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Explain how the GAIA benchmark was constructed and give the reported human versus GPT-4-with-plugins result.

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

GAIA was built as a set of **466 real-world assistant questions**, each paired with a **unique, factual answer** so that evaluation could be automatic and robust. The benchmark targets tasks requiring abilities such as reasoning, multimodality, web browsing, coding, and tool use; the authors include modalities such as PDFs, spreadsheets, images, video, and audio. [1] [2]

Construction followed a human-authored-and-validated pipeline:

1. **Question creation:** annotators were given a seed set of GAIA questions created by the authors, then asked to create and answer new questions. [3] [4]
2. **Difficulty labeling:** questions were grouped into three levels using the number of steps and tools used by annotators as a proxy. Level 1 generally requires no tools or at most one tool and no more than 5 steps; Level 2 roughly requires 5–10 steps and multiple tools; Level 3 targets near-perfect assistants, allowing arbitrarily long action sequences, any number of tools, and broad world access. [5] [6] [7] [8]
3. **Validation for non-ambiguity:** two new independent annotators answered each question. If both new annotators and the original annotator agreed, the question was validated; if not, the question was fixed when possible and removed otherwise. The paper reports that 68% of questions were good as-is and estimates about two hours of annotator time per question including validation and repairs. [9]

The headline reported result was **92% for humans versus 15% for GPT-4 equipped with plugins**. [10] The paper notes that the GPT-4+plugins score should be treated as an **oracle-style** result because plugins were manually selected per question; the human score came from validation annotators. [11]

Sources
[1] GAIA: a benchmark for General AI Assistants, Level 3, p. 2
[2] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[3] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[4] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[5] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[6] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[7] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[8] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[9] GAIA: a benchmark for General AI Assistants, 3.4 Building and extending GAIA, p. 7
[10] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[11] GAIA: a benchmark for General AI Assistants, 4 LLMs results on GAIA, p. 8

### Evidence cited by Answer A

#### A1. GAIA: a benchmark for General AI Assistants (p. 2, Level 3)

We move in that direction by proposing <sup>GAIA</sup>, a benchmark for General AI Assistants featuring 466 carefully crafted questions and their answer, along with the associated design methodology. Our questions are easy to create, challenging for AI systems—for LLMs, most require complex generations—, yet admit a unique, factual answer, allowing a simple and robust automatic evaluation.

#### A2. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

Capabilities coverage. Scoring perfectly on <sup>GAIA</sup> requires advanced reasoning, multi-modality understanding, coding capabilities and generally tool use, e.g web browsing, for which we provide a more precise definition in Appendix C. We also include questions requiring to process varied data modalities such as PDFs, spreadsheets, but also images, videos or audio, whose distribution is reported in Appendix C (Figure 6). Figure 3 (left) is an overview of these capabilities. Although web browsing is a key component of <sup>GAIA</sup>, we do not require assistants to perform actions other than “clicks” on a website such as uploading a file, post a comment or book a meeting. Testing these capabilities in real environments while avoiding spamming websites requires careful consideration that we leave for future work, and refer the reader to recent works proposing closed environments for LLMs agents (Liu et al., 2023a). We do not provide a more detailed list of required capabilities to solve the benchmark since most questions can be solved equally well via diferent combinations of capabilities. For example, a given piece of evidence may have been properly memorised by an assistant LLM, or retrieved via a web search. In particular, we do not provide a fine-grained benchmarking of tool usage by LLMs, and refer the reader to Xu et al. (2023b); Li et al. (2023c).

#### A3. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

Question creation phase. We provided the annotators with a seed set of <sup>GAIA</sup> questions we devised ourselves, accompanied with the following instructions:

#### A4. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

The annotators were also asked to answer the questions they created. We provide a typical example of annotated question in Table 1.

#### A5. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

Increasing dificulty. The questions can be sorted into three levels of increasing dificulty depending on the number of steps required to solve the questions, and the number of diferent tools needed to answer the question. There is naturally not a single definition of step or tool, and possibly many paths to answer a given question. Therefore, we rely as a proxy on the number of steps and tools used by our annotators when crafting the questions. Figure 3 (right) illustrates the distribution of our questions along these two axes. Tools are always related to one or more capability (see Appendix C). We loosely use the following definitions to attribute a level to a question:

#### A6. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 1 questions generally require no tools, or at most one tool but no more than 5 steps.

#### A7. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 2 question generally involve more steps, roughly between 5 and 10 and combining diferent tools is needed.

#### A8. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 3 are questions for a near perfect general assistant, requiring to take arbitrarily long sequences of actions, use any number of tools, and access to the world in general.

#### A9. GAIA: a benchmark for General AI Assistants (p. 7, 3.4 Building and extending GAIA)

Validating questions. Most of the work associated with crafting questions consists in ensuring that they are unambiguous, i.e., there is a single correct answer. This property allows fast and factual evaluation, hence it is crucial to maintain it. Ambiguities can be subtle and rarely obvious to the creator of a question. For example, a question is ambiguous if it does not specify a version for a web page while the information needed to answer the question is diferent in other versions. We therefore asked two new annotators to independently answer each question. If the original annotator and the two new annotators arrived at the same answer, the question was validated. Questions on which annotators disagreed generally only required a simple fix, but were removed otherwise. For this reason, question creation can hardly be automated while keeping the interest and variety of questions high. We report statistics on this validation phase in Table 3 (Appendix C). 68% of the questions were good as is, while the rest had to be corrected or removed. While the questions are conceptually simple, annotators might do inadvertent mistakes: we estimate the annotator’s success rate to be 92% when aggregated on all levels of dificulty, and report this as the human score for <sup>GAIA</sup>. It is close to perfect, demonstrating that <sup>GAIA</sup> is simple for non experts. We estimate the creation of a question, including its validation by two supplementary annotators and potential repairs, to require two hours of annotator time.

#### A10. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### A11. GAIA: a benchmark for General AI Assistants (p. 8, 4 LLMs results on GAIA)

LLMs, Human and Search engine scores and time to answer for GAIA Figure 4 Scores and time to answer per method and level. As stated in the main text, GPT4 + plugins score should be seen as an oracle since the plugins were chosen manually depending on the question. Human score refers to the score obtained by our annotators when validating the questions.

## Answer B

Declared outcome: `answered`

## How GAIA was constructed

GAIA was built around real-world assistant questions that require reasoning, multi-modality handling, web browsing, and tool use, chosen so that they are "conceptually simple for humans yet challenging for most advanced AIs" [1]. The authors devised 466 such questions and their answers, releasing the questions while holding back the answers to 300 of them to power a leaderboard [1].

**Annotator-driven pipeline (Section 3.4 / Appendix D).** The questions were written by human annotators, who were given a seed set of GAIA questions and a list of design constraints [2]. Each annotator was asked to:

- make the question "interesting" (an AI assistant answering it would be very useful) [3];
- restrict the answer to a number or at most a few words so evaluation is robust [4];
- ensure the answer does not change with time, including possible deletion of the source of truth [5];
- ensure the answer is unambiguous [6];
- ensure a human annotator can answer it in a reasonable amount of time [7].

Annotators were also required to answer the questions they created themselves [8]. The authors estimate that crafting a single question, including the validation step and any repairs, takes about two hours of annotator time [9].

**Validation phase.** After creation, every question is independently re-answered by two new annotators to check for ambiguity. If the new annotators do not fully agree with the original answer and there is no annotator error, the question is repaired if possible, otherwise removed [10]. In the validation phase reported in Table 3, 623 newly crafted questions were each answered by two additional annotators (1,246 annotations in total), and a human baseline was computed as the fraction of correct answers by the new annotators on valid questions [11].

**Three difficulty levels (Section 3.3).** Levels are defined by the number of steps required and the number of different tools involved, using the annotators' own traces as a proxy [12]:

- Level 1: generally no tools, or at most one tool and no more than 5 steps [13].
- Level 2: roughly between 5 and 10 steps, combining different tools [14].
- Level 3: for a near-perfect general assistant, requiring arbitrarily long action sequences, any number of tools, and general world access [15].

These are loose definitions, not hard constraints (e.g., a sub-10-step question requiring complex web navigation could still be Level 3) [16]. The final GAIA validation/test split reported in Table 4 contains 146 Level 1, 245 Level 2, and 75 Level 3 questions [11].

## Reported human vs. GPT-4-with-plugins result

The headline finding from the paper is that human respondents obtain **92%** versus **15%** for GPT-4 equipped with plugins [1]. Table 4 gives the per-level breakdown (number of questions: 146 / 245 / 75 at Levels 1/2/3) [11]:

| Method | Level 1 | Level 2 | Level 3 |
|---|---|---|---|
| Human annotator (validation) | **93.9%** | **91.8%** | **87.3%** |
| GPT-4 + plugins (oracle) | **30.3%** | **9.7%** | **0%** |

For context, the other systems in the same table scored: GPT-4 9.1 / 2.6 / 0; GPT-4 Turbo 13.0 / 5.5 / 0; AutoGPT (GPT-4 backend) 14.4 / 0.4 / 0; search engine 7.4 / 0 / 0 [11].

Two important caveats the authors flag for the GPT-4 + plugins number:

- It is treated as an **"oracle"** estimate because there is no API for GPT-4 with plugins, so plugins were **manually chosen** per question (Advanced Data Analysis vs. up to three third-party plugins), and the available plugins change over time [17].
- The human number is the score obtained by the validation annotators when re-answering the questions, and humans also pay a higher time cost (e.g., 6.8 / 10.5 / 17.7 minutes per question across the three levels, versus 0.65 / 0.53 / N.A. for GPT-4 + plugins) [11].

Sources
[1] GAIA: a benchmark for General AI Assistants, A Benchmark for General AI Assistants, p. 1
[2] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[3] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[4] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[5] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[6] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[7] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[8] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[9] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[10] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 19
[11] GAIA: a benchmark for General AI Assistants, D Extended description of our question design framework, p. 21
[12] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[13] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[14] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[15] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[16] GAIA: a benchmark for General AI Assistants, 3.3 Composition of GAIA, p. 6
[17] GAIA: a benchmark for General AI Assistants, 4 LLMs results on GAIA, p. 8

### Evidence cited by Answer B

#### B1. GAIA: a benchmark for General AI Assistants (p. 1, A Benchmark for General AI Assistants)

We introduce <sup>GAIA</sup>, a benchmark for General AI Assistants that, if solved, would represent a milestone in AI research. <sup>GAIA</sup> proposes real-world questions that require a set of fundamental abilities such as reasoning, multi-modality handling, web browsing, and generally tool-use proficiency. <sup>GAIA</sup> questions are conceptually simple for humans yet challenging for most advanced AIs: we show that human respondents obtain 92% vs. 15% for GPT-4 equipped with plugins. This notable performance disparity contrasts with the recent trend of LLMs outperforming humans on tasks requiring professional skills in e.g. law or chemistry. <sup>GAIA</sup>’s philosophy departs from the current trend in AI benchmarks suggesting to target tasks that are ever more dificult for humans. We posit that the advent of Artificial General Intelligence (AGI) hinges on a system’s capability to exhibit similar robustness as the average human does on such questions. Using <sup>GAIA</sup>’s methodology, we devise 466 questions and their answer. We release our questions while retaining answers to 300 of them to power a leader-board hereby accessible.

#### B2. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

Question creation phase. We provided the annotators with a seed set of <sup>GAIA</sup> questions we devised ourselves, accompanied with the following instructions:

#### B3. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

• Make sure your question is “interesting”, i.e. by reading it you think that an AI assistant answering this kind of question would help you a lot.

#### B4. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

• Make sure the answer to your question is a number or at most a few words to make evaluation robust.

#### B5. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

• Make sure the answer to your question does not change with time. This includes potential deletion of the source of truth.

#### B6. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

• Make sure the answer to your question is unambiguous.

#### B7. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

• Make sure your question can be answered in a reasonable amount of time by a human annotator.

#### B8. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

The annotators were also asked to answer the questions they created. We provide a typical example of annotated question in Table 1.

#### B9. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

We estimate the creation of a question, including its validation by two supplementary annotators and potential repairs, requires two hours of annotator time.

#### B10. GAIA: a benchmark for General AI Assistants (p. 19, D Extended description of our question design framework)

Validation phase. After question creation, we ask two new independent annotators to answer the questions to check it is not ambiguous. We provide a typical annotator output for the validation phase in Table 2, as well as additional statistics on the validation phase of our protocol in Table 3. If the new annotators don’t fully agree with the original answer and there is no human error, the question is repaired if possible and removed otherwise.

#### B11. GAIA: a benchmark for General AI Assistants (p. 21, D Extended description of our question design framework)

Table 3 Statistics on the validation phase. 623 newly crafted questions were validated by two new annotators each. The statistics were computed on their 1246 annotations. \*: a valid question is a question for which two annotators give the same answer as the question designer, or only one annotator gives the same answer as the question designer and the other made a mistake. \*\*: the human baseline is computed as the fraction of correct answers for all tentative on valid questions by the new annotators. Table 4 Score and average time to answer for various baselines on GAIA in %. \*: GPT4 + plugins scores were obtained by manually selecting plugins, and cannot be reproduced exactly for the reasons described in the main text. \*\*: Human score corresponds to the portion of correct answers by validation annotators for valid questions. Whenever we have direct API access, we run the model three times and report the average. Times for APIs were obtained by running the API on 20 questions then averaging, and were taken at a single point in time: they aren’t meant to reflect GPT4 vs. GPT4 Turbo speed, but GPT4 vs. other types of methods for answering <sup>GAIA</sup>.
Metric | Score in % (↑) | Avg. time to answer in mins (↓) | Level | Level 1 | Level 2 | Level 3 | Level 1 | Level 2 | Level 3 | Number of questions | 146 | 245 | 75 | 146 | 245 | 75 | GPT4 | $9 . 1 \pm 2 . 5$ | $2 . 6 \pm 0 . 6$ | 0 | 0.19 | 0.15 | N.A. | GPT4 Turbo | $1 3 . 0 \pm 2 . 1$ | $5 . 5 \pm 1 . 4$ | 0 | 0.24 | 0.12 | N.A. | AutoGPT (GPT4 backend) | 14.4 | 0.4 | 0 | 7.6 | 11.7 | N.A. | GPT4 + plugins* | 30.3 | 9.7 | 0 | 0.65 | 0.53 | N.A. | Search engine | 7.4 | 0 | 0 | 7.4 | N.A. | N.A. | Human annotator** | 93.9 | 91.8 | 87.3 | 6.8 | 10.5 | 17.7

#### B12. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

Increasing dificulty. The questions can be sorted into three levels of increasing dificulty depending on the number of steps required to solve the questions, and the number of diferent tools needed to answer the question. There is naturally not a single definition of step or tool, and possibly many paths to answer a given question. Therefore, we rely as a proxy on the number of steps and tools used by our annotators when crafting the questions. Figure 3 (right) illustrates the distribution of our questions along these two axes. Tools are always related to one or more capability (see Appendix C). We loosely use the following definitions to attribute a level to a question:

#### B13. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 1 questions generally require no tools, or at most one tool but no more than 5 steps.

#### B14. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 2 question generally involve more steps, roughly between 5 and 10 and combining diferent tools is needed.

#### B15. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

\- Level 3 are questions for a near perfect general assistant, requiring to take arbitrarily long sequences of actions, use any number of tools, and access to the world in general.

#### B16. GAIA: a benchmark for General AI Assistants (p. 6, 3.3 Composition of GAIA)

An illustration of these levels is provided in Figure 1. Those definitions are not hard constraints: for example, a question with less than 10 annotator steps but that requires complex web navigation might be categorised as Level 3 rather than 2. Our definition of the dificulty is validated in Section 4.

#### B17. GAIA: a benchmark for General AI Assistants (p. 8, 4 LLMs results on GAIA)

GPT4 plugins. As opposed to GPT4, there is currently no API for GPT4 with plugins, and we resort to manual ChatGPT queries. At the time of the writing, the user has to manually choose between an Advanced Data Analysis mode—with code execution and file reading capabilities—, and a set of at most three third party plugins. We use either the first mode or select third parties plugins according to our best guess of the most important capabilities given the task. We often rely on (i) a tool for reading various types of links, (ii) a web browsing tool, and (iii) a tool for computation. Sadly, it is currently not possible to use a stable set of plugins over some period of time as plugins often change or disappear from the store. Similarly, the oficial search tool for GPT4 was removed as it could possibly circumvent paywalls, before being recently brought back. Therefore, our score for GPT4 with plugins is an “oracle” estimate of GPT4 potential with more stable and automatically selected plugins rather than an easily reproducible result.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
