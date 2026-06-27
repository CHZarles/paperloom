# Global Color and Icon Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor PaperLoom's color palette to a NeurIPS/ACL-inspired academic blue + burnt orange scheme via semantic CSS tokens, and consolidate the 5 mixed icon families (MDI, Material Symbols, Phosphor, Heroicons, Ant Design) into a single Material Symbols set. No layout, font, or behavior changes.

**Architecture:** Define a single `tokens.css` with `--color-*` variables (light + dark). Migrate every component SCSS to reference tokens instead of hardcoded hex. Migrate every `icon-mdi-*`, `icon-ph:*`, `icon-heroicons:*`, `icon-ant-design:*` call site to `icon-material-symbols:*`. Six-commit plan ordered by risk and file association: foundation → layouts → business pages → admin pages → built-in/personal → final sweep.

**Tech Stack:** Vue 3 + TypeScript (TSX), SCSS, Naive UI, @iconify/vue, Vite.

**User Verification:** YES — user visually confirms the refactored design at the end (Task 6 is a dedicated verification task with `requiresUserVerification: true`).

**Reference:** The complete color token system, dark mode values, color-mapping table, and icon-mapping table are defined in the spec at `docs/superpowers/specs/2026-06-21-global-color-and-icon-refactor-design.md`. This plan references those tables rather than duplicating them.

---

## File Structure

**New file:**
- `frontend/src/styles/css/tokens.css` — color tokens (light + dark mode)

**Modified files (all frontend):**
- `frontend/src/styles/css/global.css` — use tokens for `.admin-console-card`, `.admin-console-note`
- `frontend/src/layouts/**/*.vue` — colors + icons
- `frontend/src/components/**/*` — colors + icons
- `frontend/src/views/**/*` — colors + icons

The migration is file-by-file. Each commit touches a specific subset; see tasks below.

---

## Task 1: Foundation — Token System

**Goal:** Define the color token system in a new `tokens.css`, import it into the global styles entry, and migrate `global.css` to use tokens. This is the foundation — once it lands, every other commit can reference `--color-*` variables.

**Files:**
- Create: `frontend/src/styles/css/tokens.css`
- Modify: `frontend/src/styles/css/index.css` (or equivalent global styles entry — locate by reading `frontend/src/main.ts`)
- Modify: `frontend/src/styles/css/global.css` (use tokens for `.admin-console-card`, `.admin-console-note`, `.admin-console-page`, etc.)

**Acceptance Criteria:**
- [ ] `frontend/src/styles/css/tokens.css` exists and defines all light + dark `--color-*` tokens per the spec's "Semantic tokens" section
- [ ] `tokens.css` is imported by the global styles entry so tokens are available app-wide
- [ ] `global.css` references `var(--color-*)` for all previously hardcoded color values (specifically `.admin-console-card` border/bg/shadow, `.admin-console-note` border/bg/color, `.admin-console-page` color)
- [ ] No hardcoded hex colors in `global.css` for the values covered by tokens (border, bg, text, accent)
- [ ] `pnpm typecheck` exits 0
- [ ] Dev server boots without errors and renders a test page (e.g., `/user`) correctly in both light and dark mode

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck` → exit 0. Then `pnpm dev` and visit `http://localhost:9527/#/user` (a non-chat, non-knowledge-base admin page); confirm the page renders in both light and dark mode.

**Steps:**

- [ ] **Step 1: Locate the global styles entry point**

```bash
cat /home/charles/PaiSmart/frontend/src/main.ts
```

Find the line that imports the global stylesheet (likely `import './styles/css/index.css'` or similar). Note the path. Also `cat /home/charles/PaiSmart/frontend/src/styles/css/index.css` to see the existing import order.

- [ ] **Step 2: Create `tokens.css` with the full token system**

Create `/home/charles/PaiSmart/frontend/src/styles/css/tokens.css` with these contents:

```css
:root {
  --color-bg:                #faf7f2;
  --color-surface:           #ffffff;
  --color-surface-alt:       #faf6e8;
  --color-text:              #1a1a2e;
  --color-text-muted:        #5b6378;
  --color-primary:           #1e3a5f;
  --color-primary-hover:     #2a508a;
  --color-primary-soft-bg:   #e8eef5;
  --color-accent:            #c2410c;
  --color-accent-hover:      #ea580c;
  --color-accent-soft-bg:    #fdf0e3;
  --color-border:            #c9c1b2;
  --color-border-soft:       #ddd3bf;
  --color-card-band:         #f3ede0;
  --color-card-band-pressed: #e7dcc4;
  --color-success:           #166534;
  --color-warning:           #b45309;
  --color-error:             #b91c1c;
  --shadow-card:             5px 5px 0 rgba(126, 139, 168, 0.32);
  --shadow-card-soft:        3px 3px 0 rgba(126, 139, 168, 0.28);
}

.dark {
  --color-bg:                #0f1019;
  --color-surface:           #1a1c2e;
  --color-surface-alt:       #161828;
  --color-text:              #f3ede0;
  --color-text-muted:        #a8b0c0;
  --color-primary:           #6c8ec0;
  --color-primary-hover:     #8ba8d4;
  --color-primary-soft-bg:   #1c2440;
  --color-accent:            #f08a4b;
  --color-accent-hover:      #f5a673;
  --color-accent-soft-bg:    #2a1a10;
  --color-border:            #2a2d40;
  --color-border-soft:       #1f2230;
  --color-card-band:         #1a1c2e;
  --color-card-band-pressed: #232638;
  --color-success:           #4ade80;
  --color-warning:           #fbbf24;
  --color-error:             #f87171;
  --shadow-card:             4px 4px 0 rgba(0, 0, 0, 0.4);
  --shadow-card-soft:        2px 2px 0 rgba(0, 0, 0, 0.32);
}
```

- [ ] **Step 3: Import tokens in the global styles entry**

In the global styles entry file (likely `frontend/src/styles/css/index.css` or whatever `main.ts` imports), add at the top:

```css
@import './tokens.css';
```

This must be the first import so tokens are available to all other styles.

- [ ] **Step 4: Migrate `global.css` to use tokens**

Read `/home/charles/PaiSmart/frontend/src/styles/css/global.css`. For each rule, replace hardcoded color values with `var(--color-*)` references per the spec's "Old → new mapping" table. Specifically:

- `.admin-console-page` — `color: #20242a` → `color: var(--color-text)`
- `.admin-console-card` — `border: 1px solid #c9c1b2` → `border: 1px solid var(--color-border)`; `border-radius: 8px` (keep); `background: #fbfaf6` → `background: var(--color-surface)`; `box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.58)` → `box-shadow: var(--shadow-card)` (preserve `!important`)
- `.admin-console-card > .n-card-header` — `border-bottom: 1px solid #c9c1b2` → `var(--color-border)`; background / padding keep
- `.admin-console-note` — `border: 1px solid #c9c1b2` → `var(--color-border)`; `background: #e2dccc` → `var(--color-card-band)`; `color: #5e6470` → `var(--color-text-muted)`
- All other rules with hardcoded `#fbfaf6`, `#e2dccc`, `#c9c1b2`, `#26364a`, `#7e3f46`, `#5e6470`, `#20242a` → substitute with the matching `var(--color-*)` token
- Dark mode rules (`.dark .admin-console-card` etc.) — keep the `.dark` selector but replace values with `var(--color-*)` references (the dark values are already in tokens.css under `.dark`)

Show one example migration:

Before:
```scss
.admin-console-card {
  border: 1px solid #c9c1b2 !important;
  border-radius: 8px !important;
  background: #fbfaf6 !important;
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.58);
}
```

After:
```scss
.admin-console-card {
  border: 1px solid var(--color-border) !important;
  border-radius: 8px !important;
  background: var(--color-surface) !important;
  box-shadow: var(--shadow-card);
}
```

Apply the same pattern to all rules in `global.css`.

- [ ] **Step 5: Verify**

```bash
cd /home/charles/PaiSmart/frontend && pnpm typecheck
```
Expected: exit 0.

Then start dev server (`pnpm dev`) and visit `http://localhost:9527/#/user` (an admin page using `.admin-console-card`). Confirm the card renders in light mode. Toggle dark mode in the theme drawer; confirm dark mode renders correctly.

- [ ] **Step 6: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/styles/css/tokens.css frontend/src/styles/css/index.css frontend/src/styles/css/global.css
git commit -m "style(tokens): define CSS color tokens + migrate global.css to use them"
```

---

## Task 2: Layouts + Global Components

**Goal:** Migrate all layout components and global/shared components to use color tokens and Material Symbols icons. After this task, the "skeleton" of the app (header, sidebar, footer, breadcrumbs, theme drawer, common buttons, file preview, PDF viewer) is fully on the new system.

**Files:**
- Modify (layouts): `frontend/src/layouts/base-layout/index.vue`, `frontend/src/layouts/blank-layout/index.vue`, `frontend/src/layouts/modules/global-header/index.vue`, `frontend/src/layouts/modules/global-header/components/user-avatar.vue`, `frontend/src/layouts/modules/global-sider/index.vue`, `frontend/src/layouts/modules/global-tab/index.vue`, `frontend/src/layouts/modules/global-tab/context-menu.vue`, `frontend/src/layouts/modules/global-content/index.vue`, `frontend/src/layouts/modules/global-footer/index.vue`, `frontend/src/layouts/modules/global-breadcrumb/index.vue`, `frontend/src/layouts/modules/global-logo/index.vue`, `frontend/src/layouts/modules/global-search/index.vue`, `frontend/src/layouts/modules/global-search/components/*` (all 3 files), `frontend/src/layouts/modules/global-menu/index.vue` + `modules/*` (5 files), `frontend/src/layouts/modules/theme-drawer/index.vue` + `modules/*` (5 files)
- Modify (common components): `frontend/src/components/common/pin-toggler.vue`, `lang-switch.vue`, `exception-base.vue`, `menu-toggler.vue`
- Modify (advanced components): `frontend/src/components/advanced/table-column-setting.vue`, `table-header-operation.vue`
- Modify (custom components): `frontend/src/components/custom/svg-icon.vue`, `file-preview.vue`, `pdf-document-viewer.vue`, `button-icon.vue`, `look-forward.vue`

**Acceptance Criteria:**
- [ ] All hardcoded color hex values (specifically the Journal Ink palette `#fbfaf6`, `#e2dccc`, `#c9c1b2`, `#26364a`, `#7e3f46`, `#5e6470`, `#20242a`, `#f1ebd9`, `#ddd3bf`) in the listed files are replaced with `var(--color-*)` tokens
- [ ] All non-Material-Symbols icons (`icon-mdi-*`, `icon-ph:*`, `icon-heroicons:*`, `icon-ant-design:*`) in the listed files are replaced with the Material Symbols equivalent from the spec's icon mapping table
- [ ] `pnpm typecheck` exits 0
- [ ] Dev server renders `/knowledge-base`, `/chat`, `/user`, `/login` without obvious regressions
- [ ] Dark mode still works in `/user` and `/knowledge-base`

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck` → exit 0. Then `pnpm dev` and visit `/knowledge-base`, `/user`, `/login`; check both light and dark mode.

**Steps:**

- [ ] **Step 1: Migrate `frontend/src/components/custom/svg-icon.vue`**

Read the file. If it has hardcoded color values (e.g., `color: #...`), replace with `currentColor` or a token. If it has no hardcoded colors, no change needed. Note: `svg-icon.vue` is the icon wrapper itself; usually it doesn't have its own colors.

- [ ] **Step 2: Migrate the common components (`pin-toggler.vue`, `lang-switch.vue`, `exception-base.vue`, `menu-toggler.vue`)**

For each file:
- Replace hardcoded hex colors with `var(--color-*)` tokens
- Replace `icon-mdi-*`, `icon-ph:*`, `icon-heroicons:*`, `icon-ant-design:*` with their Material Symbols equivalents

Example pattern for color replacement:
```vue
<!-- Before -->
<style scoped>.foo { color: #5e6470; background: #fbfaf6; }</style>
<!-- After -->
<style scoped>.foo { color: var(--color-text-muted); background: var(--color-surface); }</style>
```

Example pattern for icon replacement:
```vue
<!-- Before -->
<icon-mdi-magnify />
<!-- After -->
<icon-material-symbols:search-rounded />
```

- [ ] **Step 3: Migrate the advanced components (`table-column-setting.vue`, `table-header-operation.vue`)**

Apply the same patterns. The table-header-operation uses `icon-mdi-drag` and `icon-mdi-refresh` — replace per the spec.

- [ ] **Step 4: Migrate the custom components (`file-preview.vue`, `pdf-document-viewer.vue`, `button-icon.vue`, `look-forward.vue`)**

Apply the same patterns. `file-preview.vue` uses `icon-mdi-open-in-new`, `icon-mdi-download`, `icon-mdi-close`, `icon-mdi-alert-circle` — replace per the spec. `pdf-document-viewer.vue` uses `icon-mdi-chevron-left/right`, `icon-mdi-magnify-minus/plus-outline`, `icon-mdi-alert-circle` — replace per the spec.

- [ ] **Step 5: Migrate the layout components**

For each file in `frontend/src/layouts/**`:
- Replace hardcoded hex colors with `var(--color-*)` tokens
- Replace non-Material-Symbols icons with Material Symbols equivalents

Pay special attention to:
- `global-header/index.vue` — `icon-mdi-robot-outline` (the new "Chatbot" back button) → `icon-material-symbols:smart-toy-outline-rounded`
- `global-header/components/user-avatar.vue` — `icon-ph:user-circle` → `icon-material-symbols:account-circle-outline-rounded`; `icon-ph:sign-out` → `icon-material-symbols:logout-rounded`
- `global-tab/context-menu.vue` — `icon-ant-design:close-outlined` → `icon-material-symbols:close-rounded`; `icon-ant-design:column-width-outlined` → `icon-material-symbols:width-rounded`; `icon-ant-design:line-outlined` → `icon-material-symbols:horizontal-rule-rounded`; `icon-mdi-format-horizontal-align-left/right` → `icon-material-symbols:format-align-left/right-rounded`
- `global-search/components/search-footer.vue` — keyboard icons: `icon-mdi-keyboard-return` → `icon-material-symbols:keyboard-return-rounded`; `icon-mdi-arrow-up/down-thin` → `icon-material-symbols:keyboard-arrow-up/down-rounded`; `icon-mdi-keyboard-esc` → `icon-material-symbols:keyboard-esc-rounded`
- `lang-switch.vue` — `icon-heroicons:language` → `icon-material-symbols:language-rounded`
- `theme-drawer/modules/dark-mode.vue` — uses SvgIcon with `:icon="icons[key]"` (the icons come from the route config). No direct icon family in this file; just ensure the SvgIcon component is preserved.
- `global-content/index.vue` — the `p-[32px_16px_16px_32px]` class on the `<component :is="Component">` provides layout padding. No colors directly; check it doesn't break after token migration.

- [ ] **Step 6: Run typecheck**

```bash
cd /home/charles/PaiSmart/frontend && pnpm typecheck
```
Expected: exit 0.

- [ ] **Step 7: Smoke test in browser**

```bash
cd /home/charles/PaiSmart/frontend && pnpm dev
```

Visit:
- `http://localhost:9527/#/user` — admin page with sidebar + header + table
- `http://localhost:9527/#/knowledge-base` — paper library
- `http://localhost:9527/#/login` — login form

Confirm no broken icons, no obvious color regressions. Toggle dark mode in the theme drawer.

- [ ] **Step 8: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/layouts frontend/src/components
git commit -m "style(refactor): migrate layouts + global components to color tokens + Material Symbols"
```

---

## Task 3: Knowledge Base + Chat (Core Business Pages)

**Goal:** Migrate the two most prominent business pages (paper library + chat interface) to use color tokens and Material Symbols icons. These pages are user-facing and need the most polish.

**Files:**
- Modify: `frontend/src/views/knowledge-base/index.vue`, `frontend/src/views/knowledge-base/modules/upload-dialog.vue`, `frontend/src/views/knowledge-base/modules/search-dialog.vue`
- Modify: `frontend/src/views/chat/index.vue`, `frontend/src/views/chat/modules/chat-list.vue`, `frontend/src/views/chat/modules/chat-message.vue`, `frontend/src/views/chat/modules/input-box.vue`, `frontend/src/views/chat/modules/conversation-sidebar.vue`, `frontend/src/views/chat/modules/reference-preview-page.vue`

**Acceptance Criteria:**
- [ ] All Journal Ink hex values in the listed files replaced with `var(--color-*)` tokens
- [ ] All non-Material-Symbols icons in the listed files replaced per the spec's icon mapping table
- [ ] `pnpm typecheck` exits 0
- [ ] `/knowledge-base` renders correctly in light + dark mode; table, summary stats, badges all use the new color tokens
- [ ] `/chat` renders correctly in light + dark mode; conversation sidebar, message list, input box, reference preview panel all use the new tokens and Material Symbols

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck` → exit 0. Then `pnpm dev` and visit `/knowledge-base` and `/chat`; toggle dark mode.

**Steps:**

- [ ] **Step 1: Migrate `frontend/src/views/knowledge-base/index.vue`**

The most complex file in this task. Apply color + icon migration. Specific icons to replace:
- `icon-mdi-file-search-outline` → `icon-material-symbols:search-rounded`
- `icon-mdi-upload-outline` → `icon-material-symbols:upload-rounded`
- `icon-mdi-trash-can-outline` → `icon-material-symbols:delete-outline-rounded`
- `icon-mdi-file-eye-outline` → `icon-material-symbols:visibility-outline-rounded`

Color changes: the entire `<style lang="scss">` block has many hardcoded values. Replace per the spec's color mapping. Key values to substitute:
- `#fbfaf6` (paper) → `var(--color-bg)` or `var(--color-surface)`
- `#e2dccc` (header band) → `var(--color-card-band)`
- `#c9c1b2` (border) → `var(--color-border)`
- `#26364a` (title) → `var(--color-primary)`
- `#7e3f46` (wine accent) → `var(--color-accent)`
- `#5e6470` (muted) → `var(--color-text-muted)`
- `#20242a` (ink) → `var(--color-text)`
- `#f1ebd9` (row hover) → `var(--color-surface-alt)`
- `#ddd3bf` (mobile border) → `var(--color-border-soft)`

Note: status pill colors (`.library-pipeline-status--ready` uses `rgba(79, 125, 90, 0.35)` for border, `#3f6b4a` for text — greens; `.library-pipeline-status--broken` uses `rgba(159, 76, 63, 0.35)` border, `#8c4034` text — reds) should map to the new `--color-success` / `--color-error` tokens, possibly with an alpha. Keep the existing status semantics; just use the new tokens where they fit.

- [ ] **Step 2: Migrate `frontend/src/views/knowledge-base/modules/upload-dialog.vue`**

Light-touch migration. Replace any hardcoded colors with tokens. The upload dialog uses standard Naive UI components; few custom colors.

- [ ] **Step 3: Migrate `frontend/src/views/knowledge-base/modules/search-dialog.vue`**

Same light-touch migration.

- [ ] **Step 4: Migrate `frontend/src/views/chat/index.vue`**

The chat shell has many custom CSS variables (`--paper-accent`, `--paper-line`, etc.) defined locally. The spec acknowledges: "These will be migrated to global tokens where possible, but some local `--paper-*` variables may be retained for the chat's internal grid background pattern."

Action: rename `--paper-accent` → `--chat-accent` and have it reference `var(--color-primary)`; rename `--paper-line` → `--chat-line` and have it reference `var(--color-border)`; etc. The `background:` value that uses a complex pattern (grid lines over a base color) may need to be retained as a hardcoded value because tokens can't express a multi-layer background pattern cleanly.

Icon: `icon-material-symbols:left-panel-open-outline-rounded` and `icon-material-symbols:article-outline-rounded` (already Material Symbols — no change needed).

- [ ] **Step 5: Migrate `frontend/src/views/chat/modules/chat-list.vue`**

Light-touch. Replace colors with tokens.

- [ ] **Step 6: Migrate `frontend/src/views/chat/modules/chat-message.vue`**

Replace colors with tokens. Icon changes:
- `icon-ph:user-circle` → `icon-material-symbols:account-circle-outline-rounded`
- `icon-material-symbols:check-circle-rounded` / `icon-material-symbols:error-rounded` (already Material Symbols — keep)
- `icon-material-symbols:thumb-up/down-outline-rounded` (already — keep)

- [ ] **Step 7: Migrate `frontend/src/views/chat/modules/input-box.vue`**

Replace colors with tokens. Icons: `icon-material-symbols:stop-rounded` and `icon-material-symbols:arrow-upward-rounded` (already — keep).

- [ ] **Step 8: Migrate `frontend/src/views/chat/modules/conversation-sidebar.vue`**

Replace colors with tokens. Icons:
- `icon-material-symbols:left-panel-close-outline-rounded` (already — keep)
- `icon-material-symbols:add-rounded` (already — keep)
- `icon-material-symbols:search-rounded` (already — keep)
- `icon-material-symbols:chat-outline-rounded` (already — keep)
- `icon-material-symbols:archive-outline-rounded` / `icon-material-symbols:unarchive-outline-rounded` (already — keep)
- `icon-mdi-bookshelf` → `icon-material-symbols:menu-book-outline-rounded`
- `icon-material-symbols:logout-rounded` (already — keep)

Also update the recent session-item polish colors (added in earlier commit) to use the new token system. The active state `background: #fbfaf6; border-left-color: #7e3f46;` should become `background: var(--color-surface); border-left-color: var(--color-accent);` to align with the new palette.

- [ ] **Step 9: Migrate `frontend/src/views/chat/modules/reference-preview-page.vue`**

Light-touch. No icons to change.

- [ ] **Step 10: Run typecheck**

```bash
cd /home/charles/PaiSmart/frontend && pnpm typecheck
```
Expected: exit 0.

- [ ] **Step 11: Smoke test in browser**

Visit `http://localhost:9527/#/knowledge-base` and `http://localhost:9527/#/chat`. Confirm:
- All icons render
- All colors look harmonious with the new academic blue + burnt orange palette
- Hover states, active states, table row hovers all work
- Dark mode works in both pages

- [ ] **Step 12: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/knowledge-base frontend/src/views/chat
git commit -m "style(refactor): migrate knowledge-base + chat to color tokens + Material Symbols"
```

---

## Task 4: Admin Pages

**Goal:** Migrate all admin pages (user, org-tag, model-provider, invite-code, usage-monitor, recharge, recharge-manage, chat-history) to use color tokens and Material Symbols icons.

**Files:**
- Modify: `frontend/src/views/user/index.vue`, `frontend/src/views/user/modules/user-search.vue`, `frontend/src/views/user/modules/org-tag-setting-dialog.vue`, `frontend/src/views/user/modules/token-quota-dialog.vue`
- Modify: `frontend/src/views/org-tag/index.vue`, `frontend/src/views/org-tag/modules/org-tag-operate-dialog.vue`
- Modify: `frontend/src/views/model-provider/index.vue`
- Modify: `frontend/src/views/invite-code/index.vue`
- Modify: `frontend/src/views/usage-monitor/index.vue`
- Modify: `frontend/src/views/recharge/index.vue`, `frontend/src/views/recharge-manage/index.vue`
- Modify: `frontend/src/views/chat-history/index.vue`

**Acceptance Criteria:**
- [ ] All Journal Ink hex values in the listed files replaced with `var(--color-*)` tokens
- [ ] All non-Material-Symbols icons in the listed files replaced per the spec's icon mapping table
- [ ] `pnpm typecheck` exits 0
- [ ] All admin pages render correctly in both light and dark mode
- [ ] Table column settings and table header operations still work
- [ ] No regressions in the model-provider, invite-code, usage-monitor, user pages (which were just polished in earlier commits)

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck` → exit 0. Then `pnpm dev` and visit `/user`, `/org-tag`, `/model-provider`, `/invite-code`, `/usage-monitor`, `/recharge-manage`; toggle dark mode.

**Steps:**

- [ ] **Step 1: Migrate `frontend/src/views/user/index.vue`**

Replace colors with tokens. Icons:
- `icon-mdi-account-filter-outline` → `icon-material-symbols:filter-alt-outline-rounded`
- `icon-mdi-filter-remove-outline` → `icon-material-symbols:filter-alt-off-outline-rounded`
- `icon-mdi-magnify` → `icon-material-symbols:search-rounded`
- The SvgIcon calls (`mdi:tag-multiple-outline`, `mdi:database-plus-outline`) → use Material Symbols equivalents (the SvgIcon component's `icon` prop accepts any iconify name; just swap the string values).

- [ ] **Step 2: Migrate `frontend/src/views/user/modules/user-search.vue`**

Replace colors with tokens. Icons:
- `icon-mdi-account-filter-outline` → `icon-material-symbols:filter-alt-outline-rounded`
- `icon-mdi-magnify` → `icon-material-symbols:search-rounded`
- `icon-mdi-filter-remove-outline` → `icon-material-symbols:filter-alt-off-outline-rounded`

- [ ] **Step 3: Migrate `frontend/src/views/user/modules/org-tag-setting-dialog.vue` and `token-quota-dialog.vue`**

Light-touch. Replace colors with tokens. No icons to change.

- [ ] **Step 4: Migrate `frontend/src/views/org-tag/index.vue`**

Replace colors with tokens. SvgIcon calls (`mdi:source-branch-plus`, `mdi:pencil-outline`, `mdi:trash-can-outline`, `mdi:tag-plus-outline`) → swap to Material Symbols.

- [ ] **Step 5: Migrate `frontend/src/views/org-tag/modules/org-tag-operate-dialog.vue`**

Light-touch.

- [ ] **Step 6: Migrate `frontend/src/views/model-provider/index.vue`**

Replace colors with tokens. The local overrides for `.model-provider-card`, `.model-provider-card ::v-deep(.n-card-header)`, etc. (added in earlier commit) should be updated to use the new tokens. Specifically:
- The local `border: 1px solid #c9c1b2` references → `var(--color-border)`
- `background: #e2dccc` (header band) → `var(--color-card-band)`
- `color: #26364a` (title) → `var(--color-primary)`
- `color: #5e6470` (muted) → `var(--color-text-muted)`
- `background: #fbfaf6` (content) → `var(--color-surface)`
- `background: #fbfaf6` (note) → `var(--color-surface)`
- `border: 1px dashed #c9c1b2` (note) → `var(--color-border)`
- `box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.42)` → `var(--shadow-card)`

- [ ] **Step 7: Migrate `frontend/src/views/invite-code/index.vue`**

Same pattern as model-provider: replace local override colors with tokens. The local `.invite-code-card`, `.invite-code-card ::v-deep(.n-card-header)`, `.invite-code-note` rules should reference the new tokens.

- [ ] **Step 8: Migrate `frontend/src/views/usage-monitor/index.vue`**

Same pattern. The local `.usage-monitor-card`, `.usage-monitor-card ::v-deep(.n-card-header)`, `.usage-monitor-note` rules reference new tokens.

- [ ] **Step 9: Migrate `frontend/src/views/recharge/index.vue` and `recharge-manage/index.vue`**

Replace colors with tokens. No icons to change.

- [ ] **Step 10: Migrate `frontend/src/views/chat-history/index.vue`**

Light-touch. No icons to change.

- [ ] **Step 11: Run typecheck**

```bash
cd /home/charles/PaiSmart/frontend && pnpm typecheck
```
Expected: exit 0.

- [ ] **Step 12: Smoke test in browser**

Visit all admin pages: `/user`, `/org-tag`, `/model-provider`, `/invite-code`, `/usage-monitor`, `/recharge`, `/recharge-manage`, `/chat-history`. Toggle dark mode. Confirm everything renders correctly.

- [ ] **Step 13: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/user frontend/src/views/org-tag frontend/src/views/model-provider frontend/src/views/invite-code frontend/src/views/usage-monitor frontend/src/views/recharge frontend/src/views/recharge-manage frontend/src/views/chat-history
git commit -m "style(refactor): migrate admin pages to color tokens + Material Symbols"
```

---

## Task 5: Login / Built-in / Personal Center

**Goal:** Migrate the remaining views — login flow, error pages, iframe page, and personal center — to use color tokens and Material Symbols.

**Files:**
- Modify: `frontend/src/views/_builtin/login/index.vue`, `frontend/src/views/_builtin/login/modules/pwd-login.vue`, `frontend/src/views/_builtin/login/modules/code-login.vue`, `frontend/src/views/_builtin/login/modules/reset-pwd.vue`, `frontend/src/views/_builtin/login/modules/register.vue`, `frontend/src/views/_builtin/login/modules/bind-wechat.vue`, `frontend/src/views/_builtin/403/index.vue`, `frontend/src/views/_builtin/404/index.vue`, `frontend/src/views/_builtin/500/index.vue`, `frontend/src/views/_builtin/iframe-page/[url].vue`
- Modify: `frontend/src/views/personal-center/index.vue`

**Acceptance Criteria:**
- [ ] All Journal Ink hex values in the listed files replaced with `var(--color-*)` tokens
- [ ] All non-Material-Symbols icons in the listed files replaced per the spec's icon mapping table
- [ ] `pnpm typecheck` exits 0
- [ ] `/login`, `/404`, `/500` render correctly in both light and dark mode
- [ ] `/personal-center` renders correctly in both light and dark mode

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck` → exit 0. Then `pnpm dev` and visit `/login`, `/404`, `/personal-center`; toggle dark mode.

**Steps:**

- [ ] **Step 1: Migrate the login pages**

For each login module (`pwd-login.vue`, `code-login.vue`, `reset-pwd.vue`, `register.vue`, `bind-wechat.vue`):
- Replace colors with tokens
- Replace icons:
  - `icon-mdi-account-school-outline` → `icon-material-symbols:school-outline-rounded`
  - `icon-mdi-key-variant` → `icon-material-symbols:vpn-key-outline-rounded`
  - `icon-mdi-login-variant` → `icon-material-symbols:login-rounded`
  - `icon-mdi-ticket-confirmation-outline` → `icon-material-symbols:confirmation-number-outline-rounded`
  - `icon-mdi-account-plus-outline` → `icon-material-symbols:person-add-outline-rounded`
  - `icon-mdi-arrow-left` → `icon-material-symbols:arrow-back-rounded`
  - `icon-mdi-cellphone` → `icon-material-symbols:phone-iphone-rounded`
  - `icon-mdi-shield-key-outline` → `icon-material-symbols:vpn-key-alert-outline-rounded`
  - `icon-mdi-lock-check-outline` → `icon-material-symbols:lock-open-outline-rounded`

- [ ] **Step 2: Migrate the login index**

Light-touch. Replace colors with tokens.

- [ ] **Step 3: Migrate the 403, 404, 500 pages**

These use the `exception-base.vue` component (already migrated in Task 2). They may have minor color overrides. Replace hardcoded colors with tokens.

- [ ] **Step 4: Migrate the iframe page**

Light-touch. No custom colors expected.

- [ ] **Step 5: Migrate `frontend/src/views/personal-center/index.vue`**

Replace colors with tokens. No icons to change.

- [ ] **Step 6: Run typecheck**

```bash
cd /home/charles/PaiSmart/frontend && pnpm typecheck
```
Expected: exit 0.

- [ ] **Step 7: Smoke test in browser**

Visit `/login`, `/404`, `/personal-center`. Toggle dark mode. Confirm rendering.

- [ ] **Step 8: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/_builtin frontend/src/views/personal-center
git commit -m "style(refactor): migrate login + built-in + personal center to tokens + Material Symbols"
```

---

## Task 6: Final Sweep + Verification

**Goal:** Catch any remaining stragglers (hooks, stores, etc.), run final verification, and confirm the entire refactor is consistent.

**Files:** (depends on what grep finds)

**Acceptance Criteria:**
- [ ] `grep -rE "icon-mdi-|icon-ph:|icon-heroicons:|icon-ant-design:" frontend/src --include="*.vue" --include="*.ts" --include="*.tsx"` returns no results in `.vue`/`.ts`/`.tsx` source files (iconify icon configurations in the route config may still use mixed families for non-iconify legacy reasons — check and document any that remain)
- [ ] `grep -rE "#(fbfaf6|e2dccc|c9c1b2|26364a|7e3f46|5e6470|20242a|f1ebd9|ddd3bf)" frontend/src --include="*.vue" --include="*.scss" --include="*.css"` returns no results (or only the documented exceptions, like the chat shell's grid background)
- [ ] `pnpm typecheck` exits 0
- [ ] `pnpm build` (or at minimum `pnpm lint`) succeeds without errors
- [ ] Browser smoke test of all key pages in both light and dark mode
- [ ] User confirms the refactor looks good

**Verify:** User visual sign-off after browser walkthrough.

**Steps:**

- [ ] **Step 1: Sweep for remaining non-Material-Symbols icon calls**

```bash
cd /home/charles/PaiSmart
grep -rE "icon-mdi-|icon-ph:|icon-heroicons:|icon-ant-design:" frontend/src --include="*.vue" --include="*.ts" --include="*.tsx"
```

Expected: no output. If any remain, fix them in this task.

Note: the route config (`frontend/src/router/elegant/routes.ts`) may use icons like `mdi:comment-question-outline`. These are stored as strings in the route meta and rendered via SvgIcon. If they remain, either update them to Material Symbols equivalents, or note the exception in the commit message.

- [ ] **Step 2: Sweep for remaining hardcoded Journal Ink hex values**

```bash
cd /home/charles/PaiSmart
grep -rE "#(fbfaf6|e2dccc|c9c1b2|26364a|7e3f46|5e6470|20242a|f1ebd9|ddd3bf)" frontend/src --include="*.vue" --include="*.scss" --include="*.css"
```

Expected: no output. If any remain, fix them in this task. Document any exceptions (e.g., the chat shell's complex grid background pattern that can't be expressed as a single token).

- [ ] **Step 3: Run typecheck and build**

```bash
cd /home/charles/PaiSmart/frontend
pnpm typecheck
```
Expected: exit 0.

```bash
cd /home/charles/PaiSmart/frontend
pnpm build
```
Expected: build succeeds. (If the build has unrelated errors, log them and proceed; they may be pre-existing.)

- [ ] **Step 4: Run lint**

```bash
cd /home/charles/PaiSmart/frontend
pnpm lint
```
Expected: no new errors introduced by this refactor.

- [ ] **Step 5: Browser walkthrough — light mode**

```bash
cd /home/charles/PaiSmart/frontend
pnpm dev
```

Visit each page and confirm the new academic blue + burnt orange palette looks harmonious:
- `http://localhost:9527/#/login` — login form
- `http://localhost:9527/#/knowledge-base` — paper library table
- `http://localhost:9527/#/chat` — chat interface (sidebar, message list, input box)
- `http://localhost:9527/#/user` — user management
- `http://localhost:9527/#/model-provider` — model provider
- `http://localhost:9527/#/invite-code` — invite code
- `http://localhost:9527/#/usage-monitor` — usage monitor
- `http://localhost:9527/#/recharge` — recharge
- `http://localhost:9527/#/personal-center` — personal center
- `http://localhost:9527/#/404` — 404 page

- [ ] **Step 6: Browser walkthrough — dark mode**

For each page above, toggle dark mode via the theme drawer. Confirm dark mode renders correctly.

- [ ] **Step 7: User verification checkpoint**

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:

```yaml
AskUserQuestion:
  question: "整个项目的配色和 icon 统一重构完成。在亮色/暗色模式下浏览各页(knowledge-base / chat / model-provider / invite-code / user / login 等)后,整体效果如何?"
  header: "Verification"
  options:
    - label: "整体好看,可以收尾"
      description: "学术蓝 + 焦橙 + Material Symbols 符合预期,亮/暗模式都 OK"
    - label: "需要调整"
      description: "某些页面/某些颜色/某些 icon 还需要调,告诉我哪里"
```

- [ ] **Step 8: Final commit (if any cleanup was needed in steps 1-2)**

```bash
cd /home/charles/PaiSmart
git add frontend/src
git commit -m "style(refactor): final sweep - clean up remaining hardcoded colors and mixed icon families"
```

If no cleanup was needed, skip this commit.
