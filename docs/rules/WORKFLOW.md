# Repository Workflow

Status: active.

This workflow keeps repository work moving without routine human approval gates.
Agents should proceed when the repository contains enough context to make a
coherent, scoped change. Human input is required only when a contradiction,
missing permission, or external blocker prevents safe progress.

## Authority

Source of truth order for this repository:

1. Product or requirement sources control target behavior.
2. Repository context controls domain language.
3. This file controls development workflow.
4. Infrastructure rules control collaboration and review tooling.
5. Issues or direct user requests define active implementation scope.

Workflow and infrastructure rules must not introduce behavior that is absent
from the active requirements.

## Hard Rule

Default to progress.

Do not wait for a human to approve a spec, issue, implementation plan, PR, or
completed change when the next action is clear from repository sources, an
issue, or the current user request.

Ask the human only when local context cannot resolve a material blocker:

- Repository sources require incompatible behavior.
- Acceptance criteria conflict with active requirements.
- The requested scope violates an explicit non-goal.
- Required external permissions, credentials, or system details are missing.
- Multiple implementation paths have materially different privacy, security,
  data-loss, or user-visible consequences and the repository gives no
  tie-breaker.
- Continuing would delete or overwrite unrelated user work.
- Continuing would require a public or destructive external action not already
  authorized.

When uncertainty is minor, choose the conservative path, record the assumption
in the issue, PR, or handoff, and continue.

## Scope Control

Every implementation change needs a controlling issue or explicit user request.
A coherent unit of work may use one active issue when its checklist remains
reviewable. Split the work when the diff or acceptance checklist becomes too
large to review well.

Each implementation issue should include:

- Scope.
- Explicit non-goals.
- Acceptance checklist.
- Verification plan.
- Implementation plan or note for non-trivial work.

If an issue lacks non-goals, infer the narrowest useful scope from the request
and repository sources. If work reveals a new need, update the issue or create a
follow-up. Do not silently implement unrelated scope.

## GitHub And Branches

Use GitHub issues as the official work ledger when an authenticated GitHub
repository is available. If GitHub setup is unavailable, continue with a local
ledger and record the missing setup as an external-permission gap.

Default cadence:

```text
clear scope -> one issue -> one branch -> focused commits -> one PR
-> verification -> merge or handoff -> next scope
```

Rules:

- One branch is bound to one issue by default.
- One PR solves one clear target.
- Do not mix unrelated collaboration tooling, configuration, and product
  behavior in one PR.
- After a PR merges, start the next issue from `main`.
- Commits authored by the agent use the `Codex Agent` git author.
- PR author is determined by the GitHub account that creates the PR.
- Merge behavior follows repository permissions and branch protection.

## PR Standards

Keep PRs easy to review. Split a PR before it requires reviewers to understand
multiple unrelated decisions at once.

Each PR should make clear:

- What scope it implements.
- Which issue or direct request controls the work.
- What changed.
- How it was verified.
- Which checks were skipped and why.
- Remaining assumptions or risks.

CI passing is verification evidence. It is not permission to expand scope,
merge without required review, or bypass branch protection.

## Implementation Planning

The implementation plan is a working artifact, not an approval gate. For every
non-trivial implementation issue, write a compact plan before changing code.

The plan should show:

- Intended files or areas.
- Expected behavior flow.
- Verification flow.
- Expected changed files.
- Acceptance checklist mapping.
- Explicit non-goals.

Tiny fixes may use a short text note instead of a plan. Examples include typos,
issue or PR metadata updates, and mechanical validation fixes inside an already
scoped change.

If implementation materially differs from the plan after work starts, record
deviation notes in the issue, PR, or handoff.

## Context Check

A context check is required before implementation when work touches unfamiliar
areas, crosses ownership boundaries, changes public interfaces, changes stored
data, changes user-visible behavior, or affects privacy/security. The context
check may use repository search, source reading, or a project knowledge tool.
Repository files remain authoritative.

If the context check changes risk, scope, or implementation direction,
summarize it in the issue, PR, or handoff.

## Review Agent

Review Agent, when configured, runs only after a PR exists and has a reviewable
diff. It is advisory only. It may produce findings and questions, but it cannot
merge, close issues, expand scope, or override repository sources.

If automated Review Agent is unavailable, manual agent review may be used.
Human review remains optional unless branch protection or a blocking
contradiction requires it.

## Verification And Handoff

Run the checks that are configured for the repository and relevant to the
changed surface area. When CI is configured, inspect CI before claiming remote
verification passed.

For every issue or PR, record:

- Changed files.
- Planned implementation versus actual diff when a plan existed.
- Deviation notes when the actual flow differs materially from the plan.
- Commands or checks run.
- Command or check results.
- Skipped verification and why.
- Remaining assumptions or risks.

If user-facing behavior changes, perform a smoke check when practical.

## Workflow Acceptance Checklist

- [ ] Routine human approval is not required before implementation.
- [ ] Routine human approval is not required after implementation.
- [ ] Human input is required only for blocking contradictions, missing external
      permissions, destructive actions, or public actions not already authorized.
- [ ] Issue or explicit user request is required for each implementation change.
- [ ] Non-trivial implementation issues require a compact plan before work.
- [ ] One branch is bound to one issue by default.
- [ ] One PR solves one clear target.
- [ ] Agent review is advisory.
- [ ] CI green is verification evidence, not permission to expand scope.
- [ ] Infrastructure configuration requires clear scope before adoption.
