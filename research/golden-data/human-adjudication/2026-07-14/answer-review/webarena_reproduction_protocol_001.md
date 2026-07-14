# Blind Answer Review: webarena_reproduction_protocol_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Give me a concise WebArena reproduction checklist covering its web domains, environment reset, task execution, and evaluation criterion.

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

### WebArena reproduction checklist

- **Stand up the domains:** run the self-hosted Dockerized WebArena sites: e-commerce/OneStopShop, GitLab, Reddit/social forum, online-store CMS, plus map and English Wikipedia utilities; the benchmark’s main website categories are e-commerce, social forum, collaborative development, and CMS. [1] [2]
- **Use packaged, self-contained images:** each website is a separate Docker image containing the site code, database, dependencies, and pre-populated data; the images do not require external volume mounts. [3]
- **Reset before state-changing cases:** for tasks that modify site data, stop and delete the running container for the relevant website, then restart it from the original image to restore the initial state; read-only information-gathering tasks may not require a reset. [4]
- **Execute each task as a natural-language intent:** the agent acts from the current observation, intent, prior actions, and prior observations; each action transitions the deterministic web environment to a new state and observation. [5]
- **Match the baseline interface if reproducing reported agents:** provide the current observation, intent, and previous action; use the accessibility tree with element IDs as the observation space, so actions can target elements by ID. [6]
- **Evaluate outcome, not just trajectory:** each of the 812 examples has a metric for functional correctness. [7] For information-seeking tasks, compare the predicted answer with the annotated answer using the task’s scoring function: `exact_match`, `must_include`, or `fuzzy_match` with GPT-4-0613. [8] [9] [10] [11] For navigation and content/configuration tasks, use programmatic reward functions that inspect intermediate states such as databases and page status/content to check whether the intended outcome was achieved. [12]

Sources
[1] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.2 WEBSITE SELECTION, p. 3
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.2 WEBSITE SELECTION, p. 3
[3] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.2 ENVIRONMENT DELIVERY AND RESET, p. 15
[4] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.2 ENVIRONMENT DELIVERY AND RESET, p. 15
[5] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.1 CONTROLLING AGENTS THROUGH HIGH-LEVEL NATURAL LANGUAGE, p. 3
[6] WebArena: A Realistic Web Environment for Building Autonomous Agents, 4 BASELINE WEB AGENTS, p. 7
[7] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3 BENCHMARK SUITE OF WEB-BASED TASKS, p. 5
[8] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[9] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[10] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[11] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[12] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6

### Evidence cited by Answer A

#### A1. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 3, 2.2 WEBSITE SELECTION)

To decide which categories of websites to use, we first analyzed approximately 200 examples from the authors’ actual web browser histories. Each author delved into their browsing histories, summarizing the goal of particular segments of their browser session. Based on this, we classified the visited websites into abstract categories. We then identified the four most salient categories and implemented one instance per category based on this analysis: (1) E-commerce platforms supporting online shopping activities (e.g., Amazon, eBay), (2) social forum platforms for opinion exchanges (e.g., Reddit, StackExchange), (3) collaborative development platforms for software development (e.g., GitLab), and (4) content management systems (CMS) that manage the creation and revision of the digital content (e.g., online store management).

#### A2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 3, 2.2 WEBSITE SELECTION)

Implementation We leveraged open-source libraries relevant to each category to build our own versions of an E-commerce website (OneStopShop), GitLab, Reddit, an online store content management system (CMS), a map, and an English Wikipedia. Then we imported sampled data from their real-world counterparts. As an example, our version of GitLab was developed based on the actual GitLab project.<sup>1</sup> We carefully emulated the features of a typical code repository by including both popular projects with many issues and pull requests and smaller, personal projects. Details of all websites in WebArena can be found in Appendix A.1. We deliver the environment as dockers and provide scripts to reset the environment to a deterministic initial state (See Appendix A.2).

#### A3. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.2 ENVIRONMENT DELIVERY AND RESET)

One goal for our evaluation environment is ease of use and reproducibility. As a result, we deploy our websites in separate Docker images <sup>11</sup>, one per website. The Docker images are fully self-contained with all the code of the website, database, as well as any other software dependencies. They also do not rely on external volume mounts to function, as the data of the websites are also part of the docker image. This way, the image is easy to distribution containing all the pre-populated websites for reproducible evaluation. End users can download our packaged Docker images and run them on their systems and re-deploy the exact websites together with the data used in our benchmarks for their local benchmarking.

#### A4. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.2 ENVIRONMENT DELIVERY AND RESET)

Since some evaluation cases may require the agent to modify the data contained in the website, e.g., creating a new user, deleting a post, etc., it is crucial to be able to easily reset the website environment to its initial state. With Docker images, the users could stop and delete the currently running containers for that website and start the container from our original image again to fully reset the environment to the initial state. Depending on the website, this process may take from a few seconds to one minute. However, not all evaluation cases would require an environment reset, as many of the intents are information gathering and are read-only for the website data. Also, combined with the inference time cost for the agent LLMs, we argue that this environment reset method, through restarting Docker containers from the original images, will have a non-negligible but small impact on evaluation time.

#### A5. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 3, 2.1 CONTROLLING AGENTS THROUGH HIGH-LEVEL NATURAL LANGUAGE)

The WebArena environment is denoted as ${ \mathcal { E } } { = \langle { S , A , \mathcal { O } } , { \mathcal { T } } \rangle }$ with state space S, action space A (§2.4) and observation space O (§2.3). The transition function $\mathcal { T } : \mathcal { S } \times \mathcal { A } \longrightarrow \mathcal { S }$ is deterministic, and it is defined by the underlying implementation of each website in the environment. Given a task described as a natural language intent i, an agent issues an action $a _ { t } \in \mathcal A$ based on intent i, the current observation $o _ { t } \in { \mathcal { O } } .$ , the action history $\mathbf { a } _ { 1 } ^ { \ell - 1 }$ and the observation history $\mathbf { o } _ { 1 } ^ { t - 1 }$ . Consequently, the action results in a new state $s _ { t + 1 } \in S$ and its corresponding observation $o _ { t + 1 } \in \mathcal { O }$ . We propose a reward function $r ( \mathbf { a } _ { 1 } ^ { T } , \mathbf { s } _ { 1 } ^ { T } )$ to measure the success of a task execution, where $\mathbf { a } _ { 1 } ^ { T }$ represents the sequence of actions from start to the end time step T , and $\mathbf { s } _ { 1 } ^ { T }$ denotes all intermediate states. This reward function assesses if state transitions align with the expectations of the intents. For example, with an intent to place an order, it verifies whether an order has been placed. Additionally, it evaluates the accuracy of the agent’s actions, such as checking the correctness of the predicted answer.

#### A6. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 7, 4 BASELINE WEB AGENTS)

We experiment with three LLMs using two prompting strategies, both with two examples in the context. In the first setting, we ask the LLM to directly predict the next action given the current observation, the intent and the previously performed action. In the second setting, with the same information, the model first performs chain-of-thought reasoning steps in the text before the action prediction (CoT, Wei et al. (2022); Yao et al. (2022b)). Before the examples, we provide a detailed overview of the browser environment, the allowed actions, and many rules. To make the model aware of the unachievable tasks, the instruction explicitly asks the agent to stop if it believes the task is impossible to perform. We refer to this directive as Unachievable hint, or UA hint. This introduction is largely identical to the guidelines we presented to human annotators to ensure a fair comparison. We use an accessibility tree with element IDs as the observation space. The agent can identify which element to interact with by the ID of the element. For instance, the agent can issue click [1582] to click the “Add to $\mathrm { C a r t } ^ { \tilde { \prime } \tilde { \prime } }$ button with the ID of 1582. The full prompts can be found in Appendix A.9. The detailed configurations of each model can be found in Appendix A.6.

#### A7. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 5, 3 BENCHMARK SUITE OF WEB-BASED TASKS)

We provide a benchmark with 812 test examples on grounding high-level natural language instructions to interactions in WebArena. Each example has a metric to evaluate the functional correctness of the task execution. In this section, we first formally define the task of controlling an autonomous agent through natural language. Then we introduce the annotation process of our benchmark.

#### A8. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Evaluating Information Seeking Tasks To measure the correctness of information-seeking tasks where a textual answer is expected, we provide the annotated answer a<sup>∗</sup> for each intent. The a<sup>∗</sup> is further compared with the predicted answer aˆ with one of the following scoring functions $r _ { \mathrm { i n f o } } ( \hat { a } , a ^ { * } )$

#### A9. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

First, we define exact\_match where only aˆ that is identical with a<sup>∗</sup> receives a score of one. This function is primarily applicable to intent types whose responses follow a more standardized format, similar to the evaluation on question answering literature (Rajpurkar et al., 2016; Yang et al., 2018).

#### A10. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Second, we create must\_include where any aˆ containing a<sup>∗</sup> receives a score of one. This function is primarily used in when an unordered list of text is expected or where the emphasis of evaluation is on certain key concepts. In the second example in Table 1, we expect both the correct name and the email address to be presented, irrespective of the precise wording used to convey the answer.

#### A11. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Finally, we introduce fuzzy\_match where we utilize a language model to assess whether aˆ is semantically equivalent to a<sup>∗</sup>. Specifically, in this work, we use gpt-4-0613 to perform this evaluation. The corresponding prompt details are provided in Appendix A.7. The fuzzy\_match function applies to situations where the format of the answer is diverse. For instance, in responding to “Compare the time for walking and driving route from AMC Waterfront to Randyland”, it is essential to ensure that driving time and walking time are accurately linked with the correct terms. The fuzzy\_match function could also flexibly match the time “2h58min” with different forms such as “2 hour 58 minutes”, “2:58” and others. We demonstrate a language model can achieve nearly perfect performance on this task in §A.8.

#### A12. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Evaluating Site Navigation and Content & Config Tasks The tasks in these categories require accessing web pages that meet certain conditions or performing operations that modify the underlying data storage of the respective websites. To assess these, we establish reward functions $r _ { \mathrm { p r o g } } ( \mathbf { s } )$ that programmatically examine the intermediate states s within an execution trajectory to ascertain whether the outcome aligns with the intended result. These intermediate states are often the underlying databases of the websites, the status, and the content of a web page at each step of the execution.

## Answer B

Declared outcome: `answered`

## WebArena Reproduction Checklist

A condensed reproduction plan distilled from the WebArena paper (Zhou et al., ICLR 2024).

### 1. Web Domains to Stand Up
Self-host four fully functional apps plus utilities and knowledge resources, each in its own Docker image [1] [2] [3] [4]:

- **OneStopShop (E-commerce)** — Adobe Magento with ~90k products across 300+ categories, imported from the Webshop data dump.
- **Reddit (Social forum)** — Postmill with 95 subreddits (top 50 + manually curated NE-US / ML-DL subs), 127,390 posts, 661,781 users.
- **GitLab (Collaborative development)** — Real GitLab project with 300 repos sampled across popularity bands and >1,000 committing accounts.
- **E-commerce CMS** — Adobe Magento admin portal with official sample data (shop-owner profile).
- **Map utility** — OpenStreetMap, restricted to the NE-US region.
- **Calculator + scratchpad** — Implemented in-house.
- **Knowledge resources** — Kiwix hosting an offline English Wikipedia (cutoff May 2023); scraped GitLab and Adobe Commerce manuals.
- **Seeded user roles**: distinct profiles per site — a customer with 35+ orders/2 yrs on the shop, a maintainer on GitLab, an active Reddit user, and a shop-owner on the CMS [5] [6].

### 2. Environment Reset
- Ship **one Docker image per website**, fully self-contained (code, database, dependencies, and pre-populated data baked in; no external volume mounts) [7].
- Reset = `docker stop` and `rm` the running container, then re-launch from the original image. Per-website reset time ranges from a few seconds to about one minute [8].
- Read-only / information-gathering tasks can skip reset; mutating tasks (create user, delete post, place order) require it.
- Expose the environment via **gym-style APIs** so agents can `reset()` and `step()` [1].

### 3. Task Execution
- **Benchmark size**: 812 natural-language intents across three categories — Information Seeking, Site Navigation, and Content & Configuration [9].
- **Transition function T** is deterministic and defined by each website's underlying implementation [10].
- **Observation space** per step: current URL, list of opened tabs, and the content of the focused tab. Three configurable render modes: raw DOM HTML, RGB screenshot, or accessibility tree [11].
- **Action space** — three groups emulating keyboard/mouse plus browser navigation [12]:
  - Element ops: `noop`, `click(elem)`, `hover(elem)`, `type(elem, text)`, `press(key_comb)`, `scroll(dir)`
  - Tab ops: `tab_focus(index)`, `new_tab`, `tab_close`
  - URL ops: `go_back`, `go_forward`, `goto(URL)`

### 4. Evaluation Criterion
WebArena scores **functional correctness** of the trajectory, not surface form [10] [13]:

- **Reward function** `r(a₁ᵀ, s₁ᵀ)` inspects the sequence of actions and intermediate states produced during execution.
- **Locator** is supplied per instance to retrieve the relevant content — implemented as either a database query, a site-supported API call, or a JavaScript element selection on the resulting page (e.g., `document.querySelector('.submission__inner').outerText`) [14].
- **Programmatic reward `r_prog(s)`** for Site Navigation and Content & Configuration tasks — examines underlying databases, page status, and on-page content at each step to confirm the outcome [13].
- **Text-answer scoring** uses two string-based metrics for Information-Seeking-style outputs [15] [16]:
  - `exact_match` — score 1 only when the prediction is identical to the reference; suited to standardized-format answers.
  - `must_include` — score 1 when the prediction contains the reference string; suited to unordered lists or key-concept emphasis (e.g., required names/emails).

### Quick Build-Order Summary
1. Pull/build per-site Docker images (Magento, Postmill, GitLab, OSM, Kiwix, calculator, scratchpad).
2. Boot containers; seed the four canonical user accounts; verify multi-tab rendering.
3. Wire gym-API `reset` / `step` over the action space and the three observation render modes.
4. Load the 812-intent benchmark; run agents with deterministic reset between mutating tasks.
5. Score each trajectory with `exact_match` / `must_include` for text answers and `r_prog(s)` via locator-driven DB/API/JS checks for state-changing tasks.

Sources
[1] WebArena: A Realistic Web Environment for Building Autonomous Agents, 1 INTRODUCTION, p. 2
[2] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.2 WEBSITE SELECTION, p. 3
[3] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.1 WEBSITE IMPLEMENTATION, p. 15
[4] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.1 WEBSITE IMPLEMENTATION, p. 15
[5] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.3 USER ROLES SIMULATION, p. 16
[6] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.3 USER ROLES SIMULATION, p. 16
[7] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.2 ENVIRONMENT DELIVERY AND RESET, p. 15
[8] WebArena: A Realistic Web Environment for Building Autonomous Agents, A.2 ENVIRONMENT DELIVERY AND RESET, p. 15
[9] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3 BENCHMARK SUITE OF WEB-BASED TASKS, p. 5
[10] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.1 CONTROLLING AGENTS THROUGH HIGH-LEVEL NATURAL LANGUAGE, p. 3
[11] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.3 OBSERVATION SPACE, p. 4
[12] WebArena: A Realistic Web Environment for Building Autonomous Agents, 2.4 ACTION SPACE, p. 4
[13] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[14] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[15] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6
[16] WebArena: A Realistic Web Environment for Building Autonomous Agents, 3.2 EVALUATION ANNOTATION, p. 6

### Evidence cited by Answer B

#### B1. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 2, 1 INTRODUCTION)

We introduce WebArena, a realistic and reproducible web environment designed to facilitate the development of autonomous agents capable of executing tasks (§2). An overview of WebArena is in Figure 1. Our environment comprises four fully operational, self-hosted web applications, each representing a distinct domain prevalent on the internet: online shopping, discussion forums, collaborative development, and business content management. Furthermore, WebArena incorporates several utility tools, such as map, calculator, and scratchpad, to best support possible human-like task executions. Lastly, WebArena is complemented by an extensive collection of documentation and knowledge bases that vary from general resources like English Wikipedia to more domain-specific references, such as manuals for using the integrated development tool (Fan et al., 2022). The content populating these websites is extracted from their real-world counterparts, preserving the authenticity of the content served on each platform. We deliver the hosting services using Docker containers with gym-APIs (Brockman et al., 2016), ensuring both the usability and the reproducibility of WebArena.

#### B2. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 3, 2.2 WEBSITE SELECTION)

Implementation We leveraged open-source libraries relevant to each category to build our own versions of an E-commerce website (OneStopShop), GitLab, Reddit, an online store content management system (CMS), a map, and an English Wikipedia. Then we imported sampled data from their real-world counterparts. As an example, our version of GitLab was developed based on the actual GitLab project.<sup>1</sup> We carefully emulated the features of a typical code repository by including both popular projects with many issues and pull requests and smaller, personal projects. Details of all websites in WebArena can be found in Appendix A.1. We deliver the environment as dockers and provide scripts to reset the environment to a deterministic initial state (See Appendix A.2).

#### B3. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.1 WEBSITE IMPLEMENTATION)

Given the selected websites described in §2.2, we make the best attempt to reproduce the functionality of commonly used sites in a reproducible way. To achieve this, we utilized open-source frameworks for the development of the websites across various categories and imported data from their real-world counterparts. For the E-commerce category, we constructed a shopping website with approximately 90k products, including the prices, options, detailed product descriptions, images, and reviews, spanning over 300 product categories. This website is developed using Adobe Magento, an opensource e-commerce platform<sup>4</sup>. Data resources were obtained from data from actual online sites, such as that included in the Webshop data dumpYao et al. (2022a). As for the social forum platform, we deployed an open-source software Postmill<sup>5</sup>, the open-sourced counterpart of Reddit<sup>6</sup>. We sampled from the top 50 subreddits<sup>7</sup>. We then manually selected many subreddit for northeast US cities as well as subreddit for machine learning and deep learning-related topics. This manual selection encourages cross-website tasks such as seeking information related to the northeast US on both Reddit and the map. In total, we have 95 subreddits, 127390 posts, and 661781 users. For the collaborative software development platform, we choose GitLab<sup>8</sup>. We heuristically simulate the code repository characteristics by sampling at least ten repositories for every programming language: 80% of them are sampled from the set of top 90 percentile wrt stars repos using a discrete probability distribution weighted proportional to their number of stars; the remaining are sampled from the bottom ten percentile set using similar weighted distribution. This is done to ensure fair representation of repos of all kinds, from popular projects with many issues and pull requests to small personal projects. In total, we have 300 repositories and more than 1000 accounts with at least one commit to a repository. For the content management system, we adapted Adobe Magento’s admin portal, deploying the sample data provided in the official guide. We employ OpenStreetMap<sup>9</sup> for map service implementation, confining our focus to the northeast US region due to data storage constraints. We implement a calculator and a scratchpad ourselves.

#### B4. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.1 WEBSITE IMPLEMENTATION)

Lastly, we configure the knowledge resources as individual websites, complemented with search functionality for efficient information retrieval. Specifically, we utilize Kiwix<sup>10</sup> to host an offline version of English Wikipedia with a knowledge cutoff of May 2023. The user manuals for GitLab and Adobe Commerce Merchant documentation are scraped from the official websites.

#### B5. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 16, A.3 USER ROLES SIMULATION)

Users of the same website often have disparate experiences due to their distinct roles, permissions, and interaction histories. For instance, within an E-commerce CMS, a shop owner might possess full read and write permissions across all content, whereas an employee might only be granted write permissions for products but not for customer data. We aim to emulate this scenario by generating unique user profiles on each platform.

#### B6. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 16, A.3 USER ROLES SIMULATION)

On the shopping site, we created a customer profile that has over 35 orders within a span of two years. On GitLab, we selected a user who maintains several popular open-source projects with numerous merge requests and issues. This user also manages a handful of personal projects privately. On Reddit, our chosen profile was a user who actively participates in discussions, with many posts and comments. Lastly, on our E-commerce CMS, we set up a user profile for a shop owner who has full read-and-write access to all system contents.

#### B7. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.2 ENVIRONMENT DELIVERY AND RESET)

One goal for our evaluation environment is ease of use and reproducibility. As a result, we deploy our websites in separate Docker images <sup>11</sup>, one per website. The Docker images are fully self-contained with all the code of the website, database, as well as any other software dependencies. They also do not rely on external volume mounts to function, as the data of the websites are also part of the docker image. This way, the image is easy to distribution containing all the pre-populated websites for reproducible evaluation. End users can download our packaged Docker images and run them on their systems and re-deploy the exact websites together with the data used in our benchmarks for their local benchmarking.

#### B8. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 15, A.2 ENVIRONMENT DELIVERY AND RESET)

Since some evaluation cases may require the agent to modify the data contained in the website, e.g., creating a new user, deleting a post, etc., it is crucial to be able to easily reset the website environment to its initial state. With Docker images, the users could stop and delete the currently running containers for that website and start the container from our original image again to fully reset the environment to the initial state. Depending on the website, this process may take from a few seconds to one minute. However, not all evaluation cases would require an environment reset, as many of the intents are information gathering and are read-only for the website data. Also, combined with the inference time cost for the agent LLMs, we argue that this environment reset method, through restarting Docker containers from the original images, will have a non-negligible but small impact on evaluation time.

#### B9. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 5, 3 BENCHMARK SUITE OF WEB-BASED TASKS)

We provide a benchmark with 812 test examples on grounding high-level natural language instructions to interactions in WebArena. Each example has a metric to evaluate the functional correctness of the task execution. In this section, we first formally define the task of controlling an autonomous agent through natural language. Then we introduce the annotation process of our benchmark.

#### B10. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 3, 2.1 CONTROLLING AGENTS THROUGH HIGH-LEVEL NATURAL LANGUAGE)

The WebArena environment is denoted as ${ \mathcal { E } } { = \langle { S , A , \mathcal { O } } , { \mathcal { T } } \rangle }$ with state space S, action space A (§2.4) and observation space O (§2.3). The transition function $\mathcal { T } : \mathcal { S } \times \mathcal { A } \longrightarrow \mathcal { S }$ is deterministic, and it is defined by the underlying implementation of each website in the environment. Given a task described as a natural language intent i, an agent issues an action $a _ { t } \in \mathcal A$ based on intent i, the current observation $o _ { t } \in { \mathcal { O } } .$ , the action history $\mathbf { a } _ { 1 } ^ { \ell - 1 }$ and the observation history $\mathbf { o } _ { 1 } ^ { t - 1 }$ . Consequently, the action results in a new state $s _ { t + 1 } \in S$ and its corresponding observation $o _ { t + 1 } \in \mathcal { O }$ . We propose a reward function $r ( \mathbf { a } _ { 1 } ^ { T } , \mathbf { s } _ { 1 } ^ { T } )$ to measure the success of a task execution, where $\mathbf { a } _ { 1 } ^ { T }$ represents the sequence of actions from start to the end time step T , and $\mathbf { s } _ { 1 } ^ { T }$ denotes all intermediate states. This reward function assesses if state transitions align with the expectations of the intents. For example, with an intent to place an order, it verifies whether an order has been placed. Additionally, it evaluates the accuracy of the agent’s actions, such as checking the correctness of the predicted answer.

#### B11. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 4, 2.3 OBSERVATION SPACE)

We design the observation space to roughly mimic the web browser experience: a web page URL, the opened tabs , and the web page content of the focused tab. WebArena is the first web environment to consider multi-tab web-based tasks to promote tool usage, direct comparisons and references across tabs, and other functionalities. The multi-tab functionality offers a more authentic replication of human web browsing habits compared to maintaining everything in a single tab. We provide flexible configuration to render the page content in many modes: (see Figure 3 for an example): (1) the raw web page HTML, composed of a Document Object Model (DOM) tree, as commonly used in past work (Shi et al., 2017; Deng et al., 2023; Li et al., 2020); (2) a screenshot, a pixel-based representation that represents the current web page as an RGB array and (3) the accessibility tree of the web page.<sup>2</sup> The accessibility tree is a subset of the DOM tree with elements that are relevant and useful for displaying the contents of a web page. Every element is represented as its role (e.g., a link), its text content, and its properties (e.g., whether it is focusable). Accessibility trees largely retain the structured information of a web page while being more compact than the DOM representation.

#### B12. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 4, 2.4 ACTION SPACE)

Following previous work on navigation and operation in web and embodied environments (Shi et al., 2017; Liu et al., 2018), we design a compound action space that emulates the keyboard and mouse operations available on web pages. Figure 4 lists all the available actions categorized into three distinct groups. The first group includes element operations such as clicking, hovering, typing, and key combination pressing. The second comprises tab-related actions such as opening, closing, and switching between tabs. The third category consists of URL navigation actions, such as visiting a specific URL or navigating forward and backward in the browsing history.

#### B13. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Evaluating Site Navigation and Content & Config Tasks The tasks in these categories require accessing web pages that meet certain conditions or performing operations that modify the underlying data storage of the respective websites. To assess these, we establish reward functions $r _ { \mathrm { p r o g } } ( \mathbf { s } )$ that programmatically examine the intermediate states s within an execution trajectory to ascertain whether the outcome aligns with the intended result. These intermediate states are often the underlying databases of the websites, the status, and the content of a web page at each step of the execution.

#### B14. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Evaluating each instance involves two components. First, we provide a locator, tasked with retrieving the critical content pertinent to each intent. The implementation of this locator varies from a database query, a website-supported API call, to a JavaScript element selection on the relevant web page, depending on implementation feasibility. For example, the evaluation process for the intent of the fifth example in Table 1, first obtains the URL of the latest post by examining the last state in the state sequence s. Then it navigates to the corresponding post page and obtains the post’s content by running the Javascript “document.querySelector(‘.submission\_\_inner’).outerText”.

#### B15. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

First, we define exact\_match where only aˆ that is identical with a<sup>∗</sup> receives a score of one. This function is primarily applicable to intent types whose responses follow a more standardized format, similar to the evaluation on question answering literature (Rajpurkar et al., 2016; Yang et al., 2018).

#### B16. WebArena: A Realistic Web Environment for Building Autonomous Agents (p. 6, 3.2 EVALUATION ANNOTATION)

Second, we create must\_include where any aˆ containing a<sup>∗</sup> receives a score of one. This function is primarily used in when an unordered list of text is expected or where the emphasis of evaluation is on certain key concepts. In the second example in Table 1, we expect both the correct name and the email address to be presented, irrespective of the precise wording used to convey the answer.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
