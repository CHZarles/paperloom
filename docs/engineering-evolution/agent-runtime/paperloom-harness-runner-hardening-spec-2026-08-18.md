# PaperLoom Harness Runner Hardening Spec

Date: 2026-08-18
Status: Implemented and tested; numeric limits remain provisional
Decision: Keep the Python Harness, OpenAI Agents SDK Runner, and `_continue_research_turn` name. Hide that internal
tool from MiniMax, allow only adapter-generated calls to execute it, bound all Provider-format recovery with one
request-local budget, and add an SDK Runner turn limit.

## 1. Problem

The Harness requests `tool_choice="required"` and keeps that setting between turns. Some Provider responses still
contain plain text without a function call, or a function call whose arguments are malformed. The current
compatibility path converts both cases into the model-visible `_continue_research_turn` tool. That can keep the SDK
Runner alive, but the recovery path has no independent budget and the model can also select the internal tool itself.

The 2026-08-14 production Run `run_8d1496878502420c8cbccd5b4c7270a0` reached:

| Metric | Observed |
| --- | ---: |
| Harness duration | 343.3 s |
| Model calls | 28 |
| Cumulative Tokens | 632,527 |
| Discarded plain-text responses | 6 |
| Time spent on discarded responses | 170.3 s |
| Model-selected `_continue_research_turn` calls | 4 |
| Submission attempts | 5 |

This proves that recovery is insufficiently bounded. It does not prove that all recovery should be removed.

Across 33 production traces, 24 contained at least one plain-text nudge. Those traces contained 39 plain-text
responses and 675.2 seconds of discarded model time. Making the first plain-text response fatal could therefore turn
a common recoverable condition into a product failure.

## 2. Architecture Decision

The existing coarse separation remains:

```text
OpenAI Agents SDK Runner   owns the model/tool loop
agents/model.py            adapts and observes Provider responses
agents/tools.py            executes PaperLoom tools
research_contract.py       owns business protocol and answer validation
ResearchRunContext         owns request-local state and observations
```

The hardening must preserve that structure. It must not add Provider-neutral message types, an event queue, a Tool
dispatcher, a custom Session, or `loop.py`.

Provider response validation and recovery policy are separate decisions:

```text
detect and classify an anomaly
  -> apply the approved bounded policy for that anomaly
  -> either resume through Runner or terminate explicitly
```

No invalid response is accepted as a final answer.

Runner and MiniMax intentionally receive different tool projections:

```text
Runner registers       PaperLoom tools + submit_* + _continue_research_turn
MiniMax receives       PaperLoom tools + submit_*
```

`_continue_research_turn` remains an SDK execution bridge, not a model capability. Its existing name is retained
because renaming it does not fix the defect and would only expand the code, test, and documentation diff.

## 3. Failure Taxonomy

The implementation must not collapse the following cases into one generic retry rule.

| Class | Meaning | Current behavior | Target behavior |
| --- | --- | --- | --- |
| Plain text without a function call | Provider returned text output in the wrong protocol | Convert to `_continue_research_turn` | Generate a registered internal correction only while the shared experimental budget remains |
| Malformed or truncated arguments | A function call exists, but its arguments are not a JSON object | Rename it to `_continue_research_turn` | Sanitize it into a registered internal correction only while the same budget remains |
| Model-selected `_continue_research_turn` | The model selected an internal compatibility tool | Execute it like a normal tool | Reject it because its call ID was not registered by the adapter |
| Rejected answer submission | A legal submission failed deterministic business validation | Return the rejection to the model | Preserve as normal Agent correction, subject to overall Run limits |

A rejected submission is not a Provider protocol violation. It must not consume a Provider-format recovery budget.

### 3.1 Plain-Text Semantics

The Provider can still produce plain text despite the required tool choice. The Harness restriction is narrower:

```text
model produces plain text                 possible
Harness publishes that text as final      forbidden
Harness requests a bounded resubmission   policy-controlled
```

PaperLoom uses `submit_direct_answer`, `submit_catalog_answer`, and `submit_research_answer` as final-answer
acceptance boundaries. A plain-text response has not selected an Answer Contract and has not supplied the structured
fields required to validate language, outcome, abstention, catalog provenance, or source-bound citations.

| Submission | What it proves before publication |
| --- | --- |
| `submit_direct_answer` | A permitted direct kind and language were selected; clarification text has valid shape |
| `submit_catalog_answer` | The count or list refers to a current `paper_result_ref` and permitted metadata fields |
| `submit_research_answer` | Outcome and language are explicit, and every citation resolves to evidence disclosed in this Run |

Plain text is therefore a protocol-format deviation, not proof that its content is wrong and not automatically a
terminal system failure. Under the experimental policy, the Harness may ask the model to resubmit through one of the
three submission tools within a finite budget. It must never infer missing fields or publish the text without
deterministic validation.

## 4. Invariants

The final implementation must satisfy all of these rules:

1. Plain text and malformed arguments are never published as accepted answers.
2. A valid business submission rejection remains model-visible and correctable.
3. Plain text and malformed arguments share one explicit finite Provider-format recovery budget.
4. `_continue_research_turn` is absent from Provider-visible tool definitions and only adapter-registered call IDs
   can execute it.
5. Every completed Provider inference response handled by the Harness records latency and usage exactly once,
   including responses later discarded.
6. Deadline, cancellation, Redis, Session, citation, and result-contract behavior remain unchanged.

### 4.1 Required State Transitions

| Current event | Condition | Next action |
| --- | --- | --- |
| Provider response | Valid function calls | Return them to Runner unchanged |
| Provider response | Recoverable format deviation and budget remains | Record it and issue one bounded correction |
| Provider response | Non-recoverable deviation or budget exhausted | End as `FAILED_TECHNICAL` with `PROVIDER_TOOL_PROTOCOL_VIOLATION` |
| Submission result | Deterministic business rejection | Return the rejection to the model without consuming Provider recovery budget |
| Submission result | Accepted | End the Run and publish the normalized answer |
| Runner | Accepted turn limit exhausted | End through the existing limited-Run path with `RUN_MODEL_CALL_LIMIT_EXCEEDED` |

These are policy states inside the existing request context, not a requirement for a new state-machine class.

## 5. Experimental Limits And Calibration

There are currently no product users, so the first implementation may use provisional limits and run a real-model
regression before acceptance. The initial values are:

```python
MAX_PROVIDER_PROTOCOL_REPAIRS = 2
MAX_AGENT_TURNS = 16
```

`MAX_PROVIDER_PROTOCOL_REPAIRS` is one shared per-Run counter. Plain text and malformed arguments both consume it,
which prevents alternating failure types from bypassing the bound. A deterministic business submission rejection
does not consume it.

The budget counts anomalous Provider responses, not individual output items. A response containing multiple malformed
function calls consumes one unit; valid sibling calls remain unchanged, and every sanitized internal call ID is
registered before Runner receives the response.

The value `2` is an experimental starting point, not a conclusion derived from the aggregate average. Calibration
must classify the available traces and regression Runs and record:

| Measurement | Purpose |
| --- | --- |
| Count by failure class | Avoid treating unrelated failures as one condition |
| Consecutive-count distribution | Determine whether one correction is usually enough |
| Next-turn recovery rate | Measure the benefit of correction |
| Final accepted-Run rate | Distinguish temporary recovery from useful completion |
| Added latency and Tokens | Measure the cost of recovery |
| Provider finish reason and response shape | Separate truncation from ordinary protocol deviation |

| Failure class | Recovery action | Per-Run budget | Terminal reason | Current evidence |
| --- | --- | ---: | --- | --- |
| Plain text | Adapter-generated internal correction | Shared experimental total: 2 | `PROVIDER_TOOL_PROTOCOL_VIOLATION` | Common enough that immediate failure is unsafe; final budget pending regression |
| Malformed/truncated arguments | Adapter-generated sanitized correction | Shared experimental total: 2 | `PROVIDER_TOOL_PROTOCOL_VIOLATION` | Existing compatibility need; final budget pending regression |
| Model-selected internal continuation | Reject unregistered call ID | 0 | `PROVIDER_TOOL_PROTOCOL_VIOLATION` | Already observed 4 times in the runaway Run |
| Rejected submission | Existing model-visible correction | Overall Run budget | Existing contract outcome | Existing accepted behavior |

Do not present the aggregate average of 39 responses across 24 affected traces as proof that `2` is optimal. The
consecutive-count distribution, successful-recovery rate, and PaperLoom-31 regression determine the accepted value.

### 5.1 MiniMax Tool-Choice Probe

On 2026-08-18, a focused live probe called the configured `MiniMax-M3` through PaperLoom's current
OpenAI-compatible endpoint. Each request supplied one valid tool and disabled Thinking to isolate tool selection.

| Prompt | API | Tool choice | HTTP | Result |
| --- | --- | --- | ---: | --- |
| Explicitly requested plain text and forbade tools | OpenAI-compatible | `required` | 200 | Plain text, no tool call |
| Explicitly requested plain text and forbade tools | OpenAI-compatible | named function | 200 | Plain text, no tool call |
| Explicitly requested the supplied tool | OpenAI-compatible | `required` and named function | 200 | One tool call |

The probe proves that MiniMax-M3 has Tool Use capability, but PaperLoom's current MiniMax endpoint did not treat
`tool_choice` as a hard server-side constraint when the message conflicted with it. PaperLoom must validate the
actual response and retain bounded recovery plus an explicit terminal path.

This was a protocol probe, not a quality or latency benchmark. It does not determine the recovery budget and does not
measure normal PaperLoom workloads.

### 5.2 Hidden Internal Tool Probe

A second live probe sent MiniMax a conversation history containing an assistant call and tool result for
`_continue_research_turn`, while omitting that tool from the current Provider-visible definitions. MiniMax accepted
the history with HTTP 200 and produced the requested visible `submit_answer` call.

This verifies the wire behavior required by the proposed split projection: Runner may execute the registered internal
tool without advertising it as a capability on the following MiniMax request. The implementation still validates
adapter-generated call IDs because the internal tool name remains visible in history and could be imitated.

## 6. Overall Run Protection

The accepted PaperLoom-31 baseline `20260812T020304Z-cd6e7648` used 1-13 model calls across 17 cases. The experimental
Runner limit is:

```python
MAX_AGENT_TURNS = 16
```

The three-turn headroom is evidence-based relative to that baseline, but it must be verified against Direct,
Catalog, Research, Follow-up, and Retry workloads before becoming the accepted value. SDK `MaxTurnsExceeded` maps to:

```text
RUN_MODEL_CALL_LIMIT_EXCEEDED
```

That exception follows the existing `RunLimitExceeded` path. Exhausted Provider-format recovery follows the existing
`ResearchSystemError` path. Both retain the stable Harness Run envelope; neither leaks raw Provider output to the
user.

A turn limit does not by itself bound Token cost because individual calls can grow. The trace analysis must compare
normal and runaway cumulative Token distributions. Add a total-Token limit to the existing `RunControl` only if the
turn limit and current deadline do not provide an acceptable cost bound; do not create a new limit subsystem.

## 7. Implementation Shape

Use the smallest change that implements the experimental policy:

1. `agents/model.py`: remove `_continue_research_turn` from the tools passed to the MiniMax parent model; classify
   Provider output; consume the shared repair budget; register each synthetic repair call ID; and record a redacted
   diagnostic.
2. `agents/tools.py`: keep the existing `_continue_research_turn` definition for Runner, but execute it only when the
   call ID is registered by the adapter. Remove the ID after use so replay cannot execute it again.
3. `agents/context.py`: add only `protocol_repair_count: int` and `synthetic_repair_call_ids: set[str]`.
4. `agents/runtime.py`: set `max_turns=16` and translate `MaxTurnsExceeded`.
5. Update focused tests and Harness documentation. Do not add a new abstraction for two fields and fixed constants.

The resulting runtime remains the SDK Runner with a guarded Provider boundary:

```text
runtime.py builds Agent, Context and Tools
  -> Runner requests a model step
  -> model.py receives and classifies the Provider response
       -> valid function call: return it to Runner
       -> recoverable format deviation within budget: return one bounded correction to Runner
       -> non-recoverable or exhausted deviation: terminate with an explicit reason
  -> Runner executes a PaperLoom tool
       -> ordinary tool result: continue
       -> rejected submission: continue normal business correction
       -> accepted submission: finish
```

There is no second `while` loop around Runner. `_continue_research_turn` remains only as the private, bounded bridge
required to express a correction through the SDK loop. The focused MiniMax probe in Section 5.2 verified that it may
be omitted from Provider-visible tool definitions.

When `get_response()` raises after receiving a response, SDK `ResearchRunHooks.on_llm_end()` does not run. That
failure path must therefore complete accounting from `response.usage` before raising. It must not double-account
responses that return normally to Runner.

No `RecoveryPolicy`, `ProtocolClassifier`, Provider interface, or dispatcher class is introduced unless the final
policy cannot be expressed clearly with one helper and request-local counters in the existing modules.

## 8. Verification

Focused checks must cover:

1. `_continue_research_turn` is registered in Runner but absent from the tools sent to MiniMax;
2. valid function calls pass through unchanged;
3. plain text and malformed arguments consume the shared budget and produce registered internal calls;
4. an unregistered or replayed `_continue_research_turn` call is rejected;
5. exhaustion of the shared recovery budget;
6. exactly-once Token and latency accounting for discarded responses;
7. valid `model -> tool -> model -> accepted submission` behavior;
8. rejected submission followed by a successful correction without consuming the protocol budget;
9. Runner turn-limit exhaustion without sending the next Provider request.

Run PaperLoom-31 after the focused checks. Compare completion quality, accepted-Run rate, model calls, cumulative
Tokens, and latency with the accepted baseline. This change may claim that runaway work is bounded only after those
checks; it must not claim a general performance improvement from an unpaired run.

### 8.1 Verification Result

Implementation checks on 2026-08-18:

| Check | Result |
| --- | --- |
| Focused Agent tests | `20` passed in `2.759 s` |
| Full Harness suite | `182` passed in `25.079 s`; `7` skipped |
| PaperLoom-31 Run | `20260818T020602Z-a1fef27c` |
| L3 deterministic hard pass | `16 / 17` |
| Comparable SDK cases | `16 / 16` completed with the expected contract/protocol/provenance checks |
| Protocol repairs | `5` total; five Runs used one repair each; none exhausted the budget |
| Model-call distribution, comparable cases | `1-11`; no Run reached the limit of `16` |
| Protocol/limit terminal failures | `0` |

The remaining PaperLoom-31 case, `research_llm_principles_01`, uses the separate legacy
`python_skill_guided_react_harness_v1`. Its first Provider request failed with an SSL EOF and was classified as
`PROVIDER_UNAVAILABLE`; it did not execute the changed SDK Harness. This transient external failure made the aggregate
Internal Beta Gate report `false`, so this Run is not a replacement for the accepted baseline.

For the 16 directly comparable SDK cases, the new Run used `92` model calls, `829,659` Tokens, and `508,087 ms`; the
accepted baseline used `80` calls, `677,836` Tokens, and `867,233 ms`. Provider sampling and network conditions were
not paired, so these figures calibrate the guards but do not establish a performance improvement or regression. They
do show that all observed deviations recovered within one attempt and that the provisional budget of `2` did not
reject a valid SDK case.

### 8.2 Runaway-Case Replay

The original production input from Run `run_8d1496878502420c8cbccd5b4c7270a0` was replayed on 2026-08-18 with the
same question, conversation history, paper scope, live Java corpus, and MiniMax Provider. The old Run completed only
after `28` model calls, `632,527` Tokens, and `343,311 ms`.

Two hardened replays terminated as `PROVIDER_TOOL_PROTOCOL_VIOLATION` instead of continuing indefinitely:

| Replay | Model calls | Tokens | Elapsed | Result |
| --- | ---: | ---: | ---: | --- |
| `run_7045dcbbdc13400392441bfef75261a6` | 6 | 54,787 | 26,781 ms | bounded technical failure |
| `run_eb2b02ee7baa47ecae749d3a2688ea98` | 8 | 68,745 | 46,514 ms | bounded technical failure |

The captured second replay produced valid research text without a Function Call on model calls 6, 7, and 8. The
first two responses consumed the shared repair budget; the third ended the Run. This confirms the runaway-cost fix,
but it also shows that the current correction bridge does not recover this specific user case within budget. The
exact-case acceptance problem remains open: increasing the budget would weaken the new bound without addressing why
MiniMax repeatedly regenerates plain text instead of submitting the existing draft through a Tool.

### 8.3 Existing-Draft Finalization

The exact replay showed that the compatibility bridge discarded a usable draft: it replaced the Provider text with
an internal call whose `content` was empty, then asked MiniMax to regenerate the answer. The smallest correction keeps
the existing Runner and validation boundary:

```text
Provider returns plain text
  -> adapter strips private thinking and preserves the remaining text in the registered internal call
  -> internal Tool labels it as an existing draft and returns compact cards for allowed source_quote_ref values
  -> next model step must submit, not regenerate, the draft
  -> existing Contract and citation Validator remains the only publication boundary
```

The Harness still does not publish plain text directly, infer citations, or increase the repair budget. Malformed
arguments retain their previous short repair message and do not enter draft finalization.

After implementation, the same production input completed in Run `run_f991f8cca8f947adb298815222730917`:

| Metric | Original runaway | Guard only | Draft finalization |
| --- | ---: | ---: | ---: |
| Result | completed | bounded technical failure | completed |
| Model calls | 28 | 6 / 8 | 13 |
| Total Tokens | 632,527 | 54,787 / 68,745 | 241,954 |
| Elapsed | 343,311 ms | 26,781 / 46,514 ms | 143,607 ms |
| Provider repairs | unbounded | exhausted at 2 | 1 |

The first finalization submission was correctly rejected: the draft had been written from search previews before an
exact `read_paper_content`, so there were no known source quotes to authorize. The Agent then read exact evidence and
eventually submitted seven valid citations. This means the historical case was not purely cosmetic formatting; the
main explanation was useful, but its first draft did not yet satisfy the product's evidence contract.

This is one live replay, not a stable latency claim. It demonstrates the intended behavior: retain useful work,
preserve deterministic validation, recover within the existing budget, and remain below the 16-turn stop.

The follow-up context, evidence, and diagnostic tightening is specified in
[PaperLoom Harness Draft Finalization Hardening Spec](paperloom-harness-draft-finalization-hardening-spec-2026-08-18.md).

## 9. Non-Goals

- Pi or TypeScript migration;
- a custom Agent Loop;
- Provider-neutral messages or an event queue;
- prompt, retrieval, citation, or answer-contract redesign;
- a new retry, limit, or telemetry framework.

## 10. Rejected Premature Policies

| Policy | Reason rejected |
| --- | --- |
| Unlimited synthetic continuation | Already produced a 28-call, 632,527-Token runaway Run |
| Immediate failure for every first deviation | Conflicts with the high observed frequency of recoverable plain-text responses |
| Treating the experimental budget of 2 as a proven production value | Consecutive and successful-recovery distributions are not yet available |
| Renaming `_continue_research_turn` | Cosmetic churn; visibility, authorization, and bounds fix the defect |
| Pi migration as the bug fix | Replaces the runtime without determining the PaperLoom recovery policy |

## 11. Rollback

Keep the implementation isolated to the existing Provider compatibility path, Runner configuration, and two
request-local fields. Reverting those changes must restore the prior behavior without a Java, Redis, data, or
deployment migration.
