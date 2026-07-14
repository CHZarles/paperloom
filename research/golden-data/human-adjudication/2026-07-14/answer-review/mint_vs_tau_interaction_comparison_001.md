# Blind Answer Review: mint_vs_tau_interaction_comparison_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Compare MINT and tau-bench on interaction design, tool use, feedback, environment state, and reliability measurement.

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

Declared outcome: `None`

The research turn failed technically.

### Evidence cited by Answer A

No cited paper-content evidence.
## Answer B

Declared outcome: `answered`

| Axis | MINT | τ-bench |
|---|---|---|
| **Interaction design** | MINT centers on an LLM solving a task over a bounded number of turns with a simulated “lazy user”: the user gives the initial instruction, may give minimal correctness feedback, and the episode ends when the model is correct, has used its two solution attempts, or reaches the interaction limit. [1] | τ-bench models a dynamic user-agent conversation: each task has hidden instructions for the user simulator and ground-truth database write actions; the simulated user starts with a request, and the agent handles the task conversationally while referring to policy. [2] |
| **Tool use** | MINT lets the LLM either call tools by generating code or submit a solution at each turn; tool observations come from a Python interpreter, such as calculation results or error messages. [3] | τ-bench gives the agent domain-specific API tools and policy guidelines. [4] In each episode, the agent may call tools at any point while conversing with the user. [2] |
| **Feedback** | MINT explicitly studies feedback: in the lazy setting, wrong first answers receive at most the binary feedback “Your answer is wrong”; in the informative setting, GPT-4 simulates more useful natural-language feedback, and performance with this feedback is compared to lazy interaction at an interaction limit of 5. [1] [5] | τ-bench’s “feedback” is mainly conversational user response rather than correctness tutoring: a GPT-4-0613 user simulator responds stochastically based on its task prompt and the user-agent chat history, while not seeing agent-tool history. [6] |
| **Environment state** | MINT’s environment state is comparatively tool/execution oriented: the model receives Python-interpreter observations and reminders about remaining steps and solution attempts. [3] | τ-bench is explicitly stateful around a database: evaluation compares the database state at the end of the conversation to the annotated goal state. [4] Its reward uses whether the final database exactly matches the unique ground-truth outcome database, plus whether user-facing responses contain necessary information. [7] |
| **Reliability measurement** | MINT reports Success Rate, measuring the percentage of successful task instances, for interaction limits up to the kth turn. [8] For tool-augmented ability, it reports absolute performance at 5 turns and an estimated improvement per additional interaction turn from success-rate-vs.-turn curves. [9] | τ-bench proposes pass^k for reliability: the chance that all k i.i.d. trials of a task succeed, averaged across tasks. [10] It says pass^k captures reliability under conversational variations with the same underlying semantics while adhering to policies and rules, and it also reports average reward/pass^1 as the default comparison metric. [11] Main experiments run at least 3 trials per task. [12] |

**Bottom line:** MINT is primarily a bounded multi-turn task-solving benchmark for measuring how much tools and natural-language feedback improve success across turns. τ-bench is primarily a conversational, stateful tool-agent-user benchmark for measuring whether agents reliably complete policy-constrained real-world-style tasks across repeated stochastic trials.

Sources
[1] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 2.1 INTERACTION FRAMEWORK, p. 3
[2] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 4
[3] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 2.1 INTERACTION FRAMEWORK, p. 3
[4] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, Abstract, p. 1
[5] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 2.1 INTERACTION FRAMEWORK, p. 4
[6] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 3
[7] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 4
[8] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 3.1 SETUP, p. 5
[9] MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback, 3.2 MEASURING LLM’S TOOL-AUGMENTED TASK-SOLVING IN MULTI-TURN INTERACTION, p. 5
[10] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 4
[11] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 3 τ -bench: A benchmark for Tool-Agent-User Interaction, p. 5
[12] tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains, 5 Experiments, p. 6

### Evidence cited by Answer B

#### B1. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 3, 2.1 INTERACTION FRAMEWORK)

LLMs interacting with a lazy user. We consider the scenario where a user provides an initial instruction and makes minimal efforts to guide the LLM toward the final solution. This setting covers real-world problems that are “hard to solve directly, but easy to verify”: the user may not know how to solve the problem or understand the reasoning process, but it is still possible for the user to provide minimal feedback on the final outcome (e.g., “this is not what I want”). This will serve as a baseline for evaluating LLM’s ability to solve tool-augmented tasks and leverage natural language feedback. The LLM is given two attempts to propose solutions for each problem, with a limit on the number of interaction turns k (§3.1). Upon a proposed solution, MINT simulates users that check the solution’s correctness with ground truths. When the first attempt is wrong, the user responds “Your answer is wrong.” The interaction ends either after the LLM has made two attempts to propose a solution, or when a proposed solution is correct (5th turn of Fig. 1), or when the k-th turn of interaction is reached. We consider this as the case of Lazy User-LLM Interaction since the simulated user provides at most one additional binary feedback during interaction. We follow standard evaluation practice and use established evaluation metrics for each task in §2.2.

#### B2. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 4, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

Task instances. As shown in Figure 2d, each τ -bench task instance has two parts: an instruction for the user simulation (hidden from agents), and an annotation of the ground truth database write actions (and optionally, ground truth outputs for user questions). The instruction sets up user identity, intent, and preferences in a way that guarantees only one possible outcome under the domain policy. Each task episode consists of the simulated user starting with a request, which the agent handles in a conversational manner while being able to call tools at any point and refer to the provided policy. Once the episode ends, the database state and agent-to-user messages are used to compute the reward.

#### B3. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 3, 2.1 INTERACTION FRAMEWORK)

LLMs interacting with a lazy user and tools. Under the lazy User-LLM interaction setting, we measure the LLM’s ability to solve tasks using tools by comparing their task-solving success rate across different interaction limits k. For each turn, the LLM can choose to interact with tools (generate code to call equation-solver in Fig. 1) or propose a solution (5th turn in Fig. 1). To keep the LLM from getting stuck in an infinite loop of tool-calling without proposing a solution, MINT reminds the LLM: “You have X steps left and Y chances to propose solution left,” and provides an additional instruction at the last turn: “You should take the last step to propose a solution.” Intuitively, with more interaction with tools, the LLM can get more useful observations through the Python interpreter (e.g., calculation results, error messages). We vary $k \in \{ 1 , 2 , 3 , 4 , 5 \}$ and compare the models’ success rate with each k. We consider LLM’s performance gain w.r.t. k and the absolute performance at $k = 5$ as their tool-augmented task-solving ability (§3.2).

#### B4. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 1, Abstract)

Existing benchmarks do not test language agents on their interaction with human users or ability to follow domain-specific rules, both of which are vital for deploying them in real world applications. We propose τ -bench, a benchmark emulating dynamic conversations between a user (simulated by language models) and a language agent provided with domain-specific API tools and policy guidelines. We employ an efficient and faithful evaluation process that compares the database state at the end of a conversation with the annotated goal state. We also propose a new metric (pass^k) to evaluate the reliability of agent behavior over multiple trials. Our experiments show that even state-of-the-art function calling agents (like gpt-4o) succeed on < 50% of the tasks, and are quite inconsistent (pass^8 < 25% in retail). Our findings point to the need for methods that can improve the ability of agents to act consistently and follow rules reliably.

#### B5. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 4, 2.1 INTERACTION FRAMEWORK)

Informative user-LLM interaction with language feedback. Beyond lazy User-LLM interaction, we investigate how the LLM performs when the user mirrors a patient teacher who provides useful suggestions (e.g., technical users who understand the problem-solving process). However, collecting human language feedback for LLM evaluation presents reproducibility challenges due to inconsis tent standards and can be costly, particularly for open-source communities with relatively fewer resources<sup>4</sup>. To address these issues, we prompt GPT-4 (§F.4.2) to simulate user language feedback (dotted boxes in Fig. 1). We validate the effectiveness of GPT-4 feedback in a human evaluation (§3.6). We compare the performance between (1) simulated language feedback and (2) lazy user-LLM interaction, both in the setting of tool-augmented interaction with an interaction limit $\dot { k } = 5$ We consider performance (absolute) and improvements from language feedback as LLM’s ability to leverage natural language feedback.

#### B6. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 3, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

User simulation. We use a language model (gpt-4-0613) to simulate a human user interacting with the agent. The user state $s _ { u s e r }$ consists of an initial system prompt with the task instruction (Figure 2d) along with the entire conversation history between the user and the agent so far. The user cannot see the interaction history between the agent and API tools. The agent can interact with the user using any natural language message, $\mathrm { e . g . , } a _ { u s e r }$ can be "Your reservation has been updated, is there anything else I can help with?". The transition $\mathcal { T } _ { u s e r } : ( s _ { u s e r } , a _ { u s e r } ) \overline { { \ } } \mapsto ( s _ { u s e r } ^ { \prime } , o _ { u s e r } )$ is stochastic and attaches the agent’s message to the chat history followed by sampling a new user message from the $\operatorname { L M } , \operatorname { e . g . } , o _ { u s e r }$ can then be "Yes, I also want to cancel another flight." When the user issues $O _ { u s e r } { = } " { \# } { \# } { \# } { \bf S } { \mathrm { T } } 0 { \mathrm { P } } { \# } { \# } { \# } { \# }  $ , the episode finishes and the agent is evaluated.

#### B7. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 4, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

Reward. The reward of a task episode $r = r _ { \mathrm { a c t i o n } } \times r _ { \mathrm { o u t p u t } } \in \{ 0 , 1 \}$ is based on (1) whether the final database is identical to the unique ground truth outcome database $( r _ { \mathrm { a c t i o n } } )$ , and (2) whether the agent’s responses to the user contain all necessary information $( r _ { \mathrm { o u t p u t } } )$ . So for the task of Figure 2d, the agentuser dialogue can be varied and the agent can call various (read) actions, but the agent is successful if the only database write action is return\_delivered\_order\_items(order\_id="#W2890441", item\_ids=["2366567022"], payment\_method\_id="credit\_card\_1061405"), and the user responses contain "54.04", "41.64" as substrings. Note that r = 1 might be a necessary but not sufficient condition for a successful episode e.g., the agent might issue the return without explicit user confirmation, which violates the policy. Nevertheless, our proposed rule-based reward is fast to compute and faithful, and already poses significant challenges for current models and methods as we show in § 5.

#### B8. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 5, 3.1 SETUP)

Metric. We consider Success Rate SR as our evaluation metric, which measures the percentage of successful task instances. For interaction limit k, we start from scratch and allow each LLM to interact up to the k-th turn and measure their corresponding $S R _ { k }$ . Unless otherwise noted, we limit $k \in [ 1 , 5 ]$ where $k = 1$ means no interaction and $\bar { k } = 5$ maximizes interaction turns within most modern LLMs’ context window (4,096 tokens).

#### B9. MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback (p. 5, 3.2 MEASURING LLM’S TOOL-AUGMENTED TASK-SOLVING IN MULTI-TURN INTERACTION)

We ask LLMs to solve tasks (§2.2) with different interaction limits $k \in \{ 1 , 2 , 3 , 4 , 5 \}$ without natural language feedback (Fig. 1 without red dotted box), and quantify LLMs’ tool-augmented task-solving capability by (1) absolute performance $S R _ { 5 }$ and (2) improvement per additional interaction turn $\Delta _ { \mathrm { t o o l s } }$ estimated as the slope b from least-square regression mi $1 _ { b , a } \sum _ { k } ( b \cdot k + a - S R _ { k } ) ^ { 2 }$ (Tab. 2). Since the underlying $S R _ { k }$ vs. k relationship might not be linear, we only use the regression coefficient (with $R ^ { 2 } )$ as a rough estimate of the improvement rate to complement the absolute success rate $S R _ { 5 }$ for a more comprehensive understanding of the models’ capabilities.

#### B10. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 4, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

Pass^k metric. For tasks like code generation with good verification techniques (unit tests), the community has defined the pass@k (pass at k) metric as the chance that at least one out of k i.i.d. task trials is successful, which captures the trend of agents enabling discovery of solutions with scaling of inference-time compute [5]. For real-world agent tasks requiring reliability and consistency like customer service, we propose a new metric – pass^k (pass hat k), defined as the chance that all k i.i.d. task trials are successful, averaged across tasks. Therefore, if a task is run for n trials and c of those trials end up successful (r = 1), unbiased estimates for pass^k and pass@k would be:

#### B11. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 5, 3 τ -bench: A benchmark for Tool-Agent-User Interaction)

In our case, for the same task, the user prompt and database transitions are the same, with just the LM sampling of the user and agent messages generating sufficient stochasticity. Thus, pass^k can capture the reliability of the agent at handling variations in conversations with the same underlying semantics while adhering to the domain policies and rules. By default, we report the average reward across tasks, $\mathrm { p a s s } ^ { \wedge } \mathrm { l } \mathrm { = p a s s } @ 1 \mathrm { = } \mathbb { E } [ r ] = \mathbb { E } [ c / n ]$ , as the main metric for comparing agents.

#### B12. tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains (p. 6, 5 Experiments)

We limit each task to at most 30 agent actions (either tool calls or user responses). For main results (Table 2), we run at least 3 trials per task. The LM temperature is 0.0 for agent and 1.0 for user.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
