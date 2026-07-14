# Frontend Visual System and Responsive Workbench Proposal

- **Status:** Implemented
- **Date:** 2026-07-13
- **Scope:** `frontend/`, with priority on chat, evidence review, the paper library, login, and shared visual tokens
- **Product subject:** Folio, an evidence-grounded research paper workbench
- **Primary audience:** Researchers and engineers who need to move from a question to an auditable answer quickly
- **Primary job:** Make paper discovery, reading, citation inspection, and evidence verification feel like one coherent workflow

## 1. Decision

Evolve the current frontend into a **quiet research instrument** rather than applying a decorative reskin.

The current interface already has a useful restrained foundation: low radii, subdued borders, compact controls,
and an emerging citation color. The redesign should preserve that discipline while improving four weak areas:

1. responsive correctness, especially the paper library on narrow viewports;
2. visual hierarchy and reading comfort in chat;
3. a recognizable Folio identity centered on evidence, not generic AI styling;
4. consistent language, typography, controls, and status semantics across product surfaces.

The distinctive interaction will be an **evidence spine** that connects claims and citations in an answer to the
research process and source-evidence panel. This is a product behavior expressed visually, not decoration.

## 2. Current-State Findings

The proposal is based on the current light-theme build inspected at `1440x1000` and `390x844`, using the live
chat and paper-library routes.

### 2.1 What should be retained

- The overall product density is appropriate for a research tool.
- The monochrome base keeps attention on content.
- The woven Folio mark is memorable enough to anchor a product identity.
- Citation tokens already distinguish evidence from general interaction.
- Naive UI, Lucide icons, and the existing CSS token layer provide enough primitives; a new component library is
  unnecessary.
- The chat already models research progress, persisted references, source quotes, and an evidence review panel.
  The redesign can reveal those capabilities instead of inventing new product behavior.

### 2.2 Problems to solve

#### Responsive correctness

The paper library is not usable at phone width. Its sidebar remains expanded, the data table keeps
`:scroll-x="1280"`, and the mobile CSS only rearranges the toolbar and summary grid. At `390px`, the remaining
content area becomes so narrow that headings wrap one character per line.

This is the first priority. A broken narrow layout cannot be fixed by changing color or typography.

#### Weak product identity in light mode

The light theme is almost entirely white, gray, and black. The dark theme has a stronger teal character, while
the light theme reads as a generic internal console. The citation amber exists but appears only at the point of
use and does not yet organize the overall evidence workflow.

#### Chat reads as a stack of forms

Assistant responses are placed inside bordered cards, followed by a divider and a permanently visible action
row. User messages sit at the far right of a wide canvas. The result is clean but spatially disconnected, with
large dead zones on desktop and repeated borders around long-form reading content.

#### Library hierarchy is noisy

The library gives similar visual weight to filenames, token counts, asset pills, status pills, scope pills,
dates, and a large action group. Mixed labels such as `Paper / 文件` and `Index Budget / 索引` add noise without
serving either locale well. At desktop widths, the fixed 1280-pixel table also pushes actions out of view.

#### Typography has no domain role

System sans-serif is used for navigation, paper titles, evidence passages, metrics, and long answers. The UI is
legible, but it does not distinguish operating the application from reading scholarly material.

## 3. Design Direction

### 3.1 Visual thesis

Folio should feel like a precise desk for tracing a statement back to a paper: quiet surfaces, strong reading
rhythm, compact controls, visible provenance, and restrained color that means something.

It should not resemble:

- a marketing landing page;
- a card-heavy SaaS dashboard;
- a generic chatbot with colored message bubbles;
- a cream editorial site with ornamental serif headlines;
- a black interface with a neon accent;
- a decorative scientific grid or animated data visualization.

The current welcome-screen grid should be reduced or removed once the evidence spine supplies the product's
signature. Two signatures would compete.

### 3.2 Palette

The light palette uses a cool paper tone rather than beige. Ink remains dominant; teal is reserved for active
research and verified states; amber is reserved for citations and source focus.

| Token | Light | Dark | Role |
| --- | --- | --- | --- |
| Canvas | `#F6F8F7` | `#111716` | App background |
| Surface | `#FFFFFF` | `#18201E` | Reading and control surfaces |
| Ink | `#202522` | `#E7ECE9` | Primary text and primary commands |
| Muted | `#6E7873` | `#9BA7A1` | Metadata and secondary controls |
| Research teal | `#16786E` | `#77C6BA` | Active navigation, focus, verified research state |
| Citation amber | `#A86414` | `#E4B765` | Citation markers and selected evidence |

Borders, soft backgrounds, hover states, and focus rings should be derived from these tokens. Success, warning,
and error keep their semantic roles and must not be replaced by teal or amber.

Color rules:

- Ink is still the primary-action color for commands such as **New Query** and **Upload**.
- Teal means active research context, selected navigation, connected state, or verified readiness.
- Amber only means citation, source quote, or evidence focus.
- Neutral metadata must remain neutral; do not turn every tag into a colored pill.
- Dark mode must preserve the same semantic mapping rather than becoming a separate visual concept.

### 3.3 Typography

Use three explicit roles without loading a large CJK font payload:

| Role | Stack | Usage |
| --- | --- | --- |
| Interface | system sans, `PingFang SC`, `Microsoft YaHei`, sans-serif | Navigation, controls, labels, chat chrome |
| Reading | `Literata Variable`, `Noto Serif SC`, system serif | Paper titles, quoted evidence, optional long-form answer headings |
| Utility | `ui-monospace`, `SFMono-Regular`, `Consolas`, monospace | Paper IDs, token counts, location references, parser metadata |

Only a small self-hosted Latin subset of Literata should be added, with `font-display: swap`; Chinese continues
to use the platform serif fallback. The redesign must add no JavaScript font dependency and must not cause
visible layout shift.

Recommended scale:

| Role | Size / line height | Weight |
| --- | --- | --- |
| Page title | `26px / 32px` | 600 |
| Section title | `18px / 26px` | 650 |
| Reading body | `16px / 29px` | 400 |
| UI body | `14px / 21px` | 400-550 |
| Metadata | `12px / 18px` | 450-600 |
| Utility data | `11px / 16px` | 550 |

Do not scale font sizes with viewport width. Mobile uses the same semantic scale with smaller page-title and
welcome-title tokens selected at fixed breakpoints.

### 3.4 Geometry and elevation

- Spacing scale: `4, 8, 12, 16, 24, 32, 48`.
- Icon buttons: stable `36x36` desktop and at least `40x40` touch size on mobile.
- Controls: `6px` radius.
- Tool panels and modals: `8px` radius.
- User message bubble: `8px` radius.
- Avoid pill shapes except tags, status, citations, and segmented controls.
- Use borders for structure and shadows only for overlays, floating composers, and selected evidence.
- Do not place cards inside cards. Long-form assistant answers should be unframed.

### 3.5 Signature: the evidence spine

The evidence spine is a narrow rail associated with each assistant answer:

```text
Assistant answer text                         Evidence spine
-------------------------------------------------------------
Claim about the method [1]                    01  p.4  Method
Comparison result [2][3]                      02  p.7  Results
Uncertain conclusion                          !   gap detected
```

Behavior:

1. Citation numbers in answer text remain keyboard-operable buttons.
2. Hovering or focusing a citation highlights the matching spine item.
3. Selecting either representation opens and focuses the evidence panel.
4. The selected citation uses amber; verified source availability uses teal; unavailable evidence remains
   neutral or uses the existing warning/error semantics.
5. On narrow screens, the spine becomes a compact **Sources** row below the answer and opens a bottom sheet.
6. Research-process events remain a separate mode in the evidence panel; they are not mixed into citation
   markers.

This interaction reuses the current `referenceMappings`, source-quote contract, and review drawer. It must not
create a second evidence data model.

## 4. Layout Proposals

### 4.1 Chat desktop

```text
+------------------+---------------------------------------------------------------+
| Folio            | Session title     Source set                      status  review|
| Paper Library    +---------------------------------------------------------------+
| + New Query      |                                                               |
| Search chats     |     reading column 760-820px          evidence spine           |
|                  |     user prompt                                            [01]|
| Recent queries   |     assistant answer and citations                       [02]|
|                  |     contextual actions on hover/focus                         |
|                  |                                                               |
| Account          |     source scope + composer                                   |
+------------------+---------------------------------------------------------------+
```

Rules:

- Keep the sidebar at approximately `272px`, but allow collapse on medium widths.
- Limit the primary reading column to `820px`; do not stretch prose to fill the workspace.
- Keep user and assistant content inside the same reading coordinate system rather than opposite viewport
  edges.
- Remove the assistant-response border and background. Use spacing and a subtle bottom rule between turns.
- Keep the assistant mark in a fixed gutter; it must not reduce the reading width on mobile.
- Show message actions on hover or `:focus-within` for pointer devices. Keep them visible on touch devices and
  preserve accessible labels.
- Replace the generic `Folio` topbar title with current-session context and source-set context. Folio remains in
  the sidebar brand.

### 4.2 Chat with evidence review

```text
+----------+--------------------------------------+---------------------------+
| sidebar  | answer + active citation             | Evidence / Process tabs   |
|          |                                      | paper, page, section      |
|          | highlighted claim [2]  <-----------> | quoted passage            |
|          |                                      | page or asset preview     |
+----------+--------------------------------------+---------------------------+
```

- At `>= 1200px`, use a resizable or bounded split panel between `420px` and `620px`.
- At `768-1199px`, use an overlay drawer that does not permanently compress the answer.
- Below `768px`, use a full-width bottom sheet with an explicit drag handle and close button.
- Preserve the existing **Process** and **Source Evidence** tabs as a segmented control.
- The active citation should remain visible when the panel opens; avoid resetting scroll position unnecessarily.

### 4.3 Chat empty state

```text
                  What should we read?
        [ Ask about a paper, claim, table, or citation ]
        Source set: All readable papers          [send]

        Recent prompts or saved paper sets, when available
```

- Keep the empty state as the actual working experience, not a marketing hero.
- Reduce the title from the current maximum `72px` to a fixed `44px` desktop and `32px` mobile.
- Remove the decorative grid or reduce it to a barely visible local texture behind the input only.
- Present recent prompts or source sets only when real data exists; do not add explanatory feature copy.

### 4.4 Paper library desktop

```text
+----------+-----------------------------------------------------------------------+
| sidebar  | Library                      [Papers | Paper Sets] [search] [upload]   |
|          | 77 documents   20 searchable   0 processing   0 failed   963K tokens  |
|          +-----------------------------------------------------------------------+
|          | Paper                 Index          Status      Scope    Updated   ... |
|          | title + id + size     EST / ACT     Searchable  Private  date      ... |
|          |                       Assets 12 >                                     |
+----------+-----------------------------------------------------------------------+
```

Rules:

- Fit the default column set within the available desktop content width rather than forcing a 1280-pixel table
  into a narrower area.
- Make the paper column sticky when horizontal scrolling is genuinely necessary.
- Keep one primary status badge per row. Asset readiness becomes a compact disclosure such as
  `Assets 12` rather than four or more equal pills.
- Move secondary actions to a row overflow menu. Keep **Preview** as the direct action.
- Hide low-priority columns at medium widths in this order: size, imported time, scope detail. Their values remain
  available in row expansion or the overflow menu.
- Use localized labels, never slash-separated bilingual labels.

### 4.5 Paper library mobile

The mobile library must not render a compressed desktop table.

```text
+--------------------------------------+
| [menu] Library        [Paper Sets]   |
| [ Search papers................ ]    |
| 77 documents  20 searchable  0 failed|
|                                      |
| 2308.03688.pdf                  [...]|
| Searchable     Private               |
| EST 57.9K  ACT 76.9K                 |
| PDF ready  Parser saved  Assets 117  |
| Imported 2026-07-09          Preview |
+--------------------------------------+
```

- Collapse the conversation sidebar by default below `960px`, matching chat behavior.
- Replace the data table with a purpose-built paper list below `768px`.
- Use a single-column toolbar; the primary search input occupies the full width.
- Put secondary search, column settings, and refresh actions in an icon menu.
- Keep upload as a clear icon-plus-text command.
- Maintain a stable card/list geometry so long filenames, statuses, and loading states cannot shift controls.

### 4.6 Login, settings, and administration

- Keep login compact and task-focused. Use the woven mark, cool canvas, and the revised typography; do not add a
  split illustration or marketing copy.
- Remove `[auth]`, which reads as an implementation label rather than user-facing navigation.
- Use the same active-nav, segmented-control, form, and status treatments in settings.
- Keep administration dense and utilitarian. It should inherit tokens and controls but not the reading serif or
  evidence spine.

## 5. Responsive Contract

| Width | Navigation | Chat evidence | Library representation |
| --- | --- | --- | --- |
| `>= 1280px` | Persistent sidebar | Split panel | Full table |
| `960-1279px` | Collapsible sidebar | Overlay drawer | Reduced-column table |
| `768-959px` | Overlay sidebar | Overlay drawer | Reduced-column table or wide list |
| `< 768px` | Closed overlay sidebar | Bottom sheet | Paper list cards |

Viewport rules:

- No document-level horizontal scrolling at `360`, `390`, `768`, `1024`, or `1440` pixels.
- Text must never wrap one character per line because of a squeezed column.
- Fixed composers and bottom sheets must account for mobile safe areas.
- Opening the sidebar, evidence sheet, or settings must trap focus appropriately and restore it when closed.
- Pointer-only hover behavior must have an equivalent touch and keyboard state.

## 6. Language and Content Rules

- Move remaining hard-coded UI strings into `vue-i18n`.
- Render one locale at a time. Replace `Paper / 文件` with either `Paper` or `文件`, based on the active locale.
- Keep action names stable across controls, notifications, and completion messages.
- Prefer user concepts: **Sources**, **Paper set**, **Evidence**, **Research process**, **Preview**.
- Avoid implementation terms in primary UI, including parser JSON, chunk IDs, and model internals. These remain in
  admin or expandable diagnostic surfaces.
- Empty and error states must state the next action, not merely report absence.

## 7. Implementation Plan

The work should be delivered in bounded stages. Responsive correctness lands before visual signature work so
later screenshots are not built on a broken layout.

### Stage 0: Deterministic visual baseline

Add a mocked Playwright visual fixture with stable paper, message, citation, process, and evidence data.

Create:

- `frontend/tests/e2e/frontend-visual-regression.spec.ts`
- light and dark snapshots at `1440x1000`;
- light snapshots at `1024x768`, `768x1024`, and `390x844`;
- states for login, empty chat, populated chat, open evidence, library, and library mobile list.

The test must mock network responses and disable nondeterministic animation. It must not depend on the live
backend or personal conversation data.

### Stage 1: Responsive correctness

Primary files:

- `frontend/src/views/knowledge-base/index.vue`
- `frontend/src/views/chat/index.vue`
- `frontend/src/views/chat/modules/input-box.vue`
- `frontend/src/views/chat/modules/conversation-sidebar.vue`

Changes:

1. Apply the same viewport-driven sidebar behavior to library and chat.
2. Extract a shared paper row view model so desktop table and mobile list do not duplicate status logic.
3. Add a mobile paper-list component below `768px`.
4. Reduce desktop table column width and move secondary actions to overflow.
5. Add safe-area and fixed-composer spacing on mobile chat.
6. Convert the evidence panel to split, drawer, or bottom-sheet behavior by breakpoint.

This stage should not change the palette or typography beyond what is required for legibility.

### Stage 2: Shared visual foundation

Primary files:

- `frontend/src/styles/css/tokens.css`
- `frontend/src/styles/css/global.css`
- `frontend/src/theme/settings.ts`
- a new `frontend/src/styles/css/typography.css`, if the font role cannot remain concise in `global.css`

Changes:

1. Introduce the revised light and dark semantic tokens.
2. Add interface, reading, and utility font roles.
3. Define shared focus, elevation, control-height, radius, and spacing tokens.
4. Align Naive UI theme overrides with those tokens instead of per-view color overrides.
5. Add reduced-motion rules for page transitions, citation focus, drawers, and loading states.

Do not introduce a new npm dependency. A self-hosted Latin font file is acceptable only within the agreed font
budget and after a layout-shift check.

### Stage 3: Chat and evidence spine

Primary files:

- `frontend/src/views/chat/modules/chat-list.vue`
- `frontend/src/views/chat/modules/chat-message.vue`
- `frontend/src/views/chat/modules/source-evidence-panel.vue`
- `frontend/src/views/chat/modules/research-process-panel.vue`
- `frontend/src/views/chat/modules/input-box.vue`
- `frontend/src/views/chat/index.vue`

Changes:

1. Move turns into a common reading column.
2. Remove the assistant answer card treatment.
3. Add an evidence-spine presentation derived from existing reference mappings.
4. Synchronize hover, focus, selection, and panel state between citations and evidence items.
5. Make actions contextual on pointer devices and persistently accessible on touch.
6. Clarify source-set selection and reference focus in the composer.
7. Replace the topbar product label with session and source context.

Preserve current event names, request payloads, durable `sourceQuoteRef` behavior, and selectors used by behavioral
tests unless changing a selector is explicitly part of the test migration.

### Stage 4: Library hierarchy

Primary files:

- `frontend/src/views/knowledge-base/index.vue`
- `frontend/src/views/knowledge-base/modules/collections-panel.vue`
- new focused library components only where they reduce the current oversized view file

Recommended extractions:

- `library-toolbar.vue`
- `library-summary.vue`
- `paper-mobile-list.vue`

Changes:

1. Localize all headings and actions.
2. Reduce visible columns and pills.
3. Introduce asset disclosure and an action overflow menu.
4. Apply reading and utility typography to paper titles and IDs.
5. Align Papers and Paper Sets as a stable segmented view switch.

Avoid creating a generic table abstraction. The mobile list and asset disclosure are specific to paper-library
workflows.

### Stage 5: Peripheral consistency and hardening

Apply the shared system to:

- login and registration;
- settings;
- admin management pages;
- recharge and personal-center surfaces;
- loading, empty, offline, reconnecting, and error states.

Run visual review in light and dark mode and remove redundant view-local tokens after behavior is stable.

## 8. Test and Verification Plan

### Automated checks

- `pnpm typecheck`
- focused ESLint on changed files, then `pnpm lint` before completion
- existing component and contract tests under `frontend/tests/`
- existing chat and library Playwright specifications
- new deterministic screenshot tests

### Interaction coverage

- open and close sidebar with pointer, keyboard, and touch-sized viewport;
- create and switch conversations;
- use empty-state and docked composers;
- focus a citation, open matching evidence, switch to process, and return focus;
- search the library, reset search, switch Paper/Paper Sets, upload, preview, and open overflow actions;
- operate mobile paper cards without horizontal scrolling;
- verify offline, connecting, reconnecting, empty, processing, failed, private, and public states;
- verify reduced-motion mode.

### Visual acceptance matrix

| Surface | Light desktop | Dark desktop | Tablet | Mobile |
| --- | ---: | ---: | ---: | ---: |
| Login | Required | Required | Required | Required |
| Empty chat | Required | Required | Required | Required |
| Populated chat | Required | Required | Required | Required |
| Evidence open | Required | Required | Required | Required |
| Library papers | Required | Required | Required | Required |
| Paper sets | Required | Required | Required | Required |
| Settings | Required | Required | Optional | Required |

### Accessibility acceptance

- WCAG AA contrast for text, controls, citation markers, focus rings, and status badges.
- Visible `:focus-visible` treatment on every interactive element.
- Citation and evidence synchronization works without a mouse.
- Touch targets are at least `40x40`, with `44x44` used where the layout permits.
- Dialog, drawer, and bottom-sheet focus is trapped and restored.
- Meaning is never conveyed by color alone.

### Performance guardrails

- No new runtime UI dependency.
- No increase to the existing JavaScript bundle budget caused by the redesign.
- Any new Latin font asset should remain below `80KB` compressed and use `font-display: swap`.
- Mobile library must render only the representation in use; do not mount a full hidden data table beneath cards.
- Avoid animating layout properties. Restrict motion to opacity and transform.

## 9. Acceptance Criteria

The proposal is complete when all of the following are true:

1. The library has no document-level horizontal overflow at the target viewports.
2. The library sidebar is closed by default below `960px` and opens as an overlay.
3. The library uses a dedicated mobile paper list below `768px`.
4. Desktop chat answers occupy a readable column no wider than `820px`.
5. Assistant answers are no longer framed as repeated cards.
6. Citations, evidence spine, and evidence panel expose one synchronized selection state.
7. Evidence remains fully usable by keyboard and touch.
8. UI labels render in one active locale with no slash-separated bilingual headings.
9. Light and dark themes share the same semantic color rules.
10. The paper title, evidence passage, and utility-data typography roles are visibly distinct without harming CJK
    rendering.
11. Existing chat, citation, source-quote, upload, preview, and library API contracts remain unchanged.
12. Visual snapshots and behavioral tests pass at desktop, tablet, and mobile sizes.

## 10. Non-Goals

- Replacing Vue, Naive UI, UnoCSS, or the existing icon system.
- Creating a public landing page.
- Redesigning the Folio mark.
- Changing backend APIs, research orchestration, retrieval, or evidence contracts.
- Adding dashboards, charts, recommendations, or onboarding copy not supported by product requirements.
- Turning every page into the reading-oriented chat style; administration remains operational and dense.
- Adding decorative gradients, oversized hero typography, nested cards, or broad animation.

## 11. Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Chat DOM changes break tests or reference interaction | Preserve behavioral classes and events; add visual tests before restructuring |
| Evidence spine duplicates citation state | Derive it from existing `referenceMappings` and active reference number only |
| Desktop and mobile library views diverge | Share a paper-row view model and action handlers, not duplicated business logic |
| Naive UI overrides fight view-local CSS | Move semantic decisions into shared tokens and theme settings first |
| Font addition harms performance or CJK rendering | Load only a small Latin subset, retain system CJK fallbacks, and measure CLS |
| Contextual actions become undiscoverable | Show on hover and focus for pointer devices; keep visible on touch |
| Visual polish obscures operational density | Validate with real long filenames, large token counts, failure states, and admin workflows |

## 12. Recommended Delivery Order

Deliver as five independently reviewable pull requests:

1. **Visual fixtures and mobile library correctness**
2. **Shared tokens, typography roles, and locale cleanup**
3. **Chat reading layout and responsive evidence panel**
4. **Evidence spine and library hierarchy**
5. **Login, settings, admin consistency, dark-mode audit, and final visual regression pass**

This order makes the first release materially more usable, keeps behavioral risk bounded, and reserves the most
distinctive visual change for a stage where responsive and token foundations are already stable.
