# Research Process Display — Design

**Date:** 2026-07-24
**Scope:** Frontend only. No backend, no API, no schema changes.
**Target file:** `frontend/src/views/chat/modules/research-process-panel.vue`
**Status:** Approved by user (2026-07-24). Ready for plan.

## Problem

The current `ResearchProcessPanel` renders every research step as a flat vertical
timeline of bullet rows. Each row has the same shape regardless of what the agent
did, so a search, a location lookup, a passage read, and an internal LLM pass all
look like the same line. The raw detail text (`"5 locations selected · 5
evidence passages · pages 1, 2, 3, 5, 10"`, `"3098 ms · 3,125 tokens"`) is the
only differentiator, which makes the panel feel abstract and hard to scan.

The backend already classifies events by tool/type and supplies structured inputs
and outputs — the front end just isn't using that structure visually.

## Goal

Make each research phase visually distinct so a user can read the panel top-to-
bottom and immediately understand what the agent did, in what order, and what it
found at each step — without parsing metric strings.

## Non-Goals

- No changes to backend contracts, store, router, or websocket handling.
- No new components, no new utils, no global style tokens.
- No extraction of a reusable `<PhaseCard>` until a second consumer appears.
- No new unit tests for this component (the panel has none today; adding one
  for this change would be inconsistent with the rest of the codebase).

## Architecture

Single-file change. Everything happens inside `research-process-panel.vue`:

1. **Phase classifier** — a pure function `phaseOf(event): PhaseView` that maps a
   `ResearchProgressEvent` (or `ResearchAuditStep`) into one of seven phases
   (`search`, `locate`, `read`, `cite`, `think`, `answer`, `error`). Returns the
   icon, headline, badge text, one-line summary, and the items preview.

2. **Renderer** — replaces the existing `eventTitle` / `eventDetail` /
   `eventItems` helpers and the three-branch template (`hasAuditTrail` →
   `events` → `legacyTools` → empty) with a single card loop that consumes
   `PhaseView[]`.

3. **Three branches preserved** — audit-trail-first, then live events, then
   legacy tools, then empty. The branch only changes which source array feeds
   the classifier.

4. **Audit ledger** (cited / read / candidate groups) — kept as-is structurally,
   re-skinned so each row uses the same card shape as the phase cards.

## Phase Model

Each event is classified into exactly one phase. The classifier lives in the
script setup as `phaseOf(event)` and returns a typed shape:

```ts
type Phase = 'search' | 'locate' | 'read' | 'cite' | 'think' | 'answer' | 'error';

interface PhaseView {
  key: string;
  phase: Phase;
  state: 'running' | 'completed' | 'failed';
  headline: string;       // e.g. "Searching papers", "Thinking · pass 3"
  badge: string;          // e.g. "1 paper", "5 passages", "346 ms"
  oneLiner: string;       // context line: query, page list, token count
  durationMs?: number;    // shown as muted suffix on the badge
  items: PhaseItem[];     // up to 10, with optional text/title for preview
}
```

### Phase rules

| Phase | Trigger | Headline | Badge | OneLiner |
|---|---|---|---|---|
| `search` | `tool in {search_paper_candidates}` | "Searching papers" / "Searched papers" | paper count | query (or paper title if exactly one) |
| `locate` | `tool in {find_reading_locations}` | "Locating sections" / "Located sections" | location count | query + `· Xms` if available |
| `read` | `tool in {read_locations}` | "Reading passages" / "Read passages" | passage count | "pages 1, 2, 3" |
| `cite` | `tool in {get_citation_edges}` | "Tracing citations" / "Traced citations" | edge count | — |
| `think` | `type in {model_call_started, model_call_completed}` | "Thinking · pass N" / "Thinking completed" | duration | tokens (only if > 0) |
| `answer` | `type in {answer_completed}` | "Answer prepared" | — | — |
| `error` | `type in {job_failed, job_cancelled}` | "Research failed" / "Research cancelled" | error type | error message |

Anything that does not match falls through to a neutral `research-progress`
card (preserves current behavior for unknown tools).

### Item previews

- `search` → `output.papers[]` → title + (page / section if present)
- `locate` → `output.locations[]` → section + page
- `read` → `output.evidence[]` → section + first 3 lines of quote
- `cite` → `output.edges[]` → short label
- others → empty

Max 10 items. Max 3 lines of quote text with ellipsis. Collapsed by default for
reads; expanded on click. Search/locate previews are always visible (short).

## Layout

```
┌───────────────────────────────────────────────────────────────┐
│  [icon] Reading passages                       [5 passages]   │
│         Reading passages · 5 evidence                         │
│         pages 1, 2, 3, 5, 10                                 │
│  ─────────────────────────────────────────────────────────────│
│  ▸ Abstract · p. 1                                           │
│    The dominant sequence transduction models…                 │
│  ▸ 3 Model Architecture · p. 2                               │
│    Most competitive neural sequence…                         │
│  ▸ 3.1 Encoder and Decoder Stacks · p. 3                     │
│    Encoder: The encoder is composed of a stack of N=6…        │
└───────────────────────────────────────────────────────────────┘
```

Each phase card has:

- **Icon** — left rail, 28px square with phase-specific background tint using
  `--color-research-soft-bg` for active, `--color-surface-alt` for completed.
- **Headline + state pill** — "Reading passages" with `running` / `done` /
  `failed` badge.
- **Count badge** — right-aligned, monospace count.
- **One-liner** — muted, below headline.
- **Item list** — collapsible, optional, only for phases with items.
- **Connector** — vertical line between cards (kept from current CSS), now
  spanning the icon column instead of a generic dot.

### Audit ledger re-skin

The cited / read / candidate groups stay. Each evidence row becomes the same
card shape with a `ledger` variant (no icon, count badge moved to title row).
Click-to-open-reference behavior preserved.

## State Mapping

| Source state | Card state |
|---|---|
| `tool_started`, `model_call_started` | `running` |
| `tool_completed`, `model_call_completed`, `answer_completed` | `completed` |
| `job_failed`, `status === 'failed'` | `failed` |

Color tokens (existing, no additions):

- `running` → `--color-warning`
- `completed` → `--color-success`
- `failed` → `--color-error`

## Edge Cases

- **No events + not running** → existing empty state, copy unchanged.
- **Running with no events yet** → single "Researching…" card with pulse
  animation on the icon.
- **Long evidence quote** → max 3 lines, ellipsis, click to expand.
- **Unknown tool/type** → falls through to neutral `research-progress` card.
- **Streaming → audit transition** — when `researchAuditTrail` arrives,
  replace the timeline; preserve the user's scroll position by anchoring to the
  last phase card (use `scrollIntoView({ block: 'nearest' })` on the last
  card's key change).
- **Same phase repeated** — consecutive `think` events collapse to one card
  showing the latest pass + a `· pass 3 of 5` hint. Implemented as a post-
  process step on `presentedPhases`; does not change backend semantics.

## Out of Scope (intentionally)

- Animations beyond the existing pulse on the running state.
- Sound or haptic feedback.
- New i18n keys (the panel currently uses hard-coded English; preserving that
  keeps the diff small and avoids a separate locale review).
- New global tokens or theme changes.

## Verification

Run in dev mode and walk through:

1. Open a chat answer that has a live research trace — confirm each phase
   renders as a distinct card with the correct icon, badge, and items.
2. Wait for `answer_completed` — confirm the audit trail replaces the live
   timeline and the ledger groups (cited / read / candidate) render as cards.
3. Trigger a `job_failed` (or replay a fixture) — confirm the error card shows
   the full error message.
4. Confirm dark mode parity — only existing tokens are referenced.
5. Run `pnpm typecheck` and `pnpm lint` from `frontend/`.

## Risks

- **Low**: Vue template restructure is local. The risk is visual regression,
  not functional. Mitigated by preserving the data flow exactly.
- **Low**: Phase classifier must not miss any current event type. Mitigated by
  the fallthrough rule and by keeping the existing `eventTitle` string
  contracts for the top status strip during a transition window.

## Rollback

Single-file revert: `git checkout HEAD -- frontend/src/views/chat/modules/research-process-panel.vue`.