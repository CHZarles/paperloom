# Golden Contract Review: webarena_reproduction_protocol_001

## Conversation

- **user**: Give me a concise WebArena reproduction checklist covering its web domains, environment reset, task execution, and evaluation criterion.

## Authored Expectation

```yaml
outcome: answered
papers:
  required:
  - webarena_2024
  forbidden:
  - gaia_2024
evidence:
  required:
  - webarena_four_self_hosted_domains
  - webarena_deterministic_initial_state
  - webarena_functional_correctness_tasks
  forbidden:
  - gaia_question_construction
facts:
  application_count: '4'
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

### webarena_four_self_hosted_domains

Paper: `webarena_2024`, authored selector: `Our environment comprises four fully operational, self-hosted web applications, each representing a distinct domain prevalent on the internet`

We introduce WebArena, a realistic and reproducible web environment designed to facilitate the development of autonomous agents capable of executing tasks (§2). An overview of WebArena is in Figure 1. Our environment comprises four fully operational, self-hosted web applications, each representing a distinct domain prevalent on the internet: online shopping, discussion forums, collaborative development, and business content management. Furthermore, WebArena incorporates several utility tools, such as map, calculator, and scratchpad, to best support possible human-like task executions. Lastly, WebArena is complemented by an extensive collection of documentation and knowledge bases that vary from general resources like English Wikipedia to more domain-specific references, such as manuals for using the integrated development tool (Fan et al., 2022). The content populating these websites is extracted from their real-world counterparts, preserving the authenticity of the content served on each platform. We deliver the hosting services using Docker containers with gym-APIs (Brockman et al., 2016), ensuring both the usability and the reproducibility of WebArena.

### webarena_deterministic_initial_state

Paper: `webarena_2024`, authored selector: `We deliver the environment as dockers and provide scripts to reset the environment to a deterministic initial state`

Figure 3: We design the observation to be the URL and the content of a web page, with options to
represent the content as a screenshot (left), HTML DOM tree (middle), and accessibility tree (right).
The content of the middle and right figures are trimmed to save space.

real-world counterparts. As an example, our version of GitLab was developed based on the actual
GitLab project.<sup>1</sup> We carefully emulated the features of a typical code repository by including both
popular projects with many issues and pull requests and smaller, personal projects. Details of all
websites in WebArena can be found in Appendix A.1. We deliver the environment as dockers and
provide scripts to reset the environment to a deterministic initial state (See Appendix A.2).

2.3 OBSERVATION SPACE

We design the observation space to roughly mimic the web browser experience: a web page URL, the
opened tabs , and the web page content of the focused tab. WebArena is the first web environment
to consider multi-tab web-based tasks to promote tool usage, direct comparisons and references
across tabs, and other functionalities. The multi-tab functionality offers a more authentic replication
of human web browsing habits compared to maintaining everything in a single tab. We provide
flexible configuration to render the page content in many modes: (see Figure 3 for an example): (1)
the raw web page HTML, composed of a Document Object Model (DOM) tree, as commonly used
in past work (Shi et al., 2017; Deng et al., 2023; Li et al., 2020); (2) a screenshot, a pixel-based
representation that represents the current web page as an RGB array and (3) the accessibility tree of
the web page.<sup>2</sup> The accessibility tree is a subset of the DOM tree with elements that are relevant and
useful for displaying the contents of a web page. Every element is represented as its role (e.g., a link),
its text content, and its properties (e.g., whether it is focusable). Accessibility trees largely retain the
structured information of a web page while being more compact than the DOM representation.

We provide an option to limit the content to the contents within a viewport for all modes. This
ensures that the observation can be input into a text-based model with limited context length or an
image-based model with image size or resolution requirements.

2.4 ACTION SPACE

Following previous work on navigation and operation in web and embodied environments (Shi et al.,
2017; Liu et al., 2018), we design a compound action space that emulates the keyboard and mouse
operations available on web pages. Figure 4 lists all the available actions categorized into three
distinct groups. The first group includes element operations such as clicking, hovering, typing, and
key combination pressing. The second comprises tab-related actions such as opening, closing, and
switching between tabs. The third category consists of URL navigation actions, such as visiting a
specific URL or navigating forward and backward in the browsing history.

Building on these actions, WebArena provides agents with the flexibility to refer to elements for
operation in different ways. An element can be selected by its on-screen coordinates, (x, y), or by
a unique element ID that is prepended to each element. This ID is generated when traversing the
Document Object Model (DOM) or accessibility tree. With element IDs, the element selection is
transformed into an n-way classification problem, thereby eliminating any disambiguation efforts
required from the agent or the underlying implementation. For example, issuing the action click
[1582] clicks the button given the observation of [1582] Add to Cart. This flexible element
selection allows WebArena to support agents designed in various ways (e.g., accepting input from
different modalities) without compromising fair comparison metrics such as step count.

### webarena_functional_correctness_tasks

Paper: `webarena_2024`, authored selector: `release a set of benchmark tasks focusing on evaluating the functional correctness of task completions`

With advances in generative AI, there is now potential for autonomous agents to manage daily tasks via natural language commands. However, current agents are primarily created and tested in simplified synthetic environments, leading to a disconnect with real-world scenarios. In this paper, we build an environment for language-guided agents that is highly realistic and reproducible. Specifically, we focus on agents that perform tasks on the web, and create an environment with fully functional websites from four common domains: e-commerce, social forum discussions, collaborative software development, and content management. Our environment is enriched with tools (e.g., a map) and external knowledge bases (e.g., user manuals) to encourage human-like task-solving. Building upon our environment, we release a set of benchmark tasks focusing on evaluating the functional correctness of task completions. The tasks in our benchmark are diverse, long-horizon, and designed to emulate tasks that humans routinely perform on the internet. We experiment with several baseline agents, integrating recent techniques such as reasoning before acting. The results demonstrate that solving complex tasks is challenging: our best GPT-4-based agent only achieves an end-to-end task success rate of 14.41%, significantly lower than the human performance of 78.24%. These results highlight the need for further development of robust agents, that current state-of-the-art large language models are far from perfect performance in these real-life tasks, and that WebArena can be used to measure such progress. Our code, data, environment reproduction resources, and video demonstrations are publicly available at https://webarena.dev/.

## Human Decision

Choose `keep`, `revise`, or `drop`, then answer:

1. Is the expected outcome the uniquely reasonable action?
2. Are the required papers necessary?
3. Do the required evidence spans cover every requested dimension?
4. Should equivalent evidence from the same paper be accepted?
