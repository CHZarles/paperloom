# PaperLoom Harness Draft Finalization Hardening Spec

Date: 2026-08-18
Status: Implemented and verified
Scope: Reduce repeated Draft context, avoid premature Research submission without exact evidence, and remove duplicate
Draft content from transform diagnostics. Keep the current Python Harness, SDK Runner, repair budget, turn limit, and
Validator.

## 1. Background

The first Harness hardening change stopped unbounded Provider-format recovery and preserved a useful plain-text answer
as an unpublished Draft. The original runaway production input then completed successfully:

| Metric | Original runaway | Guard only | Draft finalization |
| --- | ---: | ---: | ---: |
| Result | completed | bounded technical failure | completed |
| Model calls | 28 | 6 / 8 | 13 |
| Total Tokens | 632,527 | 54,787 / 68,745 | 241,954 |
| Elapsed | 343,311 ms | 26,781 / 46,514 ms | 143,607 ms |
| Provider repairs | unbounded | exhausted at 2 | 1 |

Run `run_f991f8cca8f947adb298815222730917` proves that preserving the Draft can recover the user request without
removing the deterministic publication boundary. It also exposes three follow-up costs:

1. after the first `submit_*` call, later model requests can contain both the internal Draft and the latest full
   submission;
2. a Research Draft written from search previews may be submitted before an exact `read_paper_content` has produced
   an allowed `source_quote_ref`;
3. transform diagnostics can store the same long Draft in both source and target payloads, in addition to the raw
   Provider request and response records.

This follow-up is a context and evidence hardening change. It is not a new Agent architecture.

## 2. Decision

Retain the existing flow:

```text
Provider plain text
  -> registered internal continuation
  -> existing Draft finalization
  -> submit_direct_answer | submit_catalog_answer | submit_research_answer
  -> existing Validator
```

Make three local corrections:

```text
First real submission appears
  -> remove the superseded internal Draft call and its paired output from later model-input projections

No allowed Source Quote exists
  -> tell the model that a Research Draft requires exact reading before submission
  -> still allow Direct or Catalog submission

Transform diagnostic
  -> record Draft length and SHA-256, not a second full copy of the Draft
```

Do not add a Finalizer class, second Runner, Provider interface, message queue, citation parser, or semantic Judge.

## 3. Invariants

1. Plain text is never published directly.
2. The first finalization model step can see the latest preserved Draft; repeated plain-text responses do not
   accumulate older Drafts.
3. Once a real `submit_*` call exists, the superseded internal Draft and its paired Tool Output are absent from later
   Provider requests.
4. The latest submission and its Validator result remain visible so the model can correct it.
5. Corpus search/read history and authorized evidence remain visible.
6. Research submission without known Source Quotes remains rejected by the existing Validator.
7. Direct and Catalog submissions do not acquire a new citation requirement.
8. Only allowed `source_quote_ref` values may be submitted; the Harness does not infer a reference from `[1]` or a
   human-readable Sources line.
9. Malformed-argument recovery remains separate from plain-text Draft finalization.
10. The shared Provider repair budget is `3` after the production calibration recorded in the Runner hardening spec;
    the SDK Runner limit remains `16`.

## 4. Change 1: Remove A Superseded Draft

Extend the existing `_latest_final_submission_only` input filter in `agents/runtime.py`.

Before any submission, retain the latest internal call because it is the model-visible copy of the current Draft:

```text
_continue_research_turn(draft)
_continue_research_turn output(finalization instruction)
```

If another plain-text response supersedes it before a real submission, remove the older Draft call/output and retain
only the latest pair.

After any call whose name is in `SUBMISSION_TOOL_NAMES` appears, remove:

```text
function_call(name=_continue_research_turn, call_id=X)
function_call_output(call_id=X)
```

Continue applying the current rule that retains only the latest rejected final submission and its result. Do not
remove Corpus Tool calls or evidence outputs.

The deletion is safe because every `submit_*` call carries the complete candidate answer required for its Contract.
If the submission is rejected, its full arguments plus the Validator result are the correct correction context; the
older Draft is no longer authoritative.

No Session mutation is required. This is only the existing per-model-call input projection.

## 5. Change 2: Evidence-Aware Finalization Instruction

Keep `_continue_research_turn` hidden from MiniMax and executable only with an adapter-registered call ID.

The internal Tool already builds compact cards from known Source Quotes. Its response should use one of two modes:

### 5.1 Allowed Source Quotes Exist

```json
{
  "continue": true,
  "mode": "finalize_existing_draft",
  "message": "Submit the existing Draft. Use only the allowed Source Quotes, and remove unsupported claims.",
  "allowed_source_quotes": [
    {
      "source_quote_ref": "source_quote_...",
      "title": "Paper title",
      "section": "2.2 Standard Attention",
      "page": 4
    }
  ]
}
```

The instruction must require the model to:

- preserve supported Draft content rather than regenerate the answer;
- use only the supplied `source_quote_ref` values;
- put the appropriate reference in every factual Markdown block required by the current Contract;
- remove a claim if none of the allowed quotes supports it;
- issue exactly one `submit_*` call.

### 5.2 No Allowed Source Quote Exists

Do not infer the Answer Contract inside the compatibility Tool. Return a conditional mode:

```json
{
  "continue": true,
  "mode": "acquire_evidence_or_submit_non_research",
  "message": "A Research Draft cannot be submitted yet. Read exact evidence first. Direct or Catalog answers may submit without Source Quotes.",
  "allowed_source_quotes": []
}
```

This avoids a second Contract classifier and preserves Direct/Catalog behavior. The existing Research Validator remains
the enforcement mechanism if the model ignores the instruction.

Search previews are discovery data, not citable evidence. The Harness must not convert preview metadata into Source
Quotes or attach references automatically.

## 6. Change 3: Redact Duplicate Transform Content

The raw `model.response` remains the authoritative record of what the Provider returned. The next `model.request`
records what was actually replayed to the Provider. Therefore `model.output_transformed` does not need another full
copy of the Draft.

For plain-text finalization, record:

```json
{
  "reason_code": "PLAIN_TEXT_RESPONSE_REQUIRES_SUBMISSION",
  "source": {
    "type": "assistant_text",
    "draft_chars": 4382,
    "draft_sha256": "..."
  },
  "target": {
    "type": "function_call",
    "call_id": "...",
    "name": "_continue_research_turn",
    "arguments_redacted": true
  }
}
```

Malformed-argument diagnostics may retain their current small repair payload. Authentication headers and other
existing redactions remain unchanged.

## 7. Citation Correctness Boundary

This change retains deterministic rejection of unknown references and tells the model to remove unsupported claims.
It does not prove semantic entailment between every claim and quote.

The accepted boundary remains:

```text
known Source Quote identity
+ paper and location authorization
+ Markdown block citation presence
+ existing Contract validation
```

Do not add an online LLM entailment Judge in this change. It would add latency, cost, and another probabilistic
publication decision. A stronger semantic-claim Validator requires its own evidence and acceptance criteria.

## 8. Implementation Scope

| File | Minimal change |
| --- | --- |
| `harness_py/orchestration/agents/runtime.py` | Remove the superseded internal Draft call/output after a real submission exists |
| `harness_py/orchestration/agents/tools.py` | Return evidence-aware finalization mode and compact Source Quote cards |
| `harness_py/orchestration/agents/model.py` | Redact duplicated Draft content in transform diagnostics |
| Focused Agent tests | Cover projection, both finalization modes, and diagnostic redaction |

No Java, Redis, database, frontend, Corpus contract, or deployment configuration changes are required.

## 9. Verification

Focused checks:

1. the latest Draft call and output remain visible before the first `submit_*`;
2. repeated plain-text responses retain only the latest Draft call/output;
3. the Draft call and paired output disappear after the first `submit_*`;
4. the latest submission and rejection remain visible;
5. Corpus Tool calls and evidence remain visible;
6. known Source Quotes produce `finalize_existing_draft`;
7. an empty allowlist produces `acquire_evidence_or_submit_non_research`;
8. Direct/Catalog submission remains possible with an empty allowlist;
9. malformed-argument recovery is unchanged;
10. transform diagnostics contain Draft length/hash but not the Draft text;
11. the full Harness suite passes.

Live acceptance replays the original production input and requires:

- terminal status is not caused by an unbounded compatibility loop;
- model calls do not exceed `16`;
- Provider repairs do not exceed `2`;
- a completed Research answer contains only known Source Quotes;
- Provider requests after the first submission no longer contain the superseded Draft call;
- Token and latency results are recorded as descriptive measurements, not claimed as a stable performance gain from
  one stochastic Run.

### 9.1 Verification Result

Implementation checks on 2026-08-18:

| Check | Result |
| --- | --- |
| Focused Agent tests | `24` passed in `2.073 s` |
| Full Harness suite | `186` passed in `10.299 s`; `7` skipped |
| Final exact-case Run | `run_90deb39e955d490baf23f0d2a8e58531` |
| Result | `COMPLETED`, `RESEARCH`, three known Source Quotes |
| Provider repairs | `2`, equal to but not beyond the configured budget |
| Model calls | `14`, below the Runner limit of `16` |
| Total Tokens | `186,776` |
| Elapsed | `92,579 ms` |

Request-trace inspection proved the projection rule. Model request 7 contained the active Draft required for the
first finalization step; request 8 followed a real submission and contained no internal Draft. A later plain-text
response created one new active Draft in request 11; request 12 followed its submission and removed it again. No
Provider request contained multiple active Drafts after the final adjustment.

The first live replay of this follow-up reached the 16-turn limit. Its trace exposed two consecutive plain-text Drafts
before the first real submission, so both were still present in the next request. The implementation was tightened to
retain only the latest active Draft even before submission. The final replay then completed in 14 calls. This is the
evidence for the additional latest-Draft projection rule, not a reason to raise either execution limit.

Compared with the earlier successful Draft-finalization Run (`241,954` Tokens, `143,607 ms`), the final replay used
22.8% fewer Tokens and 35.5% less elapsed time. These are descriptive single-Run measurements; Provider sampling and
network conditions were not paired, so they are not a stable performance claim.

## 10. Non-Goals

- direct publication of Provider plain text;
- deterministic mapping from `[1]` to `source_quote_ref`;
- automatic attachment of all known evidence to a Draft;
- an online semantic-entailment Judge;
- another Agent Loop or dedicated formatting Provider;
- further repair or turn-limit changes beyond the recorded production calibration;
- prompt, retrieval, citation syntax, or answer-contract redesign.

## 11. Rollback

The change is isolated to model-input projection, the existing internal Tool response, and transform diagnostics.
Reverting those edits restores the current Draft finalization behavior without a data, transport, or deployment
migration.

## 12. Optimization Record

```text
Background
The Provider sometimes generated a useful final answer as plain text instead of a submission Tool Call. Discarding
that answer caused repeated full regeneration and one 28-call, 632,527-Token runaway Run.

First hardening
Bounded Provider repair, hid the internal Tool, authorized synthetic calls, and limited Runner turns. This stopped
runaway cost but made the exact case fail after the repair budget was exhausted.

Draft finalization
Preserved the plain-text answer as an unpublished Draft and asked the next model step to submit it through the normal
Validator. The exact case completed in 13 calls and 241,954 Tokens, but exposed duplicated Draft context and a
premature Research submission without exact Source Quotes.

This follow-up
Delete the superseded Draft after the first submission, distinguish evidence-ready finalization from the empty-
allowlist case, and avoid duplicate Draft content in transform diagnostics. Correctness remains owned by the existing
Validator; performance claims wait for measured replays.

Implementation result
The first replay exposed a second duplication path before submission, where repeated plain-text responses accumulated
multiple Draft calls. The projection was tightened to retain only the latest active Draft. The final exact-case replay
completed in 14 calls, 186,776 Tokens, and 92,579 ms; all 186 Harness tests passed. These measurements demonstrate the
mechanism but remain single-Run evidence.

Production follow-up
The question `DDoS攻击呢` produced a third recoverable plain-text Draft after correctly converting three citations,
but the provisional two-repair budget rejected it before submission. The budget was calibrated to three while the
16-turn bound remained unchanged. Live API probes showed that MiniMax accepted `json_object` and `json_schema`
response formats but did not enforce either under a conflicting prompt. The finish and finalization instructions were
therefore tightened to forbid assistant Markdown and place the corrected Draft in the submission tool's `markdown`
argument; response-format hints were not added as a false correctness boundary.
```
