# Global Color and Icon Refactor Design

## Goal

Refactor PaperLoom's color palette and icon family across the entire frontend so the system is consistent, maintainable, and aligned with academic-conference branding (NeurIPS / ACL-style deep academic blue + burnt orange accent). Replace the existing "Journal Ink" hardcoded palette with a semantic CSS-variable token system, and consolidate the 5 mixed icon families (MDI, Material Symbols, Phosphor, Heroicons, Ant Design) into a single Material Symbols set. **No layout changes, no font changes, no logic changes.**

## Direction

### Color

Move from hardcoded hex values scattered across ~70 SCSS/components to a single semantic token system. The new palette is inspired by academic-conference branding (NeurIPS / ACL): a deep academic blue (`#1e3a5f`) as the primary, burnt orange (`#c2410c`) as the citation/CTA accent, and paper-cream backgrounds (`#faf7f2`) for readability of long-form text. Semantic tokens (`--color-bg`, `--color-primary`, `--color-accent`, etc.) are defined once in a tokens CSS file, and every component is migrated to reference tokens. The dark mode (`.dark`) reuses the same token names with inverted values, so adding new components doesn't require separately styling both modes.

### Icon

Consolidate to a single icon family — Material Symbols (the @iconify/vue prefix `icon-material-symbols:*`). The project currently uses 5 families through the @iconify/vue library; while they all render via iconify, the visual styles are inconsistent (mdi:close is a thin X, ant-design:close-outlined is a heavier one, material-symbols:close is yet another weight). Material Symbols also provides variable font controls (fill, weight, optical size) that better match the academic feel. Every icon call site is migrated. Default style is `-outline-rounded` (light, scholarly); high-contrast contexts (status, primary CTAs) use `-rounded` (solid).

### What stays the same

- All layout (flex, grid, responsive breakpoints)
- All fonts (Georgia for display, monospace for UI labels, sans-serif for body)
- All component behavior, API calls, state management
- All routes and route config

## Scope

### Files

The refactor touches all frontend SCSS + Vue files that reference color or icons. Grouped into 6 commits (see "Migration Plan" below). No new dependencies, no dependency removals. `@iconify/vue` stays (it powers Material Symbols).

### Color Token System

**Base palette (light mode):**
```
primary-700:  #1e3a5f   /* 主蓝 — 标题/重点 */
primary-500:  #2a508a   /* 链接/选中态 */
primary-50:   #e8eef5   /* 主蓝淡背景 */

accent-700:   #c2410c   /* 焦橙 — 引用/CTA */
accent-500:   #ea580c   /* hover/active */
accent-50:    #fdf0e3   /* 强调淡背景 */

paper:        #faf7f2   /* 页面背景 */
paper-soft:   #f3ede0   /* 卡片头部 band, hover */
paper-strong: #e7dcc4   /* pressed 态 */

ink:          #1a1a2e   /* 主文本 */
ink-muted:    #5b6378   /* 次要文本 */
muted:        #64748b   /* 占位/灰阶 */
line:         #c9c1b2   /* 边框/分隔线 */
line-soft:    #ddd3bf   /* 更柔的分隔线 */

surface:      #ffffff   /* 纯白表面, 弹窗 */
surface-alt:  #faf6e8   /* 表格行 hover */

success-600:  #166534
warning-600:  #b45309
error-600:    #b91c1c
```

**Dark mode:**
```
bg:           #0f1019
surface:      #1a1c2e
card-band:    #1a1c2e
card-band-pressed: #232638
text:         #f3ede0
text-muted:   #a8b0c0
primary:      #6c8ec0
primary-hover: #8ba8d4
accent:       #f08a4b
accent-hover: #f5a673
border:       #2a2d40
border-soft:  #1f2230
shadow-card:  4px 4px 0 rgba(0, 0, 0, 0.4)
shadow-card-soft: 2px 2px 0 rgba(0, 0, 0, 0.32)
```

**Semantic tokens (declared in a new `frontend/src/styles/css/tokens.css`):**
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

**Old → new mapping (for the migration):**
| Old (Journal Ink) | New token | Where used |
|---|---|---|
| `#fbfaf6` (paper) | `--color-bg` / `--color-surface` | page bg, card surface |
| `#e2dccc` (header band) | `--color-card-band` | NCard header band |
| `#c9c1b2` (binding line) | `--color-border` | borders, dividers |
| `#26364a` (title) | `--color-text` + `--color-primary` | titles, brand text |
| `#7e3f46` (wine accent) | `--color-accent` | emphasis, citations |
| `#5e6470` (muted) | `--color-text-muted` | secondary text |
| `#20242a` (ink) | `--color-text` | body text |
| `#f1ebd9` (row hover) | `--color-surface-alt` | row hover, soft surfaces |
| `#ddd3bf` (mobile border) | `--color-border-soft` | soft dividers |

### Icon Mapping

All icons standardized to `icon-material-symbols:*` with default `-outline-rounded` style. High-contrast contexts (status indicators, primary CTAs) use `-rounded` (solid). Full mapping:

| Old | New |
|---|---|
| `mdi:upload-outline` | `material-symbols:upload-rounded` |
| `mdi:trash-can-outline` | `material-symbols:delete-outline-rounded` |
| `mdi:magnify` | `material-symbols:search-rounded` |
| `mdi:close` | `material-symbols:close-rounded` |
| `mdi:bookshelf` | `material-symbols:menu-book-outline-rounded` |
| `mdi:account-filter-outline` | `material-symbols:filter-alt-outline-rounded` |
| `mdi:cellphone` | `material-symbols:phone-iphone-rounded` |
| `mdi:account-school-outline` | `material-symbols:school-outline-rounded` |
| `mdi:key-variant` | `material-symbols:vpn-key-outline-rounded` |
| `mdi:login-variant` | `material-symbols:login-rounded` |
| `mdi:file-eye-outline` | `material-symbols:visibility-outline-rounded` |
| `mdi:archive-outline-rounded` | `material-symbols:archive-outline-rounded` |
| `mdi:logout-rounded` | `material-symbols:logout-rounded` |
| `mdi:chevron-left/right` | `material-symbols:chevron-left/right-rounded` |
| `mdi:drag` | `material-symbols:drag-indicator-rounded` |
| `mdi:refresh` | `material-symbols:refresh-rounded` |
| `mdi:arrow-left` | `material-symbols:arrow-back-rounded` |
| `mdi:alert-circle` | `material-symbols:error-outline-rounded` |
| `mdi:open-in-new` | `material-symbols:open-in-new-rounded` |
| `mdi:download` | `material-symbols:download-rounded` |
| `mdi:pencil-outline` | `material-symbols:edit-outline-rounded` |
| `mdi:tag-plus-outline` | `material-symbols:label-outline-rounded` |
| `mdi:tag-multiple-outline` | `material-symbols:sell-outline-rounded` |
| `mdi:database-plus-outline` | `material-symbols:database-outline-rounded` |
| `mdi:source-branch-plus` | `material-symbols:hub-outline-rounded` |
| `mdi:thumb-up/down-outline-rounded` | `material-symbols:thumb-up/down-outline-rounded` |
| `mdi:check-circle-rounded` | `material-symbols:check-circle-rounded` |
| `mdi:error-rounded` | `material-symbols:error-rounded` |
| `mdi:add-rounded` | `material-symbols:add-rounded` |
| `mdi:chat-outline-rounded` | `material-symbols:chat-bubble-outline-rounded` |
| `mdi:left-panel-open/close-outline-rounded` | `material-symbols:left-panel-open/close-outline-rounded` |
| `mdi:article-outline-rounded` | `material-symbols:article-outline-rounded` |
| `mdi:filter-remove-outline` | `material-symbols:filter-alt-off-outline-rounded` |
| `mdi:keyboard-return` | `material-symbols:keyboard-return-rounded` |
| `mdi:arrow-up/down-thin` | `material-symbols:keyboard-arrow-up/down-rounded` |
| `mdi:keyboard-esc` | `material-symbols:keyboard-esc-rounded` |
| `mdi:lock-check-outline` | `material-symbols:lock-open-outline-rounded` |
| `mdi:shield-key-outline` | `material-symbols:vpn-key-alert-outline-rounded` |
| `mdi:ticket-confirmation-outline` | `material-symbols:confirmation-number-outline-rounded` |
| `mdi:account-plus-outline` | `material-symbols:person-add-outline-rounded` |
| `mdi:format-horizontal-align-left/right` | `material-symbols:format-align-left/right-rounded` |
| `mdi:file-search-outline` | `material-symbols:search-rounded` |
| `mdi:stop-rounded` | `material-symbols:stop-rounded` |
| `mdi:arrow-upward-rounded` | `material-symbols:arrow-upward-rounded` |
| `mdi:magnify-minus/plus-outline` | `material-symbols:zoom-out/in-rounded` |
| `mdi:robot-outline` | `material-symbols:smart-toy-outline-rounded` |
| `ph:user-circle` | `material-symbols:account-circle-outline-rounded` |
| `ph:sign-out` | `material-symbols:logout-rounded` |
| `heroicons:language` | `material-symbols:language-rounded` |
| `ant-design:close-outlined` | `material-symbols:close-rounded` |
| `ant-design:column-width-outlined` | `material-symbols:width-rounded` |
| `ant-design:line-outlined` | `material-symbols:horizontal-rule-rounded` |

Any icon not on this list will be mapped to the closest Material Symbols equivalent during implementation.

## Migration Plan (6 commits)

### Commit 1: Foundation — Token System
- Create `frontend/src/styles/css/tokens.css` with all `--color-*` variables (light + dark)
- Import `tokens.css` in `frontend/src/styles/css/index.css` (or whatever the global styles entry is)
- Update `global.css` `.admin-console-card`, `.admin-console-note`, etc. to use tokens
- Verify: `pnpm typecheck` exit 0 + dev server boots without errors

### Commit 2: Layouts + Global Components
- `frontend/src/layouts/**` (base-layout, global-header, global-sider, global-tab, global-menu, global-content, theme-drawer, global-search, global-footer, global-breadcrumb, global-logo)
- `frontend/src/components/custom/**` (svg-icon, file-preview, pdf-document-viewer, button-icon, look-forward, pin-toggler, exception-base)
- `frontend/src/components/advanced/**` (table-column-setting, table-header-operation)
- `frontend/src/components/common/**` (menu-toggler, lang-switch)

### Commit 3: Knowledge Base + Chat (Core Business Pages)
- `frontend/src/views/knowledge-base/**` (index, upload-dialog, search-dialog)
- `frontend/src/views/chat/**` (index, chat-list, chat-message, input-box, conversation-sidebar, reference-preview-page)

### Commit 4: Admin Pages
- `frontend/src/views/user/**` (index, user-search, org-tag-setting-dialog, token-quota-dialog)
- `frontend/src/views/org-tag/**` (index, org-tag-operate-dialog)
- `frontend/src/views/model-provider/**` (index)
- `frontend/src/views/invite-code/**` (index)
- `frontend/src/views/usage-monitor/**` (index)
- `frontend/src/views/recharge/**`, `recharge-manage/**`, `chat-history/**`

### Commit 5: Login / Built-in / Personal Center
- `frontend/src/views/_builtin/**` (login, 403, 404, 500, iframe-page)
- `frontend/src/views/personal-center/**`

### Commit 6: Final Sweep + Verification
- Catch any remaining stragglers (hooks, stores, types, etc.)
- Run `pnpm typecheck` → exit 0
- Run `pnpm build` (or at least lint) to catch any unresolved icon name or token reference
- Browser smoke test: knowledge-base / chat / model-provider / invite-code / usage-monitor / user / login
- Toggle dark mode in each page, confirm both modes render correctly
- Final commit with any cleanup

## Out of Scope

- Backend (Java) styling or theming
- RAG pipeline, API contracts, business logic
- Component layout (flex/grid/responsive breakpoints)
- Font families (Georgia, monospace, sans-serif all stay)
- Routes, navigation flow, page metadata
- Dependency changes (no new packages, no removals)
- i18n message keys (no new translations)

## Validation

- `cd frontend && pnpm typecheck` → exit 0
- `pnpm build` (or `pnpm lint`) → no errors
- Browser walkthrough (light mode): knowledge-base, chat, model-provider, invite-code, usage-monitor, user, login, 404
- Browser walkthrough (dark mode): same pages
- Icon-only check: no `mdi:`, `ph:`, `heroicons:`, or `ant-design:` icon references remain in `.vue` files
- Color check: no hardcoded `#fbfaf6`, `#e2dccc`, `#c9c1b2`, `#26364a`, `#7e3f46`, `#5e6470` hex values remain (all routed through tokens; exception allowed only for non-color literals like `transparent` or shadow rgba)

## Risk Notes

- The chat page (`frontend/src/views/chat/index.vue` and modules) has many custom CSS variables (`--paper-accent`, `--paper-line`, etc.) that are local to the chat shell. These will be migrated to global tokens where possible, but some local `--paper-*` variables may be retained for the chat's internal grid background pattern.
- PDF viewer (`pdf-document-viewer.vue`) is a complex component with many internal styles. Migration will be limited to color tokens; PDF rendering logic is untouched.
- Dark mode in chat and a few other places uses semi-transparent overlays. After token migration, the alpha values may need to be tuned (e.g., `rgba(38, 54, 74, 0.2)` → a token-based equivalent).
- Some ad-hoc `text-stone-*` Tailwind utilities may remain if they don't have a clear token mapping; will be cleaned up opportunistically but not blocking.