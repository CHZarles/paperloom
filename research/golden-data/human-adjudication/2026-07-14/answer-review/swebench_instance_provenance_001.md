# Blind Answer Review: swebench_instance_provenance_001

Judge only the user-visible answer and its cited passages. Do not reward exact authored Anchor IDs.

## Conversation

- **user**: Trace how an original SWE-bench task is created from GitHub data and explain how a generated patch is evaluated.

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

An original SWE-bench task is built as a provenance chain from a real GitHub PR/issue pair, then evaluated by replaying tests against a generated patch.

## Task creation from GitHub data

1. **Start with GitHub PRs from selected repos.** SWE-bench collects pull requests from 12 popular open-source Python repositories, totaling about 90,000 PRs; each PR is tied to a codebase through its base commit. [1]

2. **Filter to merged PRs that look like issue-resolving changes with tests.** Candidate tasks are merged PRs that both resolve a GitHub issue and modify repository test files, using the test-file change as evidence that tests were contributed to check the issue resolution. [2]

3. **Recover the issue/problem statement.** The construction process scans PR text for issue-number references preceded by resolution keywords such as “closes,” “fixes,” or “resolves,” retrieves each linked issue’s data, and forms the problem statement by concatenating the issue title and body, including multiple linked issues when present. [3]

4. **Represent the starting codebase by repository plus base commit.** SWE-bench does not store every codebase in plaintext; each task refers to the relevant codebase through the `repo` and `base_commit` fields, and mirrored repositories preserve original commit hashes, history, branches, and tags so the base commit can be checked out reproducibly. [3]

5. **Split the PR diff into solution and tests.** The PR’s file-change diff is parsed as patch blocks: blocks whose file paths contain testing-related keywords such as “tests” or “testing” become the test patch `T`, while the remaining blocks become the reference solution `δ`. [3]

6. **Validate the candidate by execution.** For each candidate, SWE-bench applies the PR’s test content and records test results before and after applying the PR’s non-test content; it removes instances without at least one fail-to-pass test and removes instances with installation or runtime errors. [4]

7. **Exclude unusable or impossible cases.** The authors define a virtual environment, install the codebase, run the tests before and after the solution, drop any candidate if a verification step fails, require at least one test to change from fail to pass, and exclude instances where tests invoke newly created functions or classes first introduced in the solution. [5]

## What a task contains

A task instance includes fields such as `base_commit`, `repo`, `problem_statement`, original PR `pull_number`, linked `issue_numbers`, reference-solution `patch`, hidden `test_patch`, and lists of `FAIL_TO_PASS` and `PASS_TO_PASS` tests. [6]

## Evaluation of a generated patch

1. **Model input and output.** The model receives the issue text description plus a complete codebase, and it must produce edits represented as a patch file. [7]

2. **Set up the evaluation codebase and tests.** The evaluation procedure installs the codebase, applies the test patch `T`, and then applies the model’s prediction patch `\hat{δ}` to the codebase with those tests. [8] [9] [10]

3. **Run the associated tests and decide success.** SWE-bench applies the generated patch with Unix `patch`, executes the unit and system tests associated with the task instance, and counts the issue as resolved if the patch applies successfully and all those tests pass. [11]

4. **Use fail-to-pass and pass-to-pass status checks.** For task completion, SWE-bench compares the parsed evaluation test-status mapping against the ground-truth `FAIL_TO_PASS` and `PASS_TO_PASS` tests; all of those tests must be present and passing, and any missing or non-passing test is treated as failure. [12]

In short: SWE-bench turns a merged GitHub PR into a task by extracting the linked issue as the prompt, the PR base commit as the starting codebase, the PR’s test changes as hidden tests, and the PR’s non-test changes as the reference solution; a generated patch is then evaluated by applying it to that starting codebase plus the hidden tests and requiring all relevant fail-to-pass and pass-to-pass tests to pass.

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.2 CONSTRUCTION PROCESS, p. 18
[4] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[5] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.1 HIGH LEVEL OVERVIEW, p. 16
[6] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.2 CONSTRUCTION PROCESS, p. 17
[7] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3
[8] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[9] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[10] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[11] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3
[12] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21

### Evidence cited by Answer A
#### A1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage I: Repo selection and data scraping. We start by collecting pull requests (PRs) from 12 popular open-source Python repositories on GitHub, producing about ∼ 90,000 PRs in total. We focus on popular repositories as they tend be better maintained, have clear contributor guidelines, and have better test coverage. Each PR has an associated codebase specified by it’s base commit.

#### A2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage II: Attribute-based filtering. We create candidate tasks by selecting the merged PRs that (1) resolve a GitHub issue and (2) make changes to the test files of the repository, which indicates that the user likely contributed tests to check whether the issue has been resolved.

#### A3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 18, A.2 CONSTRUCTION PROCESS)

concatenating these components’ text data, we first remove any Markdown-style comments, then
look through the remaining text for references to issue numbers (a pound # sign followed by a
number) and check whether the word preceding the issue number reference is included in a set of
keywords suggesting that the issue was resolved by the PR (e.g. “closes”, “fixes”, “resolves”). The
found issues are recorded in the issue numbers field, then separate web requests are made to
retrieve each issue’s data. To form the problem statement, each issue’s title and body are
added together and then concatenated with the next issue’s if there are multiple. It is also during this
step that the hints text field is created and collected from the PR’s comment section, where text
from comments created before the PR’s initial commit. The intuition for this collection methodology
is that such PR comments would likely contain natural language and pseudo-code suggestions to
the original human task worker regarding how to complete the problem at hand. The experiments
presented in this work do not make use of hints text, but we believe this information may be
interesting for future investigations.

Codebase. The codebase C content is not stored in plaintext for every task instance. Rather, the
task instance contains a reference to the relevant codebase via the repo and base commit field.
Both fields are available in the original PR’s data. To make retrieval of the codebase C from these
two elements reproducible and reliable, we create mirrors of the original repository. Mirrors for
the repository constituting both the evaluation and fine tuning data are collected and open-sourced
under the SWE-bench GitHub organization. Because an original repository’s code may be subject to
changes in its commit and edit history outside of the authors’ control, we choose to create a mirror
repository to ensure that later modifications to the codebase do not potentially render a task instance
unusable due to a corruption or removal of the associated base commit. Additionally, we create
a mirror instead of cloning and storing the latest version of a repository. This is because a mirror
retains the original commit hashes, history, branches, and tags, serving as a faithful and complete
history of the technical details of the original repository. A mirror does not retain stars, watchers,
issues, or pull requests from the original repository.

We create a mirror from a repository after and within the same day when task instances were col-
lected. The mirror retains the original repository’s “owner/name” moniker, except that the “/”
character is converted to a “ ” to confirm to GitHub naming conventions. Given this infrastructure,
retrieving a task instance’s codebase is straightforward. First, the correct mirror can be cloned from
the SWE-bench organization using repo. Next, within the local copy of the mirror, checking out
the base commit will reset the repository to codebase C. To proceed to another task instance
from the same repository, git version control is used to automatically remove any modifications
associated with the current task instance before checking out the next task instance’s base commit.

Solution, Test Patches. The solution δ and tests T are derived from the file changes data, or diff,
of a PR. As mentioned in Section 2.1, the original diff along with solution δ and tests T are
represented as a .patch file, a format for efficiently specifying transformations to line-based text
files. Generally speaking, a .patch is structured as a list of blocks, where each block consists of
a header and one or more hunks that collectively correspond to changes to a single file. The header
contains metadata specifying a file path and line numbers, while the actual modifications to the
target file are encoded as multiple lines prefixed by “+” and “-” to indicate additions and removals.
To create the tests T , we first identifying every unique block within the patch, then pick out and
conglomerate blocks with file paths that contain testing-related keywords (e.g. “tests”, “testing”).
The remaining blocks are merged to form the solution δ. We validate the robustness of the script
written to parse correctly T and δ by applying both patches to the corresponding codebase C and
running the tests; we then check that the results reproduce the behavior of the base PR’s diff data.
The solution δ is saved as the patch field while the tests T are saved as the test patch field.

Remaining Fields. The created at field is a timestamp that specifies when the base PR was cre-
ated. We retain the created at field from the original data and use this field to perform temporal
analysis of model performance. The version field is a string that corresponds to the release ver-
sion, with respect to the repo, during which the PR was released. Depending on availability and the
amount of effort required for each method, we create the version field by retrieving the informa-
tion directly from the source code, building the repository locally and invoking code to display the
version to standard output, or comparing the created at field with a timeline of release versions

#### A4. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage III: Execution-based filtering. For each candidate task, we apply the PR’s test content, and log the associated test results before and after the PR’s other content is applied. We filter out task instances without at least one test where its status changes from a fail to pass (henceforth referred to as fail-to-pass test). We also filter out instances that result in installation or runtime errors.

#### A5. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 16, A.1 HIGH LEVEL OVERVIEW)

Execution-based validation. We verify the usability of a task instance via execution. For each candidate, we first define a virtual environment to serve as an execution context, then install C before applying any patches, and finally run T once before and once after the solution δ is applied. A candidate is removed from consideration for the final dataset if any step in the verification process fails. In addition, to ensure that a solution δ is non-trivial, we compare the pre-solution and postsolution validation logs to check for whether there are one or more tests in T where the status changes from fail to pass. Lastly, we exclude task instances with tests that invoke newly created functions or classes first introduced in the solution δ. Since naming such constructs is typically an arbitrary process and usually not explicitly specified in the problem statement, resolving tests such as these may be an impossible task even for human developers. Information about execution contexts, codebase installation, determining test statuses from logs, and more are in Appendix A.3.

#### A6. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 17, A.2 CONSTRUCTION PROCESS)

Table 9: Description of each field of a SWE-bench task instance object. See § A.2 for details regarding how each field is collected.
Field | Description | base_commit created_at hints_text instance_id | (str) The commit ID that the original PR is applied on top of (date) Datetime object of when PR was first created (not merged) (str) Natural language suggestions for how to solve problem | issue_numbers patch | (str) A unique identifier created from repo and pul1_number (list) List of issue numbers that the original pull request resolves (str) . pat ch-format styled string that is a reference solution | problem_statement pull_number | to the problem, extracted from the original PR&#x27;s code changes (str) Natural language description of desired change to codebase (int) The pull number of the original pull request | test_patch | (str) . pat ch-format styled string containing unseen tests for checking if a task was solved, extracted from the original | version | PR&#x27;s code changes (str) Release version (w.r.t. repo) during which PR was created | repo FAIL_TO_PASS PASS_TO_PASS env_install_commit | (str) The repository the task instance originates from (list) List of tests that change in status from fail to pass (list) List of tests that change in status from pass to pass

#### A7. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Model input. A model is given an issue text description and a complete codebase. The model is then tasked to make an edit to the codebase to resolve the issue. In practice, we represent edits as patch files, which specify which lines in the codebase to modify in order to resolve the issue.

#### A8. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

3. Run installation command to instantiate codebase C.

#### A9. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

4. Apply test patch $T$ to codebase C.

#### A10. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

5. Apply prediction patch $\hat { \delta }$ to codebase C with tests T .

#### A11. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Evaluation metrics. To evaluate a proposed solution, we apply the generated patch, using unix’s patch program, to the codebase and then execute the unit and system tests associated with the task instance. If the patch applies successfully and all of these tests pass we consider the proposed solution to have successfully resolved the issue. The metric for our benchmark is the percentage of task instances that are resolved. Additional technical details in Appendix A.4.

#### A12. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

Evaluation Metrics Calculation. To determine task completion, we compare the test-to-status mapping parsed from log<sub>ˆ</sub> with the list of tests corresponding to the FAIL TO PASS and PASS TO PASS keys from the ground truth test results data structure. Determining task completion is straightforward; we check that all FAIL TO PASS and PASS TO PASS tests are found and have a pass status in the evaluation test-to-status mapping. If a test is missing or has a non-pass status, it is considered a fail status. As defined and used in the main paper, a task is considered solved if all tests across FAIL TO PASS and PASS TO PASS pass.

## Answer B
Declared outcome: `answered`

A SWE-bench task is built from a single GitHub PR through three stages, then evaluated by running a model-generated patch against a cached test oracle.

## How an original SWE-bench task is created

**Stage I – Repository selection and data scraping.** Authors collect about 90,000 PRs from 12 popular open-source Python repositories on GitHub. Popular repos are chosen because they tend to be better maintained, have clear contributor guidelines, and have better test coverage. Each PR has an associated codebase specified by its base commit. [1] The 2,294 finalized task instances span this same set of 12 repositories. [2]

**Stage II – Attribute-based filtering.** Candidate tasks are merged PRs that (1) resolve a GitHub issue and (2) make changes to test files of the repository, which indicates the user contributed tests to check whether the issue has been resolved. [3] Appendix A.1 sharpens this to three hard conditions: the PR must be Merged; it must resolve one or more issues (links are scanned in the PR's title, body, and commit messages via tokens like "closes", "fixes", or "resolves" preceding an issue number); and it must edit a file path containing a testing-related keyword such as "test" or "testing". [4]

Each surviving PR is materialized as a candidate task instance with three components:

- **Codebase C**: a mirror of the repository (identified as owner/name), checked out at the PR's base commit, giving the pre-PR state. [5]
- **Problem statement P**: the concatenation of every resolved issue's title and body, plus any issue comments written before the timestamp of the PR's initial commit. This timestamp cutoff prevents solution leakage. Markdown comments are stripped before parsing. [6]
- **Two split patches from the PR diff**: blocks whose file paths contain testing keywords ("tests", "testing") are conglomerated into the **test patch T**; the remaining blocks are merged into the **solution / gold patch δ**, saved in the `patch` field. A robustness check reapplies both to C and re-runs tests to confirm the parser reproduces the original diff's behavior. [7]

**Stage III – Execution-based filtering.** A conda virtual environment is built per repository release version. For each candidate, the validation engine (a) checks out the base commit so C is pristine, (b) runs the installation command, (c) applies test patch T, (d) runs T to obtain `log_pre`, (e) applies solution patch δ on top of C with T, and (f) runs T again to obtain `log_post`. Any failure discards the candidate, removing roughly half of survivors. [8] [9] [10] [11] [12] [13] [14] [15] [16] [17]

A candidate is finalized only if it satisfies two criteria: (1) comparing `log_pre` and `log_post` must yield at least one FAIL_TO_PASS test — a test that fails without the gold patch and passes with it — so the solution is non-trivial; and (2) the task instance must not contain any test that invokes functions or classes first introduced in δ, since naming such constructs is arbitrary and not specified in the issue text, making those tests unsolvable even for a human. [18] [19] Alongside each surviving instance, a cached ground-truth test-results structure is saved with keys `FAIL_TO_FAIL`, `FAIL_TO_PASS`, `PASS_TO_FAIL`, and `PASS_TO_PASS`, each listing the tests that fell into that transition. [20] The 2,294 finalized task instances are written to a single open-source JSON file. [21]

## How a generated patch is evaluated

The model receives the problem statement and codebase and must produce a `.patch` prediction δ̂. [22] Evaluation runs a per-prediction pipeline: [23]

1. Remove any file changes and check out the task instance's base commit, restoring C. [24]
2. Activate the conda executable context for the task instance's release version. [25]
3. Run the installation command to instantiate C. [26]
4. Apply the test patch T to C. [27]
5. Apply the prediction patch δ̂ on top of C with tests T. [28]
6. If step 5 fails, automatically repair δ̂ (remove unnecessary context lines, recalculate header values) and reapply. [29]
7. Run the testing script (the repo's framework, e.g. pytest or tox, with paths from T) to generate `log_δ̂`. [30]

Steps 1–4 essentially never fail because the same sequence succeeded during construction. If step 5/6 or step 7 ultimately fails, the prediction is automatically scored 0. Otherwise `log_δ̂` is parsed by the same repository-specific parser into a test-to-status mapping. [31]

**Score calculation.** The parsed mapping is compared against the cached ground-truth: every test listed under `FAIL_TO_PASS` and `PASS_TO_PASS` must appear with a pass status; any missing or non-pass entry is treated as a fail. A task instance is resolved only if **all FAIL_TO_PASS and PASS_TO_PASS tests pass**. [32] The benchmark's overall metric is the percentage of task instances that satisfy this. [33]

This dual check makes evaluation robust: FAIL_TO_PASS tests confirm the issue is fixed (40% of instances have at least two), while the median of 51 PASS_TO_PASS tests enforces that prior functionality is preserved. [34] [35]

Sources
[1] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[2] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 3
[3] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[4] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.1 HIGH LEVEL OVERVIEW, p. 16
[5] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.1 HIGH LEVEL OVERVIEW, p. 16
[6] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.2 CONSTRUCTION PROCESS, p. 17
[7] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.2 CONSTRUCTION PROCESS, p. 18
[8] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[9] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[10] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[11] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[12] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[13] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[14] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[15] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[16] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[17] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 19
[18] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.1 BENCHMARK CONSTRUCTION, p. 2
[19] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.1 HIGH LEVEL OVERVIEW, p. 16
[20] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 20
[21] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.3 EXECUTION-BASED VALIDATION, p. 20
[22] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3
[23] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[24] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[25] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[26] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[27] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[28] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[29] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[30] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[31] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[32] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.4 EVALUATION PROCEDURE, p. 21
[33] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.2 TASK FORMULATION, p. 3
[34] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, 2.3 FEATURES OF SWE-BENCH, p. 3
[35] SWE-bench: Can Language Models Resolve Real-World GitHub Issues?, A.5 EVALUATION TEST SET CHARACTERIZATION, p. 21

### Evidence cited by Answer B
#### B1. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage I: Repo selection and data scraping. We start by collecting pull requests (PRs) from 12 popular open-source Python repositories on GitHub, producing about ∼ 90,000 PRs in total. We focus on popular repositories as they tend be better maintained, have clear contributor guidelines, and have better test coverage. Each PR has an associated codebase specified by it’s base commit.

#### B2. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.1 BENCHMARK CONSTRUCTION)

Through these stages of filtering, the original 90,000 PRs are filtered down to the 2,294 task instances which comprise SWE-bench. A final breakdown of these task instances across repositories is presented in Figure 3, and Table 1 highlights the key features of SWE-bench task instances. We highlight that the codebases are large with thousands of files, and the reference pull requests often make changes to multiple files at once. Technical details about SWE-bench’s construction pipeline are discussed in Appendix A. Additional dataset statistics are in Appendix A.5.

#### B3. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage II: Attribute-based filtering. We create candidate tasks by selecting the merged PRs that (1) resolve a GitHub issue and (2) make changes to the test files of the repository, which indicates that the user likely contributed tests to check whether the issue has been resolved.

#### B4. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 16, A.1 HIGH LEVEL OVERVIEW)

Task instance construction. We construct candidate task instances from PRs that satisfy three conditions. First, the PR’s status must be Merged. A Merged status indicates that the PR’s associated code changes were accepted and incorporated into its parent repository. Second, the PR resolves one or more issues in its repository. An issue is defined according to its canonical usage in GitHub as a digital ticket for tracking bugs, enhancements, or any general development goals for a software project. We scan a PR’s title, body, and commit messages for linked issues (i.e. “fixes #24”). Third, the PR must introduce one or more new tests. A new test is counted when a PR’s code changes edits a file path containing a testing-related keyword (e.g. “test”, “testing”).

#### B5. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 16, A.1 HIGH LEVEL OVERVIEW)

A PR that satisfies these criteria is then converted into a candidate task instance such as the example in Figure 7. The codebase C is identified by the repository’s owner/name moniker and the pull request’s base commit. Recovering the actual codebase from this information is straightforward. We create mirrors of the original GitHub repositories, where each mirror is uniquely identified as owner name. Cloning a repository’s corresponding mirror and checking out the base commit yields C in its pre-PR state. The problem statement P is an aggregate of all related issues’ titles and descriptions along with any subsequent comments written before the timestamp of the PR’s initial commit to avoid leakage of solution details. A PR’s code changes are separated into a test patch and a gold patch δ. T consists of all tests from files edited in the test patch. As shown in Figure 7, both T and δ are stored as patch files. Further details about parsing PR and semantic data is in Appendix A.2.

#### B6. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 17, A.2 CONSTRUCTION PROCESS)

Problem Statement. The problem statement P for each task instance is readily available as the problem statement field. The problem statement is an aggregate of all issues’ first comments along with any comments attached to those issues that were created before the creation date of the PR’s initial commit. We crawl for issues from PR’s title, body, and commit messages. After concatenating these components’ text data, we first remove any Markdown-style comments, then look through the remaining text for references to issue numbers (a pound # sign followed by a number) and check whether the word preceding the issue number reference is included in a set of keywords suggesting that the issue was resolved by the PR (e.g. “closes”, “fixes”, “resolves”). The found issues are recorded in the issue numbers field, then separate web requests are made to retrieve each issue’s data. To form the problem statement, each issue’s title and body are added together and then concatenated with the next issue’s if there are multiple. It is also during this step that the hints text field is created and collected from the PR’s comment section, where text from comments created before the PR’s initial commit. The intuition for this collection methodology is that such PR comments would likely contain natural language and pseudo-code suggestions to the original human task worker regarding how to complete the problem at hand. The experiments presented in this work do not make use of hints text, but we believe this information may be interesting for future investigations.

#### B7. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 18, A.2 CONSTRUCTION PROCESS)

Solution, Test Patches. The solution δ and tests T are derived from the file changes data, or diff, of a PR. As mentioned in Section 2.1, the original diff along with solution δ and tests T are represented as a .patch file, a format for efficiently specifying transformations to line-based text files. Generally speaking, a .patch is structured as a list of blocks, where each block consists of a header and one or more hunks that collectively correspond to changes to a single file. The header contains metadata specifying a file path and line numbers, while the actual modifications to the target file are encoded as multiple lines prefixed by “+” and “-” to indicate additions and removals. To create the tests T , we first identifying every unique block within the patch, then pick out and conglomerate blocks with file paths that contain testing-related keywords (e.g. “tests”, “testing”). The remaining blocks are merged to form the solution δ. We validate the robustness of the script written to parse correctly T and δ by applying both patches to the corresponding codebase C and running the tests; we then check that the results reproduce the behavior of the base PR’s diff data. The solution δ is saved as the patch field while the tests T are saved as the test patch field.

#### B8. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

Validation Engine. The purpose of the validation engine is to verify candidate task instances. Specifically, this step checks first, that the solution δ and tests T can be applied to codebase C, and second, that the codebase can be properly installed and run within the corresponding virtual environment. To do this, we perform validation repository-by-repository, where for each repository’s set of task instances, we perform the following procedure:

#### B9. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

Executable Contexts. We choose to create executable contexts per release version after experimenting with various degrees of granularity with regards to what definition level to define virtual environments for. Defining task instance-specific contexts is most conducive to ensuring end-to-end installation success, but comes at the cost of laborious manual handcrafting. On the other hand, a repository-specific context based on the latest version of a repository is typically too coarse of a definition that is not compatible with older versions’ requirements. We find that release versions are a good proxy for capturing the dependency requirements across a subset of task instances, striking a manageable balance between installation success and manual effort. We manually create each executable context by examining the codebase of the latest task instance for each version. Based on the source code and documentation typically found in the repository’s README and CONTRIBUTING guides, we find out the Python version, necessary dependencies, and installation command.

#### B10. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

1. Create executable contexts as conda envs. based on latest task instance per version.

#### B11. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(a) Remove any file changes and checkout the task instance’s base commit. This sets the repository to codebase C.

#### B12. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(b) Run the installation command to instantiate codebase C.

#### B13. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(c) Apply the test patch T to codebase C.

#### B14. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(d) Run the testing script, determined from test patch T , to generate test result logs $l o g _ { p r e }$

#### B15. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(e) Apply the solution δ patch to the codebase C with tests T .

#### B16. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

(f) Run the testing script from part (d) again to generate test result logs $l o g _ { p o s t }$

#### B17. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 19, A.3 EXECUTION-BASED VALIDATION)

The testing command consists of the testing framework used by the repository (e.g. pytest, tox) with paths specified in T appended. The testing command would run any and all tests that are specified within the contents of each file path. If any of the steps (a) through (f) fails, the candidate task instance is discarded from consideration. With moderate variation across repositories, we observe that this step generally removes half of the candidate task instances.

#### B18. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 2, 2.1 BENCHMARK CONSTRUCTION)

Stage III: Execution-based filtering. For each candidate task, we apply the PR’s test content, and log the associated test results before and after the PR’s other content is applied. We filter out task instances without at least one test where its status changes from a fail to pass (henceforth referred to as fail-to-pass test). We also filter out instances that result in installation or runtime errors.

#### B19. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 16, A.1 HIGH LEVEL OVERVIEW)

Execution-based validation. We verify the usability of a task instance via execution. For each candidate, we first define a virtual environment to serve as an execution context, then install C before applying any patches, and finally run T once before and once after the solution δ is applied. A candidate is removed from consideration for the final dataset if any step in the verification process fails. In addition, to ensure that a solution δ is non-trivial, we compare the pre-solution and postsolution validation logs to check for whether there are one or more tests in T where the status changes from fail to pass. Lastly, we exclude task instances with tests that invoke newly created functions or classes first introduced in the solution δ. Since naming such constructs is typically an arbitrary process and usually not explicitly specified in the problem statement, resolving tests such as these may be an impossible task even for human developers. Information about execution contexts, codebase installation, determining test statuses from logs, and more are in Appendix A.3.

#### B20. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 20, A.3 EXECUTION-BASED VALIDATION)

Alongside the task instances, we also create a corresponding folder containing the ground truth test results. For each task instance, from their respective $l o g _ { p r e }$ and $l o g _ { p o s t }$ test-to-status mappings, we create a test results data structure where the keys are FAIL TO FAIL, FAIL TO PASS, PASS TO FAIL, and PASS TO PASS, and the values are lists of tests. By “caching” these results, we remove the need to re-run the solution δ at evaluation time (although re-running is an available option). We use this data structure to verify task completion, as discussed in Section A.4.

#### B21. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 20, A.3 EXECUTION-BASED VALIDATION)

If a task instance fulfills these two criteria, then it is included in the evaluation dataset. Table 10 displays a summary of how many task instances were removed from consideration across the construction process and execution based validation steps. We save all finalized task instances to a single .json file that is open sourced and available for download.

#### B22. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Model input. A model is given an issue text description and a complete codebase. The model is then tasked to make an edit to the codebase to resolve the issue. In practice, we represent edits as patch files, which specify which lines in the codebase to modify in order to resolve the issue.

#### B23. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

LM; the LM then generates a .patch prediction ${ \hat { \delta } } .$ In the evaluation step, the following steps are performed per prediction on the target task instance:

#### B24. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

1. Remove any file changes and checkout the task instance’s base commit. This sets the repository to codebase C.

#### B25. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

2. Activate the executable context corresponding to the task instance’s version.

#### B26. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

3. Run installation command to instantiate codebase C.

#### B27. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

4. Apply test patch $T$ to codebase C.

#### B28. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

5. Apply prediction patch $\hat { \delta }$ to codebase C with tests T .

#### B29. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

6. If the previous step fails, we attempt to fix prediction patch $\hat { \delta }$ automatically and reapply it.

#### B30. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

7. Run the testing script, determined from test patch T , to generate test result logs $l o g _ { \hat { \delta } }$

#### B31. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

Steps 1 through 4 reliably do not fail due to verification during the task instance validation process. If applying the prediction patch (Step 5) fails, we attempt to repair the prediction patch file by removing unnecessary context lines and recalculating the header values (Step 6). If the remaining patch fails again or running the test command (Step 7) fails, then the prediction is automatically given a score of 0. Assuming these steps succeed, the output log log can then be converted to a test-to-status mapping, identical in structure to the via the appropriate, repository-specific parser introduced in § A.3.

#### B32. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.4 EVALUATION PROCEDURE)

Evaluation Metrics Calculation. To determine task completion, we compare the test-to-status mapping parsed from log<sub>ˆ</sub> with the list of tests corresponding to the FAIL TO PASS and PASS TO PASS keys from the ground truth test results data structure. Determining task completion is straightforward; we check that all FAIL TO PASS and PASS TO PASS tests are found and have a pass status in the evaluation test-to-status mapping. If a test is missing or has a non-pass status, it is considered a fail status. As defined and used in the main paper, a task is considered solved if all tests across FAIL TO PASS and PASS TO PASS pass.

#### B33. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.2 TASK FORMULATION)

Evaluation metrics. To evaluate a proposed solution, we apply the generated patch, using unix’s patch program, to the codebase and then execute the unit and system tests associated with the task instance. If the patch applies successfully and all of these tests pass we consider the proposed solution to have successfully resolved the issue. The metric for our benchmark is the percentage of task instances that are resolved. Additional technical details in Appendix A.4.

#### B34. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 3, 2.3 FEATURES OF SWE-BENCH)

Robust evaluation. For each task instance, there is at least one fail-to-pass test which was used to test the reference solution, and 40% of instances have at least two fail-to-pass tests. These tests evaluate whether the model addressed the problem in the issue. In addition, a median of 51 additional tests run to check whether prior functionality is properly maintained.

#### B35. SWE-bench: Can Language Models Resolve Real-World GitHub Issues? (p. 21, A.5 EVALUATION TEST SET CHARACTERIZATION)

Attribute Distributions. In Figure 9, we present plots of the cumulative distribution function for attributes introduced in Table 1. From these plots, we see that the median SWE-bench task instance has a problem description of 140 words, and will take place within a codebase containing just shy of 1900 files and 400K lines. The corresponding reference solution δ will usually edit a single function within a file, changing ∼15 lines, and has a single fail to pass test to verify the correctness of the change along with 51 pass to pass tests to check whether existing behavior is preserved.

## Human Label

For each answer, label:

- Decision: pass/fail
- Task fulfillment: pass/fail
- Grounding: pass/fail/not_applicable
- Overall: pass/fail
- Preferred answer: A/B/tie
- Note: concrete reason
