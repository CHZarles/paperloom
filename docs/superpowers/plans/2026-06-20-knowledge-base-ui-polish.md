# Knowledge Base UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply visual polish to the PaperLoom paper library page (`/#/knowledge-base`) while keeping its Journal Ink vintage aesthetic intact.

**Architecture:** Pure SCSS + one-line TSX tweak in a single existing file (`frontend/src/views/knowledge-base/index.vue`). No new components, no functional changes, no new dependencies. Changes split across two implementation commits (card framework first, table second) for clean review, followed by a visual verification checkpoint.

**Tech Stack:** Vue 3 + TypeScript (TSX in `<script setup>`), Naive UI (`NDataTable`, `NCard`, `NProgress`, etc.), SCSS.

**User Verification:** YES — user must visually confirm the polish matches the design spec across desktop/tablet/mobile breakpoints at the end.

---

## File Structure

**Modified files (single file, two commits):**

- `frontend/src/views/knowledge-base/index.vue` — the only file touched.
  - `<style lang="scss">` block: card framework tweaks (Task 1) + table tweaks (Task 2)
  - `<script setup lang="tsx">` block: one inline-style swap on the failed-state `<NEllipsis>` (Task 2)

**No new files. No file splits (the file is already focused on a single page concern).**

---

## Task 1: Card Framework Polish

**Goal:** Apply the Section 1 visual tweaks (border-radius, box-shadow, padding, title sizing/letter-spacing) to the paper library card.

**Files:**
- Modify: `frontend/src/views/knowledge-base/index.vue` (inside `<style lang="scss">` block, ~4 selectors)

**Acceptance Criteria:**
- [ ] `.paper-library-card` `border-radius` is `10px`
- [ ] `.paper-library-card` `box-shadow` is `5px 5px 0 rgba(201, 193, 178, 0.42)`
- [ ] `.paper-library-card > .n-card-header` `padding` is `14px 20px`
- [ ] `.paper-library-card .n-card__content` `padding` is `16px 20px`
- [ ] `.paper-library-card .n-card-header__main` `font-size` is `22px` with `letter-spacing: 0.2px`
- [ ] No other selectors in this task are changed
- [ ] `pnpm typecheck` exits 0

**Verify:** `cd frontend && pnpm typecheck` → exits 0 with no TypeScript errors.

**Steps:**

- [ ] **Step 1: Update `.paper-library-card` border-radius and box-shadow**

In `frontend/src/views/knowledge-base/index.vue`, inside `<style lang="scss">`, locate the existing rule:

```scss
.paper-library-card {
  overflow: hidden;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
  box-shadow: 6px 6px 0 rgba(201, 193, 178, 0.55);
}
```

Replace with:

```scss
.paper-library-card {
  overflow: hidden;
  border: 1px solid #c9c1b2;
  border-radius: 10px;
  background: #fbfaf6;
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.42);
}
```

- [ ] **Step 2: Update card header padding**

In the same `<style lang="scss">` block, locate:

```scss
.paper-library-card > .n-card-header {
  border-bottom: 1px solid #c9c1b2;
  background: #e2dccc;
  padding: 13px 16px;
}
```

Replace with:

```scss
.paper-library-card > .n-card-header {
  border-bottom: 1px solid #c9c1b2;
  background: #e2dccc;
  padding: 14px 20px;
}
```

- [ ] **Step 3: Update card title size and add letter-spacing**

Locate:

```scss
.paper-library-card .n-card-header__main {
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 20px;
  font-weight: 700;
}
```

Replace with:

```scss
.paper-library-card .n-card-header__main {
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.2px;
}
```

- [ ] **Step 4: Update card content padding**

Locate:

```scss
.paper-library-card .n-card__content {
  background: #fbfaf6;
  padding: 14px 16px 16px;
}
```

Replace with:

```scss
.paper-library-card .n-card__content {
  background: #fbfaf6;
  padding: 16px 20px;
}
```

- [ ] **Step 5: Run frontend type check**

Run: `cd frontend && pnpm typecheck`
Expected: command exits 0, no TypeScript errors.

- [ ] **Step 6: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/knowledge-base/index.vue
git commit -m "style(knowledge-base): polish card framework spacing, shadow, title"
```

---

## Task 2: Table Polish

**Goal:** Apply Section 2 visual tweaks (cell padding, hover softness, transitions, Index Budget density, badge sizing, failed-state markup tweak).

**Files:**
- Modify: `frontend/src/views/knowledge-base/index.vue`
  - `<script setup lang="tsx">` block — one inline-style swap on the failed-state `<NEllipsis>` (1 occurrence)
  - `<style lang="scss">` block — 5 selector edits (th/td padding + transition, hover color, index cell gap, index label letter-spacing, badge sizing)

**Acceptance Criteria:**
- [ ] `.paper-library-card .n-data-table-th` and `.n-data-table-td` both have `padding: 12px 12px`
- [ ] `.paper-library-card .n-data-table-td` has `transition: background-color 120ms ease`
- [ ] `.paper-library-card .n-data-table-tr:hover .n-data-table-td` background is `#f1ebd9` (no longer `#e2dccc`)
- [ ] `.library-index-cell` gap is `6px`
- [ ] `.library-index-line__label` has `letter-spacing: 0.4px`
- [ ] Failed-state `<NEllipsis>` in `renderActualIndexLine` uses `style="color: #8c4034"` instead of `class="text-stone-500"`
- [ ] `.library-digest-chip, .library-scope-chip, .library-visibility, .library-pipeline-status` group has `min-height: 23px` and `padding: 2px 9px`
- [ ] No other selectors in this task are changed
- [ ] `pnpm typecheck` exits 0

**Verify:** `cd frontend && pnpm typecheck` → exits 0 with no TypeScript errors.

**Steps:**

- [ ] **Step 1: Update table cell padding (th + td) and add transition**

In `<style lang="scss">`, locate the existing th/td rules:

```scss
.paper-library-card .n-data-table-th {
  background: #eeeae1;
  color: #394150;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.paper-library-card .n-data-table-td {
  background: #fbfaf6;
  vertical-align: middle;
}
```

Replace with:

```scss
.paper-library-card .n-data-table-th {
  background: #eeeae1;
  color: #394150;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  padding: 12px 12px;
}

.paper-library-card .n-data-table-td {
  background: #fbfaf6;
  vertical-align: middle;
  padding: 12px 12px;
  transition: background-color 120ms ease;
}
```

- [ ] **Step 2: Update row hover background**

Locate:

```scss
.paper-library-card .n-data-table-tr:hover .n-data-table-td {
  background: #e2dccc;
}
```

Replace with:

```scss
.paper-library-card .n-data-table-tr:hover .n-data-table-td {
  background: #f1ebd9;
}
```

- [ ] **Step 3: Update `.library-index-cell` gap**

Locate:

```scss
.library-index-cell {
  display: grid;
  gap: 8px;
  min-width: 220px;
}
```

Replace with:

```scss
.library-index-cell {
  display: grid;
  gap: 6px;
  min-width: 220px;
}
```

- [ ] **Step 4: Add letter-spacing to `.library-index-line__label`**

Locate:

```scss
.library-index-line__label {
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}
```

Replace with:

```scss
.library-index-line__label {
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.4px;
}
```

- [ ] **Step 5: Replace Tailwind utility with inline color on failed-state `<NEllipsis>`**

In `<script setup lang="tsx">`, inside the `renderActualIndexLine` function, locate the FAILED branch (the one returning a `<div class="library-index-line library-index-line--failed">`). Inside that branch, locate:

```tsx
<NEllipsis tooltip lineClamp={2} class="text-stone-500">
  {row.processingErrorMessage || '请检查 Embedding 额度或稍后重试'}
</NEllipsis>
```

Replace with:

```tsx
<NEllipsis tooltip lineClamp={2} style="color: #8c4034">
  {row.processingErrorMessage || '请检查 Embedding 额度或稍后重试'}
</NEllipsis>
```

- [ ] **Step 6: Update badge sizing (chips and status pills)**

Locate the grouped selector rule:

```scss
.library-digest-chip,
.library-scope-chip,
.library-visibility,
.library-pipeline-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 22px;
  padding: 2px 8px;
  border: 1px solid #c9c1b2;
  border-radius: 999px;
  background: #e2dccc;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
```

Replace with:

```scss
.library-digest-chip,
.library-scope-chip,
.library-visibility,
.library-pipeline-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 23px;
  padding: 2px 9px;
  border: 1px solid #c9c1b2;
  border-radius: 999px;
  background: #e2dccc;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
```

- [ ] **Step 7: Run frontend type check**

Run: `cd frontend && pnpm typecheck`
Expected: command exits 0, no TypeScript errors.

- [ ] **Step 8: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/knowledge-base/index.vue
git commit -m "style(knowledge-base): polish table density, hover, and badges"
```

---

## Task 3: Visual Verification

**Goal:** Confirm the UI polish matches the approved design spec and that the page still renders correctly across desktop / tablet / mobile breakpoints, with explicit user sign-off.

**Files:** (none modified — verification only)

**Acceptance Criteria:**
- [ ] Dev server runs at `http://localhost:9527` without console errors on the knowledge-base page
- [ ] Card title renders at 22px with subtle letter-spacing
- [ ] Card header and content padding visually balanced (14px / 16px vertical, 20px horizontal)
- [ ] Card border-radius is 10px (softer corners)
- [ ] Card shadow is `5px 5px 0 rgba(201,193,178,0.42)` (gentler than before)
- [ ] Table rows have visible breathing room (12px cell padding)
- [ ] Row hover background changes to a soft warm tint (#f1ebd9), not a strong color swap
- [ ] Row hover transition is smooth (~120ms)
- [ ] Index Budget column slightly tighter (6px gap between EST/ACT lines)
- [ ] Status badges visually consistent at 23px min-height
- [ ] A failed-state Index Budget row's error message renders in rust color (#8c4034)
- [ ] Page renders without overlap at desktop (≥1180px), tablet (641–1180px), mobile (≤640px)
- [ ] User confirms the polish looks good

**Verify:** User visually inspects the page across breakpoints.

**Steps:**

- [ ] **Step 1: Start the dev server**

Run in the foreground or note the URL:

```bash
cd /home/charles/PaiSmart/frontend
pnpm dev
```

Expected: Vite reports a local URL, typically `http://localhost:9527/`.

- [ ] **Step 2: Open the knowledge-base page**

Navigate to `http://localhost:9527/#/knowledge-base` in a browser.

- [ ] **Step 3: Inspect desktop layout (≥1180px)**

Set browser viewport to ≥1280px wide. Confirm:
- Card title "Paper Library / 文献库" reads at 22px with subtle letter-spacing (serif, slightly wider tracking)
- Card padding feels balanced — header band slightly thinner than content area, both at 20px horizontal
- Card corners are visibly softer (10px radius) but still rectangular
- Card shadow is gentler than before (5px offset, lower opacity) — still has the vintage offset-shadow feel
- Table rows have visible breathing room (not cramped)
- Hover any row → background transitions smoothly to a soft warm tint, no jarring color flash
- Inspect an Index Budget cell → EST and ACT lines feel slightly closer together (6px gap)
- Inspect status badges (Ready/Interrupted, Public/Private, scope chips) → consistent sizing, 23px min-height
- Inspect a failed-state Index Budget row (if available) → error message renders in rust color matching the broken-status color

- [ ] **Step 4: Inspect tablet layout (641–1180px)**

Resize viewport to ~900px. Confirm:
- Summary stats bar wraps to 3-column grid (existing behavior, unchanged)
- No overlap between summary stats and the table
- Table remains readable (horizontal scroll if needed)

- [ ] **Step 5: Inspect mobile layout (≤640px)**

Resize viewport to ~400px. Confirm:
- Card header stacks vertically (existing behavior, unchanged)
- Summary stats wrap to 2-column grid (existing behavior, unchanged)
- Table is horizontally scrollable (existing behavior, unchanged)
- No text overlap, no clipped badges

- [ ] **Step 6: User verification checkpoint**

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:

```yaml
AskUserQuestion:
  question: "知识库页面 UI 打磨结果如何?对照设计 spec 检查卡片与表格细节。"
  header: "Verification"
  options:
    - label: "看起来更舒服了"
      description: "保留复古风,只打磨了细节 — 符合预期,可以收尾"
    - label: "还需要调整"
      description: "某些细节没达到预期,需要再调"
```

If the user selects "还需要调整", capture their specific feedback and create follow-up tasks to address it before marking the plan complete.