# Collaboration Infrastructure

Status: active.

This document defines reusable collaboration infrastructure for repository work.
Infrastructure supports planning, review, verification, and handoff. It must not
add product behavior or project-specific tooling unless the active scope
explicitly calls for it.

## Infrastructure Goals

- Keep work tracked to an issue or direct user request.
- Catch common mistakes before merge.
- Run repeatable automated checks on pull requests when configured.
- Keep optional agent/review tools advisory.
- Protect secrets, branch protections, and unrelated user work.
- Make verification evidence visible in PRs and handoffs.

## Work Ledger

Use GitHub issues as the official work ledger when available. A direct user
request may also define active scope.

Each tracked unit should make clear:

- Scope.
- Non-goals.
- Acceptance checklist.
- Verification plan.
- Owner or acting agent.
- Current status.

If GitHub is unavailable, use a local ledger and record the missing setup as an
external-permission gap. Do not use missing GitHub setup as a reason to expand
scope or stop safe local progress.

## Branch And PR Flow

Default flow:

```text
issue or request -> branch -> focused commits -> PR -> verification -> merge or handoff
```

Branch and PR rules:

- One branch is bound to one issue by default.
- One PR solves one clear target.
- PRs should avoid unrelated cleanup, configuration churn, or behavior changes.
- Branch names should be short, descriptive, and tied to the work item when
  practical.
- Merge behavior follows repository permissions and branch protection.
- After merge, start the next scope from the protected base branch.

## PR Content Standard

Each PR should include:

- Scope summary.
- Linked issue or stated direct request.
- Change summary.
- Verification evidence.
- Skipped checks with reasons.
- User-visible impact, when relevant.
- Known risks, assumptions, or follow-ups.

The PR description should help a reviewer decide whether the diff matches the
scope. It should not introduce new scope that is absent from the issue or direct
request.

## Review Standard

Review should prioritize correctness, scope control, maintainability, safety,
and missing verification.

Review checklist:

- Matches the issue or direct request.
- Stays within explicit non-goals.
- Keeps the diff cohesive and reviewable.
- Avoids unrelated refactors or configuration changes.
- Preserves existing ownership boundaries.
- Handles privacy, security, data-loss, and user-visible risks.
- Includes appropriate verification, or explains skipped verification.
- Does not store secrets or credentials in the repository.
- Notes migration, compatibility, or rollback concerns when relevant.

Review findings should be specific, actionable, and tied to the diff. Questions
are useful when they clarify risk or scope; they should not become approval
gates for routine work.

## Automated Review

Automated review is optional and advisory.

Allowed:

- Read the PR diff.
- Read related repository sources and issue scope.
- Produce review findings.
- Comment on the PR.

Forbidden:

- Push commits.
- Modify code.
- Merge PRs.
- Close issues.
- Act as merge authority.
- Expand scope.
- Bypass branch protection or verification requirements.

If automated review uses privileged credentials or events, it must not execute
untrusted PR code. It should review the diff through trusted base-branch code or
trusted platform APIs.

If review automation is misconfigured or missing secrets, it should report
configuration status rather than failing unrelated product verification.

## CI And Local Checks

CI is verification evidence, not merge authority.

When CI is configured, it should:

- Run checks relevant to the repository.
- Run on PRs targeting the protected base branch.
- Run on pushes to protected branches.
- Support manual dispatch when useful.
- Avoid exposing secrets to untrusted code.

Local checks and pre-commit hooks are optional unless the repository has adopted
them. Add or change check configuration only in a scoped infrastructure issue or
direct user request.

Failing checks should be fixed, or the skip should be recorded with a clear
reason before handoff.

## Configuration Review

Infrastructure configuration is implementation work. It needs a clear issue or
direct user request before files are added or changed.

For each new configuration file, record:

- What the config controls.
- Which command, workflow, or tool uses it.
- Expected local command, if any.
- Expected remote trigger, if applicable.
- Whether it affects product behavior, verification, collaboration, or agents.
- Secret handling and permission assumptions.
- Confirmation that it does not expand scope.

## Process Tools

Process tools are optional and advisory. Repository sources, issues, PRs, and
verification results remain authoritative.

These tools must not:

- Replace issues when an issue is needed.
- Expand scope beyond the active issue or request.
- Override repository sources.
- Store secrets or credentials in the repository.
- Become required for routine progress unless explicitly adopted.

## Coordination Workflow

Normal sequence:

1. Repository sources define target behavior.
2. An issue or direct user request defines active scope.
3. A context check runs when scope touches unfamiliar areas, public interfaces,
   stored data, user-visible behavior, privacy, or security.
4. A compact plan is written for non-trivial implementation.
5. Implementation proceeds in focused commits.
6. Relevant local checks run when configured.
7. CI repeats relevant checks on the PR when configured.
8. Optional review automation comments after a PR exists.
9. Changed files, verification, skipped checks, and unresolved assumptions are
   recorded in the issue, PR, or handoff.

## Infrastructure Acceptance Checklist

- [ ] Work is tracked by an issue or direct request.
- [ ] Branch and PR flow is explicit.
- [ ] PR content standard is explicit.
- [ ] Review standard is explicit.
- [ ] Automated review is advisory only.
- [ ] CI is verification evidence, not merge authority.
- [ ] Privileged automation does not execute untrusted PR code.
- [ ] Local checks and pre-commit hooks are optional until adopted.
- [ ] Infrastructure config files require clear scope before adoption.
- [ ] Secrets and credentials are not stored in the repository.
- [ ] Process tools remain advisory unless explicitly adopted.
