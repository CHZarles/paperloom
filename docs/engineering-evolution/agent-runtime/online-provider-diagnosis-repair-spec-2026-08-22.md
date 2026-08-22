# Online Provider Diagnosis Repair Spec

**Status:** Implemented and verified
**Date:** 2026-08-22

## Problem

MiniMax occasionally returns assistant text even when `tool_choice="required"`. The Harness already
converts the first violation into `_continue_research_turn`, but repeated violations currently spend
the fixed repair budget without learning why the previous instruction failed.

## Accepted Decision

The normative behavior and bounds are recorded in
[ADR 0014](../../adr/0014-use-bounded-online-diagnosis-for-repeated-provider-protocol-violations.md).

## Observability

- Eval events: `diagnosis.started`, `diagnosis.completed`, `diagnosis.failed`, `repair.applied`.
- Draft and prior-prompt inputs are recorded only as hashes and lengths.
- Diagnostics expose diagnostic call count, tokens, latency, and recovery outcome.
- Progress emits `repairing_response`; the current UI renders it as "Repairing answer format".

## Acceptance

- Repeated plain text can recover through exactly one diagnosis and a normal `submit_*` call.
- Diagnostic failure still gives the model one deterministic repair attempt.
- Plain text or malformed Tool arguments after diagnosis fail immediately.
- A valid Tool Call never invokes diagnosis or adds latency.

## Implementation Record

### Background and root cause

MiniMax can occasionally return a useful Markdown Draft even with `tool_choice="required"`. The
answer content is not necessarily bad; the response envelope violates the Harness contract because
no `submit_*` Tool Call exists. Repeating a fixed instruction three times was bounded, but it could
not adapt when the Provider misunderstood that instruction.

### Change

The first violation still uses the cheap deterministic nudge. A second consecutive violation now
gets one bounded behavior-level diagnosis from the same Provider. Its hint is appended to, not
substituted for, the authoritative deterministic repair instruction. The next Tool Call still passes
through the existing Contract and Validator, so diagnosis cannot bypass publication rules.

The deterministic `message` also has explicit precedence over the diagnostic hint. The fallback no
longer selects a `submit_*` Tool, so it cannot contradict a Research instruction that must read
evidence before submission.

The normal path performs no diagnostic request. The exceptional path is capped at one diagnostic
request and one repaired primary response; another plain-text response fails immediately.

### Verification

- Deterministic replay: two plain responses, one diagnosis, then `submit_direct_answer` completed in
  four HTTP requests; diagnostic usage was included in total Run tokens.
- Failure injection: invalid JSON, HTTP 503, and timeout all used the fixed fallback hint without a
  recursive diagnosis.
- Negative path: plain text after diagnosis stopped with
  `PROVIDER_TOOL_PROTOCOL_VIOLATION`, made no additional request, and retained diagnostic metrics.
- Boundary injection: slow responses obeyed the hard Run-relative timeout, and a second consecutive
  plain response still reached diagnosis after the deterministic repair budget was exhausted.
- Review regression: limited, cancelled, and unexpected Failure Runs retained diagnostic metrics;
  empty Drafts retained the hint; malformed post-diagnosis calls failed; non-string fields fell back.
- Regression: 201 Harness tests passed, 7 skipped; frontend focused lint, typecheck, and production
  build passed.

### Interview summary

"I found that `tool_choice=required` was not a hard guarantee for an OpenAI-compatible Provider.
Instead of either publishing unvalidated text or retrying blindly, I kept the first deterministic
repair and added one bounded online diagnosis only after a repeated violation. The hint has no
authority: the repaired response must still call `submit_*` and pass the existing Validator. I also
counted diagnostic tokens in quota settlement, added hash-only input tracing, injected Provider
failures, and proved the normal Tool Call path adds zero model calls."
