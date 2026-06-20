# Knowledge Base UI Polish Design

## Goal

Polish the visual details of the PaperLoom paper library page (`http://localhost:9527/#/knowledge-base`) while preserving its vintage paper aesthetic. Make the page feel more comfortable to scan and interact with by tuning spacing, hover behavior, typography hierarchy, and micro-details. Do not change visual identity (color palette, fonts, column structure, copy, or interaction logic).

## Direction

Keep the existing Journal Ink visual language (paper surface `#fbfaf6`, header band `#e2dccc`, binding lines `#c9c1b2`, evidence accent `#7e3f46`, Georgia serif titles, monospace UI labels). Only adjust "comfort parameters" — sizing, padding, alignment, hover softness, micro-typography. The result should look like the same page after a careful designer pass, not a redesign.

## Scope

### Header & Card Framework (Section 1)

In `frontend/src/views/knowledge-base/index.vue` `<style lang="scss">`:

- `.paper-library-card`:
  - `border-radius`: `8px` → `10px`
  - `box-shadow`: `6px 6px 0 rgba(201, 193, 178, 0.55)` → `5px 5px 0 rgba(201, 193, 178, 0.42)`
- `.paper-library-card > .n-card-header`:
  - `padding`: `13px 16px` → `14px 20px`
- `.paper-library-card .n-card__content`:
  - `padding`: `14px 16px 16px` → `16px 20px`
- `.paper-library-card .n-card-header__main`:
  - `font-size`: `20px` → `22px`
  - add `letter-spacing: 0.2px`

### Main Table (Section 2)

In the same `<style lang="scss">`:

- `.paper-library-card .n-data-table` cell padding (apply via `.n-data-table-th` / `.n-data-table-td`):
  - `padding`: `12px 12px` for both header and body cells (override Naive UI default ~8px)
- `.paper-library-card .n-data-table-tr:hover .n-data-table-td`:
  - `background`: `#e2dccc` → `#f1ebd9`
  - add `transition: background-color 120ms ease` on the `.n-data-table-td` rule
- `.library-index-cell`:
  - `gap`: `8px` → `6px`
- `.library-index-line__label`:
  - add `letter-spacing: 0.4px`
- `.library-index-line--failed .library-index-line__top` (existing `.text-stone-500` class in markup):
  - in markup, replace `class="text-stone-500"` with `style="color: #8c4034"` on the `<NEllipsis>` inside `renderActualIndexLine` for the failed branch
- `.library-digest-chip, .library-scope-chip, .library-visibility, .library-pipeline-status`:
  - `min-height`: `22px` → `23px`
  - `padding`: `2px 8px` → `2px 9px`

### Behavior & Copy

No functional or copy changes. No new dependencies. No component restructuring.

## Out of Scope

- Summary stats bar (5 metric tiles) — user explicitly excluded from this pass.
- Upload dialog, search dialog, document preview modal — user explicitly excluded.
- Responsive breakpoints (1180px / 640px media queries).
- Column widths, column structure, table column definitions.
- Color palette, font families, typography choice.
- Backend, RAG pipeline, API contracts.

## Validation

- Run `cd frontend && pnpm dev`, open `http://localhost:9527/#/knowledge-base`.
- Verify the page renders without overlap, broken layout, or scroll artifacts at desktop width (>1180px), tablet (641–1180px), and mobile (≤640px).
- Spot-check: card title at 22px reads cleanly; row hover no longer flashes a high-contrast color; Index Budget column still legible but less dense; status badges visually consistent.
- Run `pnpm typecheck` to confirm the small markup tweak (inline `style` on failed-state `<NEllipsis>`) does not break TSX.