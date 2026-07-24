# Research Process Display — Design

**Date:** 2026-07-24 (revised after first user feedback)
**Scope:** Frontend only. No backend, no API, no schema changes.
**Target file:** `frontend/src/views/chat/modules/research-process-panel.vue`
**Status:** Revised per user feedback 2026-07-24. Goal shifted from "visually distinct phase cards" to "less chrome, more useful content".

## Problem

The original implementation showed each research step as a phase card with a count badge ("1 paper", "5 passages"), a state pill ("running", "completed"), and metric plumbing ("3098 ms · 3,125 tokens", "Thinking · pass 3"). The user found this abstract: the metrics are internal plumbing and the badges duplicate information that should just appear as the result.

The user wants the panel to answer one question — *what did the agent do, and what did it find?* — without parsing metric strings.

## Goal

Show the useful information of the agentic RAG process:

- For each **search**: just the paper names found.
- For each **locate**: just the section names.
- For each **read**: just the page list and a collapsed passage list (user expands manually).
- For each **think**: a one-line decision summary ("Decided to search papers") — not duration, not tokens, not pass numbers.
- For the **status header**: a pulse animation while running; a check when done. No verbose activity label.

Drop:
- Count badges (`"1 paper"`, `"5 passages"`)
- State pills (`"running"` / `"completed"` / `"failed"` text — replaced by icon + animation only)
- Duration suffixes (`"3098 ms"`)
- Token counts (`"3,125 tokens"`)
- Pass counters (`"Thinking · pass 3"`)

## Non-Goals

- No changes to backend contracts, store, router, or websocket handling.
- No new components, no new utils, no global style tokens.
- No extraction of a reusable `<PhaseCard>` until a second consumer appears.
- No new unit tests for this component.
- No expansion of read passages by default — the user wants them collapsed.

## Architecture

Single-file change in `research-process-panel.vue`. The phase classifier from Tasks 1–3 stays; its output shape is simplified:

```ts
type Phase = 'search' | 'locate' | 'read' | 'cite' | 'think' | 'answer' | 'error';

interface PhaseItem {
  key: string | number;
  title: string;
  text: string;
}

interface PhaseView {
  key: string | number;
  phase: Phase;
  state: 'running' | 'completed' | 'failed';
  headline: string;       // e.g. "Searched papers"
  decision: string;       // think-only: "Decided to search papers"
  detail: string;         // read-only: page list; error-only: error message
  items: PhaseItem[];
}
```

Differences from the previous model:
- **Drop** `badge` field (count info now lives in `items`).
- **Drop** `durationMs` field (internal plumbing, never shown).
- **Add** `decision` field (think phase only).
- **Add** `detail` field for the page list / error message (used by read and error).

The rendering pipeline stays the same:

1. `buildPhaseView(event, index)` returns a `PhaseView`.
2. `presentedPhaseCards` / `presentedAuditPhaseCards` computeds feed the two template branches.
3. The audit ledger (cited / read / candidate groups) keeps its three-group structure but uses the same minimal card shape.

## Phase Model

| Phase | Headline | Items content | Detail |
|---|---|---|---|
| `search` | "Searched papers" | `output.papers[].title` | — |
| `locate` | "Located sections" | `output.locations[].section` (+ page) | — |
| `read` | "Read passages" | `output.evidence[]` (collapsed) | "pages 1, 2, 3" |
| `cite` | "Traced citations" | `output.edges[].label` | — |
| `think` | "Reasoning" | — | `decision` (derived from next event) |
| `answer` | "Answer prepared" | — | — |
| `error` | "Research failed" | — | error message |

The "running" variants are kept in the data model so the icon can pulse, but the headlines shown to the user are the completed form. There is no "Searching papers…" / "Searched papers" split — just "Searched papers" once a result arrives, with the icon pulsing while it runs.

### Decision derivation for think

The model_call_completed event itself does not say what was decided — the decision is implicit in what the model did next. Derive:

- If next event is `search_paper_candidates` → "Decided to search papers"
- If next event is `find_reading_locations` → "Decided to locate sections"
- If next event is `read_locations` → "Decided to read passages"
- If next event is `get_citation_edges` → "Decided to trace citations"
- If next event is `answer_completed` → "Decided to answer"
- Otherwise → "Reasoning" (no decision available yet)

Helper: `decisionOf(event, allEvents, index)` looks at `allEvents[index + 1]`.

## Layout

```
┌─────────────────────────────────────────────────────┐
│ ✨ Decided to search papers                         │  ← think: minimal decision line
├─────────────────────────────────────────────────────┤
│ 🔍 Searched papers                                  │  ← search: just paper name(s)
│    Attention Is All You Need                         │
├─────────────────────────────────────────────────────┤
│ 📍 Located sections                                 │  ← locate: just section names
│    3 Model Architecture · p. 2                       │
│    3.1 Encoder and Decoder Stacks · p. 3             │
│    3.2 Attention · p. 4                              │
├─────────────────────────────────────────────────────┤
│ 📖 Read passages                                    │  ← read: collapsed passage list
│    pages 1, 2, 3, 5, 10                              │
│    ▸ 5 passages                                      │
├─────────────────────────────────────────────────────┤
│ ✅ Answer prepared                                   │
└─────────────────────────────────────────────────────┘
```

Each card has only:

- **Icon** — left rail, 24×24, with `--color-research` for active, `--color-success` for completed, `--color-error` for failed.
- **Headline** — short action name (no badges, no counts).
- **Items / detail / decision** — the actual useful content. No metric plumbing.

For `read` the items are inside a `<details>` element so they start collapsed and only expand on click.

For `think` the headline is "Reasoning" and the `decision` text appears below.

## Status header

A single line above the timeline:

- **Running**: pulse animation + current activity (e.g. "Searching papers"). The activity comes from the latest event's headline.
- **Idle / completed**: a check icon + "Research complete".
- **Failed**: a warning icon + "Research failed".

No counts, no metrics, no "X events processed".

## State Mapping

The card state stays on the data model but is only used for icon color and the running pulse animation — never rendered as text.

| Source state | Card state |
|---|---|
| `tool_started`, `model_call_started` | `running` |
| `tool_completed`, `model_call_completed`, `answer_completed` | `completed` |
| `job_failed`, `status === 'failed'` | `failed` |

Color tokens (existing, no additions):

- `running` → `--color-research` (pulsing)
- `completed` → `--color-success`
- `failed` → `--color-error`

## Edge Cases

- **No events + not running** → existing empty state.
- **Running with no events yet** → status header pulses; timeline area shows a single pulsing card.
- **Long evidence quote** → 3-line clamp, ellipsis, manual click to expand.
- **Unknown tool/type** → falls through to a "Research progress" card with the icon and items if available.
- **Streaming → audit transition** → audit replaces the live timeline; same card shape.
- **Multiple think events** → all are shown (no collapsing — each is its own decision line).

## Out of Scope (intentionally)

- Animations beyond the existing pulse on the running state.
- Sound or haptic feedback.
- New i18n keys (the panel uses hard-coded English; preserving that keeps the diff small).
- New global tokens or theme changes.
- Re-introducing any of the dropped metrics anywhere in the panel.

## Verification

Run in dev mode and walk through:

1. Open a chat answer with a live research trace — confirm: no badges, no state pills, no duration/tokens, paper names visible for search, sections visible for locate, page list and collapsed passages for read, decision line for think.
2. Wait for `answer_completed` — confirm the audit trail replaces the live timeline and the ledger groups (cited / read / candidate) render with the same minimal shape.
3. Trigger a `job_failed` (or replay a fixture) — confirm the error card shows the error message.
4. Confirm dark mode parity — only existing tokens are referenced.
5. Run `pnpm typecheck` and `pnpm lint` from `frontend/`.

## Risks

- **Low**: visual redesign is local. The data model stays compatible — only the rendering shape changes.
- **Low**: existing helpers (`eventTitle`, `eventDetail`, etc.) become unused and are deleted in Task 5.

## Rollback

Single-file revert: `git checkout HEAD -- frontend/src/views/chat/modules/research-process-panel.vue`.