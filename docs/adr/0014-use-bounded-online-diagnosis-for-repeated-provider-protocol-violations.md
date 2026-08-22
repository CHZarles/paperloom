# Use bounded online diagnosis for repeated Provider protocol violations

MiniMax can occasionally return assistant text even when `tool_choice="required"`. The Draft may be
useful, but publishing it would bypass the Harness submission contract and retrying the same fixed
instruction repeatedly does not adapt to the Provider's misunderstanding.

**Decision**

- The first consecutive plain-text violation uses the existing deterministic repair.
- The second consecutive violation makes one diagnostic call to the same MiniMax Provider without
  Tools, then appends its `repair_hint` to the deterministic instruction.
- The diagnosis is behavior-level advice only. It cannot change Tool schemas, contracts, validators,
  evidence rules, or publish a Draft.
- The deterministic Tool-result `message` remains authoritative. `repair_hint` is formatting advice
  only and must defer tool selection, arguments, evidence, and validation requirements to `message`.
- The next primary response still goes through the normal `submit_*` Tool and Validator path. If it
  is plain text again or contains malformed Tool arguments, the Run fails with
  `PROVIDER_TOOL_PROTOCOL_VIOLATION`.
- Diagnosis is limited to this one protocol violation. Redis, database, authorization, timeout,
  Provider 5xx, Validator, and malformed-argument failures are not diagnosed.
- Each Run gets at most one diagnostic call. Its wall-clock timeout is the smaller of 10 seconds and
  the remaining Run deadline; invalid output or Provider failure uses one deterministic fallback.
- Diagnostic inputs and outputs are bounded. Tokens count toward quota settlement but the call does
  not consume an Agent Turn.
- Traces record bounded results plus input hashes and lengths, never a second full Draft copy or
  chain-of-thought.

**Considered Options**

- Publish the plain Draft directly. Rejected because it bypasses the submission contract and
  Validator.
- Keep deterministic retries only. Rejected because repeated identical instructions did not recover
  every otherwise useful Draft.
- Diagnose every failure or every first violation. Rejected because it adds latency, cost, and
  authority where deterministic handling already exists.

**Consequences**

- Valid Tool Call paths add no diagnostic request or latency.
- Repeated plain text adds at most one diagnostic request and one repair opportunity.
- Failure Runs must preserve diagnostic count, usage, latency, and outcome for operations and quota
  auditing.
- Provider-specific diagnostic transport remains inside the MiniMax adapter.
