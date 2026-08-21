# Formula Citation Rendering Compatibility Spec

**Date:** 2026-08-21
**Status:** Implemented and deployed
**Incident:** Production Run `run_a3fdfde318d34f8389d854ea46385edc`
**Proposal:** `docs/engineering-evolution/frontend/formula-citation-rendering-fix-proposal-2026-08-21.md`

## Problem

The Chat page displayed raw headings, prose, and LaTeX after a research answer placed a citation on the closing
display-math delimiter line:

```markdown
$$
formula
$$ [1]
```

`markdown-it-katex` requires the closing `$$` to be alone on its line. The parser therefore consumed later Markdown
as part of an unterminated math block. The defect is a boundary mismatch:

1. MiniMax submitted `$$ [[source_quote_...]]`.
2. the Harness converted the marker to `$$ [1]` and persisted it;
3. the frontend passed that invalid Markdown to KaTeX.

The captured production Markdown reproduces the defect deterministically. A new model call is not needed to test the
fix.

## Required Behavior

The canonical representation of a cited display formula is:

```markdown
$$
formula
$$

[1]
```

The normalization must be:

- narrow: only a standalone `$$` delimiter followed on the same line by one or more numeric citations;
- idempotent: normalizing an already normalized answer changes nothing;
- content preserving: formula text, citation numbers, headings, and prose remain unchanged.

The frontend legacy normalizer must additionally leave the same text inside fenced code unchanged.

Inline math, same-line display math, arbitrary malformed LaTeX, and answer-length control are outside this spec.

## Implementation

### Harness Output Boundary

**File:** `harness_py/orchestration/run_output.py`

Keep the change inside `_render_citations`, after Source Quote markers have been replaced with numeric citations and
before the `Sources` section is appended.

Transform:

```text
<closing $$><spaces><one or more [n] citations>
```

into:

```text
<closing $$>
<blank line>
<the same citations>
```

This prevents new malformed answers from being returned to Java or persisted. Do not change citation numbering,
Source generation, validation, or the answer schema.

### Frontend Legacy Compatibility

**Shared function:** `frontend/src/utils/research-markdown.ts`

Export exactly one new helper:

```ts
normalizeLegacyDisplayMathCitations(content: string): string
```

It applies the canonical rewrite before Markdown parsing. It must ignore fenced code blocks and return the original
string when no matching line exists. No new dependency or renderer is allowed.

Apply the helper at the existing render entry points:

| File | Path | Required order |
| --- | --- | --- |
| `chat-message.vue` | completed assistant message | normalize, then create citation chips, then render Markdown |
| `chat-message.vue` | answer revision | normalize, then render Markdown |
| `streaming-markdown.vue` | streaming assistant message | normalize, then call `markdown.render` |

`chat-history/index.vue` needs no change because it renders the shared `ChatMessage` component.

Do not mutate message objects or write normalized content back to the API. This is a render-time compatibility rule for
already persisted answers.

## Data Flow After the Fix

```text
MiniMax Draft
  -> Harness validates Source Quote markers
  -> Harness numbers citations
  -> Harness normalizes display-math citation lines
  -> Java persists valid Markdown
  -> frontend normalizes legacy content once more, idempotently
  -> citation chips are injected
  -> markdown-it + KaTeX render
```

## Tests

### Python

**File:** `harness_py/tests/test_run_output.py`

One focused test must prove that the captured shape:

```markdown
$$
x = 1
$$ [[source_quote_1]]
```

becomes `$$\n\n[1]` after citation rendering and never contains `$$ [1]`.

Run:

```bash
.venv-harness/bin/python -m unittest harness_py.tests.test_run_output
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

### Frontend

**File:** `frontend/tests/research-markdown-preformatted.test.ts`

Extend the existing Markdown test with one legacy fixture containing:

- two display formulas ending in `$$ [n]`;
- a Markdown heading and normal paragraph after the first formula;
- one fenced code example containing `$$ [9]`.

Assertions:

- the normalized fixture has two standalone closing delimiters;
- rendering produces two `.katex-display` blocks;
- rendering produces no `.katex-error`;
- the following heading remains an `<h2>`;
- the fenced example remains byte-for-byte unchanged by the normalizer.

Run:

```bash
pnpm --dir frontend exec tsx tests/research-markdown-preformatted.test.ts
pnpm --dir frontend typecheck
```

## Acceptance Criteria

1. Reopening the existing `你把公式写出来` answer renders every formula instead of raw LaTeX.
2. The `量化映射` and `反量化与矩阵乘法` headings remain headings.
3. Citation chips remain visible and clickable.
4. New Harness answers do not contain a line matching `^\s*\$\$\s+\[\d+\]`.
5. Valid Markdown, inline math, and fenced examples are unchanged.
6. No API, database, schema, dependency, or model Prompt change is introduced.

## Rollout

1. Deploy the frontend first so already persisted malformed answers render correctly.
2. Deploy the four Redis Harness Workers so new answers are stored in canonical form.
3. Reopen the existing production conversation and verify formulas, headings, and citation clicks.
4. Do not migrate the database and do not rerun MiniMax solely for this deterministic rendering defect.

## Rollback

The frontend compatibility helper and Harness normalization are independent and stateless. Either can be reverted
without changing stored data. No rollback migration is required.

## Local Verification

Completed on 2026-08-21:

- Harness: `188` tests passed, `7` skipped.
- Frontend Markdown regression: passed.
- Frontend typecheck: passed.
- ESLint on the four touched frontend files: passed.

## Deployment Verification

Commit `208596c` was deployed to Wuyun on 2026-08-21:

- the production frontend build and bundle-budget checks passed;
- `https://paperloom.me/` returned HTTP `200` after the build;
- all four Redis Harness Workers restarted as `active` and reported `status: ready`;
- no Java backend or database restart was required.
