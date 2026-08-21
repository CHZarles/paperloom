# Formula Citation Rendering Compatibility Fix Proposal

Date: 2026-08-21
Status: Implemented and deployed
Production Run: `run_a3fdfde318d34f8389d854ea46385edc`

## Problem

The production answer rendered as raw headings, prose, and LaTeX in the Chat page. The visible failure was in the
frontend, but the trigger was malformed Markdown crossing the Harness/frontend boundary:

```markdown
$$
formula
$$ [1]
```

The Harness replaced an inline Source Quote marker after the closing display-math delimiter with `[1]`. KaTeX
requires the closing `$$` delimiter to occupy its own line. It therefore treated later headings, prose, and formulas
as part of one unterminated math block.

Replaying the exact production Markdown through the installed `markdown-it` and KaTeX plugin reproduced the failure:
the six-formula answer produced only two `.katex-display` blocks, and those blocks contained misparsed citation/text
content rather than the intended equations; the remaining content produced KaTeX errors or raw text.

## Decision

Fix both sides of the existing Markdown contract:

1. The Harness emits valid Markdown for new answers.
2. The frontend normalizes the same known-invalid legacy shape before rendering stored answers.

Do not add a new renderer, Markdown AST layer, dependency, database migration, or general-purpose LaTeX repair
system. This proposal handles only a closing `$$` followed on the same line by one or more numeric citations.

The canonical output is:

```markdown
$$
formula
$$

[1]
```

## Changes

### 1. Prevent New Invalid Answers

In `harness_py/orchestration/run_output.py`, keep citation numbering in `_render_citations`, then move trailing numeric
citations off a standalone `$$` delimiter line. This is the shared output boundary used before answers are persisted
and returned to Java.

Add one Python regression test using the production shape. It must assert that output contains `$$\n\n[1]` and does
not contain `$$ [1]`.

### 2. Render Existing Stored Answers

Add a small exported normalizer to the existing `frontend/src/utils/research-markdown.ts`. Run it before citation-link
injection and Markdown rendering in these existing paths:

- completed Chat messages in `chat-message.vue`;
- answer revisions in `chat-message.vue`;
- streaming Markdown in `streaming-markdown.vue`.

Chat History already reuses `ChatMessage`, so it needs no separate implementation. The normalizer must leave inline
math, valid display math, code fences, and ordinary citation text unchanged.

No database migration is needed. Once the frontend is deployed, the already persisted production answer renders
correctly; new answers are also corrected at the Harness boundary.

## Verification

1. Python regression: the Harness never emits `$$ [n]` for the captured shape.
2. Frontend regression: render a legacy fixture containing two formulas and citations; assert two KaTeX display blocks,
   no `.katex-error`, and an intact following heading.
3. Product replay: reopen the existing conversation and confirm formulas render, headings remain headings, and citation
   chips are still clickable.
4. Run the Harness suite and focused frontend test. A new model call is unnecessary because this defect is
   deterministic and the captured answer is sufficient to reproduce it.

## Acceptance Criteria

- The existing `你把公式写出来` answer no longer displays raw `##`, `$$`, or LaTeX caused by this delimiter failure.
- Every display formula is rendered by KaTeX.
- Numeric citations remain visible and clickable.
- Future Harness output contains no closing delimiter in the form `$$ [n]`.
- The answer-scope issue remains a separate Prompt change; this rendering fix must not rewrite or shorten content.
