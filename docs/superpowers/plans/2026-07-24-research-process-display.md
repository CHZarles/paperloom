# Research Process Display — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `frontend/src/views/chat/modules/research-process-panel.vue` so each research phase (search, locate, read, cite, think, answer, error) renders as a visually distinct card with icon, count badge, and item preview, replacing the current flat bullet timeline.

**Architecture:** Single-file Vue 3 `<script setup lang="ts">` change. A pure helper `phaseOf()` + `buildPhaseView()` classifies each `ResearchProgressEvent` (and `ResearchAuditStep`) into a typed `PhaseView`. The template replaces the three-branch `<article>` loop with a single phase-card loop driven by `presentedPhaseCards` / `presentedAuditPhaseCards`. Scoped CSS adds `.phase-card` anatomy (icon rail, headline + state pill, count badge, one-liner, items list, connector). No new components, no new utils, no new dependencies.

**Tech Stack:** Vue 3 (`<script setup lang="ts">`), UnoCSS tokens, Iconify via `SvgIcon`. TypeScript. No new dependencies.

**User Verification:** YES — user opens dev mode (`pnpm dev` in `frontend/` with backend running on `:8081`) and walks through the spec's verification checklist after Task 5 (verification gated by Task 6).

---

## File Structure

**Modify:** `frontend/src/views/chat/modules/research-process-panel.vue` (one file, ~620 lines → ~770 lines)

No new files. The component will grow, but it has one responsibility (render the research process panel) and remains under 800 lines. Extracting `<PhaseCard>` would be premature until a second consumer appears (YAGNI).

---

## Conventions for every task

- **Verify command:** `cd frontend && pnpm typecheck && pnpm lint`
- **Commit subject:** `feat(chat): …` (matches `pnpm sa git-commit -l=zh-cn` style — but use plain English here, the implementer can run `pnpm commit` if they want translation)
- **Pre-commit hook:** `simple-git-hooks` runs `cd frontend && pnpm typecheck && pnpm lint && git diff --exit-code` before every commit. If `pnpm lint --fix` rewrites files, re-stage and retry.

---

## Task 1: Add phase classifier and PhaseView type

**Goal:** Introduce a typed `Phase` / `PhaseView` model and a pure `buildPhaseView()` helper. Add a `presentedPhaseCards` computed alongside the existing `presentedEvents`. The template still uses the old shape — no visual change yet.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] `Phase` and `PhaseView` types defined at the top of `<script setup>`
- [ ] `buildPhaseView(input, index)` returns a `PhaseView` for all 7 phases
- [ ] `getPhaseIcon(phase)` returns an Iconify name (e.g. `lucide:search`)
- [ ] `presentedPhaseCards` computed produces one `PhaseView` per event
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes
- [ ] Template still renders the old `presentedEvents` — no visual change

**Verify:** `cd frontend && pnpm typecheck && pnpm lint` → both exit 0.

**Steps:**

- [ ] **Step 1: Add types and classifier helpers**

Add the following block at the top of `<script setup lang="ts">`, immediately after the existing imports and before `const props = defineProps<…>`:

```ts
type Phase = 'search' | 'locate' | 'read' | 'cite' | 'think' | 'answer' | 'error';

interface PhaseItem {
  key: string | number;
  title: string;
  text: string;
  reference: string;
}

interface PhaseView {
  key: string | number;
  phase: Phase;
  state: 'running' | 'completed' | 'failed';
  headline: string;
  badge: string;
  oneLiner: string;
  durationMs?: number;
  items: PhaseItem[];
}

interface PhaseInput {
  type?: string;
  eventType?: string;
  tool?: string;
  status?: string;
  attempt?: number;
  durationMs?: number;
  message?: string;
  errorType?: string;
  input?: Record<string, any>;
  output?: Record<string, any>;
  usage?: { totalTokens?: number };
}

function eventTypeOf(input: PhaseInput): string {
  return input.eventType || input.type || '';
}

function phaseOf(input: PhaseInput): Phase {
  const type = eventTypeOf(input);
  if (type === 'job_failed' || type === 'job_cancelled') return 'error';
  if (input.tool === 'search_paper_candidates') return 'search';
  if (input.tool === 'find_reading_locations') return 'locate';
  if (input.tool === 'read_locations') return 'read';
  if (input.tool === 'get_citation_edges') return 'cite';
  if (type === 'model_call_started' || type === 'model_call_completed') return 'think';
  if (type === 'answer_completed') return 'answer';
  return 'think';
}

function stateOf(input: PhaseInput): 'running' | 'completed' | 'failed' {
  const type = eventTypeOf(input);
  if (type === 'job_failed' || input.status === 'failed') return 'failed';
  if (type === 'tool_started' || type === 'model_call_started') return 'running';
  return 'completed';
}

function getPhaseIcon(phase: Phase): string {
  switch (phase) {
    case 'search': return 'lucide:search';
    case 'locate': return 'lucide:map-pin';
    case 'read': return 'lucide:book-open';
    case 'cite': return 'lucide:link';
    case 'think': return 'lucide:sparkles';
    case 'answer': return 'lucide:check-circle';
    case 'error': return 'lucide:alert-triangle';
  }
}

function headlineOf(input: PhaseInput, phase: Phase, state: 'running' | 'completed' | 'failed'): string {
  if (phase === 'search') return state === 'running' ? 'Searching papers' : 'Searched papers';
  if (phase === 'locate') return state === 'running' ? 'Locating sections' : 'Located sections';
  if (phase === 'read') return state === 'running' ? 'Reading passages' : 'Read passages';
  if (phase === 'cite') return state === 'running' ? 'Tracing citations' : 'Traced citations';
  if (phase === 'think') {
    const attempt = input.attempt && input.attempt > 1 ? ` · pass ${input.attempt}` : '';
    return `Thinking${attempt}`;
  }
  if (phase === 'answer') return 'Answer prepared';
  if (phase === 'error') {
    return eventTypeOf(input) === 'job_cancelled' ? 'Research cancelled' : 'Research failed';
  }
  return 'Research progress';
}

function pluralize(n: number, singular: string): string {
  return `${n} ${singular}${n === 1 ? '' : 's'}`;
}

function badgeOf(input: PhaseInput, phase: Phase): string {
  const output = input.output || {};
  switch (phase) {
    case 'search': return pluralize(Number(output.resultCount || 0), 'paper');
    case 'locate': return pluralize(Number(output.resultCount || 0), 'location');
    case 'read': return pluralize(Number(output.evidenceCount || output.readCount || 0), 'passage');
    case 'cite': return pluralize(Number(output.edgeCount || 0), 'edge');
    case 'think': return input.durationMs ? `${input.durationMs} ms` : '';
    case 'error': return input.errorType || '';
    default: return '';
  }
}

function oneLinerOf(input: PhaseInput, phase: Phase): string {
  const inputData = input.input || {};
  const output = input.output || {};
  switch (phase) {
    case 'search': {
      const query = String(inputData.query || '').trim();
      const count = Number(output.resultCount || 0);
      if (count === 1 && Array.isArray(output.papers) && output.papers.length) {
        return String(output.papers[0].title || query);
      }
      return query;
    }
    case 'locate': {
      const query = String(inputData.query || '').trim();
      return query || 'Locating relevant sections';
    }
    case 'read': {
      const pages = Array.isArray(output.pages) ? output.pages : [];
      return pages.length ? `pages ${pages.join(', ')}` : '';
    }
    case 'think': {
      const tokens = Number(input.usage?.totalTokens || 0);
      return tokens > 0 ? `${tokens.toLocaleString()} tokens` : '';
    }
    case 'error': return input.message || 'The harness stopped before completing the answer.';
    default: return '';
  }
}

function itemsOf(input: PhaseInput, phase: Phase): PhaseItem[] {
  const output = input.output || {};
  if (phase === 'search' && Array.isArray(output.papers)) {
    return output.papers.slice(0, 10).map((p: any, i: number) => ({
      key: p.paperId || i,
      title: String(p.title || ''),
      text: '',
      reference: String(p.paperId || '')
    }));
  }
  if (phase === 'locate' && Array.isArray(output.locations)) {
    return output.locations.slice(0, 10).map((l: any, i: number) => ({
      key: l.locationRef || i,
      title: [l.section, l.page ? `p. ${l.page}` : ''].filter(Boolean).join(' · '),
      text: '',
      reference: String(l.locationRef || '')
    }));
  }
  if (phase === 'read' && Array.isArray(output.evidence)) {
    return output.evidence.slice(0, 10).map((e: any, i: number) => ({
      key: e.evidenceId || i,
      title: [e.section, e.page ? `p. ${e.page}` : ''].filter(Boolean).join(' · '),
      text: String(e.quote || '').slice(0, 240),
      reference: String(e.evidenceId || '')
    }));
  }
  return [];
}

function buildPhaseView(input: PhaseInput, index: number): PhaseView {
  const phase = phaseOf(input);
  const state = stateOf(input);
  return {
    key: `${phase}:${index}:${input.attempt ?? ''}`,
    phase,
    state,
    headline: headlineOf(input, phase, state),
    badge: badgeOf(input, phase),
    oneLiner: oneLinerOf(input, phase),
    durationMs: input.durationMs,
    items: itemsOf(input, phase)
  };
}
```

- [ ] **Step 2: Add `presentedPhaseCards` computed**

Below the existing `presentedEvents` computed, add:

```ts
const presentedPhaseCards = computed(() =>
  events.value.slice(-MAX_VISIBLE_EVENTS).map((event, index) => buildPhaseView(event, index))
);
```

Template still references `presentedEvents`. This is intentional — Task 2 swaps the binding.

- [ ] **Step 3: Run verification**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0, no template change visible in browser.

- [ ] **Step 4: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): add phase classifier for research process panel"
```

---

## Task 2: Render phase cards for live events

**Goal:** Replace the `v-else-if="events.length"` branch of the template with a phase-card loop driven by `presentedPhaseCards`. Add scoped CSS for `.phase-card` anatomy.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Live-events branch renders one `.phase-card` per event
- [ ] Each card shows icon, headline, state pill, count badge, one-liner
- [ ] Read/search/locate cards show items list (collapsed for read, visible for search/locate)
- [ ] Other branches (audit, legacy, empty) unchanged
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes

**Verify:** `cd frontend && pnpm typecheck && pnpm lint` → both exit 0. Manual: open a chat answer with live research events in dev mode (`pnpm dev` in `frontend/`) and confirm each phase renders as a distinct card.

**Steps:**

- [ ] **Step 1: Replace the events branch template**

Find this block in the template:

```vue
    <div v-else-if="events.length" class="research-process__timeline">
      <article
        v-for="event in presentedEvents"
        :key="event.key"
        class="research-process__event"
        :class="`is-${event.state}`"
      >
        <span class="research-process__marker" />
        <div class="research-process__event-body">
          <div class="research-process__event-heading">
            <strong>{{ event.title }}</strong>
            <span v-if="event.durationMs">{{ event.durationMs }} ms</span>
          </div>
          <div v-if="event.detail" class="research-process__event-detail">{{ event.detail }}</div>
          <div v-if="event.items.length" class="research-process__results">
            <div v-for="item in event.items" :key="item.key" class="research-process__result">
              <div v-if="item.title" class="research-process__result-title">{{ item.title }}</div>
              <p v-if="item.text" class="research-process__result-text">{{ item.text }}</p>
              <div v-if="item.reference" class="research-process__result-ref">
                {{ item.reference }}
              </div>
            </div>
          </div>
        </div>
      </article>
    </div>
```

Replace it with:

```vue
    <div v-else-if="events.length" class="research-process__timeline">
      <article
        v-for="card in presentedPhaseCards"
        :key="card.key"
        class="phase-card"
        :class="[`phase-card--${card.phase}`, `is-${card.state}`]"
      >
        <div class="phase-card__icon">
          <SvgIcon :icon="getPhaseIcon(card.phase)" class="text-16" />
        </div>
        <div class="phase-card__body">
          <div class="phase-card__heading">
            <strong>{{ card.headline }}</strong>
            <span class="phase-card__state-pill" :class="`is-${card.state}`">
              {{ card.state }}
            </span>
            <span v-if="card.badge" class="phase-card__badge">{{ card.badge }}</span>
          </div>
          <div v-if="card.oneLiner" class="phase-card__one-liner">{{ card.oneLiner }}</div>
          <div v-if="card.items.length && card.phase !== 'read'" class="phase-card__items">
            <div v-for="item in card.items" :key="item.key" class="phase-card__item">
              <span v-if="item.title" class="phase-card__item-title">{{ item.title }}</span>
            </div>
          </div>
          <details v-if="card.items.length && card.phase === 'read'" class="phase-card__items phase-card__items--collapsed">
            <summary>{{ card.items.length }} passage{{ card.items.length === 1 ? '' : 's' }}</summary>
            <div v-for="item in card.items" :key="item.key" class="phase-card__item">
              <span v-if="item.title" class="phase-card__item-title">{{ item.title }}</span>
              <p v-if="item.text" class="phase-card__item-text">{{ item.text }}</p>
            </div>
          </details>
        </div>
      </article>
    </div>
```

- [ ] **Step 2: Add scoped CSS for `.phase-card`**

Find the closing `</style>` tag. Just before it, add:

```css
.phase-card {
  position: relative;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  padding: 12px 0;
}

.phase-card:not(:last-child)::after {
  position: absolute;
  top: 44px;
  bottom: -4px;
  left: 15px;
  width: 1px;
  background: var(--color-border);
  content: '';
}

.phase-card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--color-research-soft-bg);
  color: var(--color-research);
}

.phase-card.is-completed .phase-card__icon {
  background: var(--color-surface-alt);
  color: var(--color-success);
}

.phase-card.is-failed .phase-card__icon {
  background: var(--color-surface-alt);
  color: var(--color-error);
}

.phase-card.is-running .phase-card__icon {
  animation: phase-card-pulse 1.6s ease-in-out infinite;
}

@keyframes phase-card-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

.phase-card__body {
  min-width: 0;
}

.phase-card__heading {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}

.phase-card__heading strong {
  font-weight: 700;
}

.phase-card__state-pill {
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.phase-card__state-pill.is-running {
  background: rgba(183, 121, 31, 0.15);
  color: var(--color-warning);
}

.phase-card__state-pill.is-completed {
  background: rgba(22, 128, 57, 0.12);
  color: var(--color-success);
}

.phase-card__state-pill.is-failed {
  background: rgba(217, 45, 32, 0.12);
  color: var(--color-error);
}

.phase-card__badge {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-primary-soft-bg);
  color: var(--color-text);
  font-family: var(--font-utility);
  font-size: 11px;
}

.phase-card__one-liner {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.phase-card__items {
  margin-top: 8px;
  border-left: 2px solid var(--color-border);
  padding-left: 10px;
}

.phase-card__items--collapsed > summary {
  cursor: pointer;
  color: var(--color-text-muted);
  font-size: 12px;
  list-style: none;
}

.phase-card__items--collapsed > summary::-webkit-details-marker {
  display: none;
}

.phase-card__item {
  padding: 4px 0;
}

.phase-card__item-title {
  font-size: 12px;
  font-weight: 650;
}

.phase-card__item-text {
  margin: 2px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
```

- [ ] **Step 3: Run verification**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): render research phases as distinct cards"
```

---

## Task 3: Render phase cards for audit steps

**Goal:** Reuse the same phase-card shape for `ResearchAuditStep` (post-hoc audit trail). Add `presentedAuditPhaseCards` and replace the audit timeline template.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Audit steps render as `.phase-card` (same shape as live events)
- [ ] Audit step `kind` (e.g. `find_reading_locations`) is mapped to a `tool` name before classification
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes

**Verify:** `cd frontend && pnpm typecheck && pnpm lint` → both exit 0. Manual: open a chat answer with a complete audit trail (after answer completes) and confirm phase cards render.

**Steps:**

- [ ] **Step 1: Add `auditStepToPhaseInput` adapter**

Below the existing `presentedPhaseCards` computed, add:

```ts
function auditStepToPhaseInput(step: Api.Chat.ResearchAuditStep): PhaseInput {
  return {
    tool: step.kind || undefined,
    status: step.status || undefined,
    durationMs: step.durationMs || undefined,
    message: step.message || undefined,
    input: step.query ? { query: step.query } : {},
    output: {}
  };
}

const presentedAuditPhaseCards = computed(() =>
  auditSteps.value.map((step, index) => buildPhaseView(auditStepToPhaseInput(step), index))
);
```

- [ ] **Step 2: Replace the audit timeline template**

Find this block:

```vue
      <div v-if="auditSteps.length" class="research-process__timeline">
        <article
          v-for="step in auditSteps"
          :key="step.stepId || `${step.kind}:${step.query}`"
          class="research-process__event"
          :class="`is-${step.status || 'completed'}`"
        >
          <span class="research-process__marker" />
          <div class="research-process__event-body">
            <div class="research-process__event-heading">
              <strong>{{ auditStepTitle(step) }}</strong>
              <span v-if="step.durationMs">{{ step.durationMs }} ms</span>
            </div>
            <div v-if="auditStepDetail(step)" class="research-process__event-detail">{{ auditStepDetail(step) }}</div>
          </div>
        </article>
      </div>
```

Replace it with:

```vue
      <div v-if="presentedAuditPhaseCards.length" class="research-process__timeline">
        <article
          v-for="card in presentedAuditPhaseCards"
          :key="card.key"
          class="phase-card"
          :class="[`phase-card--${card.phase}`, `is-${card.state}`]"
        >
          <div class="phase-card__icon">
            <SvgIcon :icon="getPhaseIcon(card.phase)" class="text-16" />
          </div>
          <div class="phase-card__body">
            <div class="phase-card__heading">
              <strong>{{ card.headline }}</strong>
              <span v-if="card.badge" class="phase-card__badge">{{ card.badge }}</span>
            </div>
            <div v-if="card.oneLiner" class="phase-card__one-liner">{{ card.oneLiner }}</div>
          </div>
        </article>
      </div>
```

The old `auditStepTitle` and `auditStepDetail` helpers are now unused. **Delete them** along with this replacement:

```ts
// DELETE these two functions (they are now unused)
function auditStepTitle(step: Api.Chat.ResearchAuditStep) {
  return toolLabel(step.kind || '', step.status === 'running');
}

function auditStepDetail(step: Api.Chat.ResearchAuditStep) {
  const parts = [
    step.query,
    step.paperIds?.length ? `${step.paperIds.length} papers` : '',
    step.locationRefs?.length ? `${step.locationRefs.length} locations` : '',
    step.evidenceRefs?.length ? `${step.evidenceRefs.length} evidence` : '',
    step.durationMs ? `${step.durationMs} ms` : '',
    step.message
  ];
  return parts.filter(Boolean).join(' · ');
}
```

Also remove the `latestAuditStep` and `auditStepTitle(step)` reference in the top status strip **only if** you choose to do so in Task 5 (for now, keep `latestAuditStep` but drop the `auditStepTitle` call — replace it with `auditStepToPhaseInput(latestAuditStep).tool` lookup if needed). To keep Task 3 surgical, **leave `latestAuditStep` alone** and only delete `auditStepTitle` / `auditStepDetail`. The top status strip will be reworked in Task 5.

- [ ] **Step 3: Run verification**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): render audit steps as phase cards"
```

---

## Task 4: Re-skin audit ledger rows as cards

**Goal:** Update each `.research-process__evidence-row` to use the same card shape (count badge in title row, consistent spacing) so the visual language is unified across streaming and post-hoc views. Preserve click-to-open-reference behavior.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Ledger rows show a count badge in the title row (per-group total only — already there)
- [ ] Rows use the same hover/focus border color as phase cards
- [ ] Click on row still emits `openReference`
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes

**Verify:** `cd frontend && pnpm typecheck && pnpm lint` → both exit 0. Manual: open an answer with cited/read/candidate groups and click a row — the reference opens as before.

**Steps:**

- [ ] **Step 1: Update ledger row class**

Find `<button class="research-process__evidence-row"`. Replace `class="research-process__evidence-row"` with `class="phase-card phase-card--ledger"` and the row's class binding `:class="\`is-\${step.status || 'completed'}\`"` is not applicable here. Keep all other attributes unchanged.

The exact change inside the `<button>` element:

```vue
          <button
            v-for="(row, rowIndex) in group.rows"
            :key="evidenceKey(row, rowIndex)"
            type="button"
            class="phase-card phase-card--ledger"
            :disabled="!canOpenEvidence(row)"
            @click="openEvidence(row)"
          >
```

- [ ] **Step 2: Add `.phase-card--ledger` CSS**

Just before the closing `</style>` tag, add:

```css
.phase-card--ledger {
  grid-template-columns: minmax(0, 1fr);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.phase-card--ledger:hover:not(:disabled),
.phase-card--ledger:focus-visible {
  border-color: var(--color-primary);
  background: var(--color-accent-soft-bg);
}

.phase-card--ledger:disabled {
  cursor: default;
}

.phase-card--ledger:not(:last-child)::after {
  display: none;
}
```

- [ ] **Step 3: Run verification**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): re-skin audit ledger rows as phase cards"
```

---

## Task 5: Top status strip + edge cases

**Goal:** Refresh the top status strip to use the latest phase's icon and headline. Add a "Researching…" pulse card for the empty-but-running state. Collapse consecutive `think` events into a single card with a "pass N of M" hint.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Top status strip shows the latest phase's icon + headline (replacing the bullet dot)
- [ ] When `isRunning` is true but no events yet, a single "Researching…" pulse card renders
- [ ] Consecutive `think` events collapse into a single card showing the latest pass + `(N passes)` hint
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes

**Verify:** `cd frontend && pnpm typecheck && pnpm lint` → both exit 0. Manual: walk through the three states — running with no events yet, running with multiple thinking passes, audit trail after completion.

**Steps:**

- [ ] **Step 1: Update top status strip**

Find this block:

```vue
    <div class="research-process__status">
      <span class="research-process__status-dot" :class="{ 'is-running': isRunning }" />
      <div>
        <div class="research-process__status-title">
          {{
            hasAuditTrail
              ? latestAuditStep
                ? auditStepTitle(latestAuditStep)
                : 'Research audit trail'
              : latestPresentedEvent
                ? latestPresentedEvent.title
                : isRunning
                  ? 'Researching'
                  : 'No process selected'
          }}
        </div>
        <div v-if="hasAuditTrail && latestAuditStep" class="research-process__status-detail">
          {{ auditStepDetail(latestAuditStep) }}
        </div>
        <div v-else-if="latestPresentedEvent?.detail" class="research-process__status-detail">
          {{ latestPresentedEvent.detail }}
        </div>
      </div>
    </div>
```

Replace it with:

```vue
    <div class="research-process__status">
      <div class="research-process__status-icon" :class="{ 'is-running': isRunning }">
        <SvgIcon
          :icon="getPhaseIcon(latestPresentedPhase?.phase ?? (isRunning ? 'think' : 'answer'))"
          class="text-16"
        />
      </div>
      <div>
        <div class="research-process__status-title">
          {{
            latestPresentedPhase?.headline
              ?? (isRunning ? 'Researching' : 'No process selected')
          }}
        </div>
        <div v-if="latestPresentedPhase?.oneLiner" class="research-process__status-detail">
          {{ latestPresentedPhase.oneLiner }}
        </div>
        <div v-else-if="latestPresentedPhase?.badge" class="research-process__status-detail">
          {{ latestPresentedPhase.badge }}
        </div>
      </div>
    </div>
```

- [ ] **Step 2: Add `latestPresentedPhase` and empty-running card computeds**

Below `latestPresentedEvent`, replace it with:

```ts
const latestPresentedPhase = computed<PhaseView | undefined>(() => {
  if (hasAuditTrail.value && auditSteps.value.length) {
    const last = auditSteps.value[auditSteps.value.length - 1];
    return buildPhaseView(auditStepToPhaseInput(last), auditSteps.value.length - 1);
  }
  return presentedPhaseCards.value[presentedPhaseCards.value.length - 1];
});

const collapsedPhaseCards = computed<PhaseView[]>(() => {
  const cards = presentedPhaseCards.value;
  const out: PhaseView[] = [];
  for (const card of cards) {
    const prev = out[out.length - 1];
    if (prev && prev.phase === 'think' && card.phase === 'think') {
      prev.headline = card.headline;
      prev.badge = card.badge;
      prev.oneLiner = card.oneLiner;
      prev.durationMs = card.durationMs;
      prev.state = card.state;
      const match = prev.headline.match(/· pass (\d+)/);
      if (match) {
        const total = Number(match[1]);
        if (total > 1) prev.headline = prev.headline.replace(/· pass \d+/, `· pass ${total} of ${cards.length}`);
      }
      continue;
    }
    out.push({ ...card });
  }
  return out;
});
```

Replace the binding in the events-branch template from `presentedPhaseCards` to `collapsedPhaseCards`:

```vue
        v-for="card in collapsedPhaseCards"
```

- [ ] **Step 3: Add empty-running pulse card**

In the template, just before the `events.length` branch, add:

```vue
    <div v-else-if="isRunning" class="research-process__timeline">
      <article class="phase-card is-running">
        <div class="phase-card__icon">
          <SvgIcon icon="lucide:sparkles" class="text-16" />
        </div>
        <div class="phase-card__body">
          <div class="phase-card__heading">
            <strong>Researching</strong>
            <span class="phase-card__state-pill is-running">running</span>
          </div>
          <div class="phase-card__one-liner">Waiting for the first research step…</div>
        </div>
      </article>
    </div>
```

The existing `v-else-if="events.length"` becomes the third branch in the chain — Vue's `v-else-if` order handles this automatically.

- [ ] **Step 4: Remove now-unused helpers**

The following functions from the old implementation are no longer referenced:
- `eventTitle` — delete
- `eventDetail` — delete
- `eventItems` — delete
- `itemTitle` — delete
- `itemText` — delete
- `eventState` — delete
- `presentEvent` — delete
- `legacyToolLabel` — delete (only used by old legacy branch — keep the legacy branch as it was, this function is no longer needed since the legacy branch still uses `legacyToolLabel`. Wait — actually the legacy branch still uses it. Keep `legacyToolLabel` if `legacyTools.length` branch still references it. Yes, line 381 still uses `legacyToolLabel(event)`. Keep it.)
- `PresentationCache` (`presentationCache` const) and `PresentedEvent` interface — delete
- `presentationCache` const — delete
- `PresentedEvent` interface — delete

Delete:

```ts
interface PresentedEvent {
  key: string | number;
  title: string;
  detail: string;
  durationMs?: number;
  state: string;
  items: Array<{
    key: string | number;
    title: string;
    text: string;
    reference: string;
  }>;
}

const presentationCache = new WeakMap<Api.Chat.ResearchProgressEvent, PresentedEvent>();

function eventType(event: Api.Chat.ResearchProgressEvent) {
  return event.eventType || event.type;
}

function eventTitle(event: Api.Chat.ResearchProgressEvent) { /* … */ }
function toolLabel(tool?: string, running = false) { /* … */ }
function eventDetail(event: Api.Chat.ResearchProgressEvent) { /* … */ }
function eventItems(event: Api.Chat.ResearchProgressEvent) { /* … */ }
function itemTitle(item: Record<string, any>) { /* … */ }
function itemText(item: Record<string, any>) { /* … */ }
function eventState(event: Api.Chat.ResearchProgressEvent) { /* … */ }
function presentEvent(event: Api.Chat.ResearchProgressEvent, index: number): PresentedEvent { /* … */ }
```

(Leave `toolLabel` in place — it's used by `legacyToolLabel`.)

The `presentedEvents` computed is no longer referenced by the template. **Delete it:**

```ts
// DELETE
const presentedEvents = computed(() =>
  events.value.slice(-MAX_VISIBLE_EVENTS).map((event, index) => presentEvent(event, index))
);
```

Also delete the unused `MAX_VISIBLE_EVENTS` constant if no other reference remains:

```ts
// DELETE if no other references
const MAX_VISIBLE_EVENTS = 100;
```

The `latestPresentedEvent` computed is also no longer referenced by the template (replaced by `latestPresentedPhase`). **Delete it too:**

```ts
// DELETE
const latestPresentedEvent = computed(() => presentedEvents.value[presentedEvents.value.length - 1]);
```

`latestAuditStep` is still referenced by the audit-ledger section via `auditStepTitle(latestAuditStep)`. Wait — `auditStepTitle` was deleted in Task 3. Let me re-check what references `latestAuditStep` in the remaining template. After Task 3 deletion, `latestAuditStep` is only used by the top status strip, which we just rewrote in Step 1 to use `latestPresentedPhase`. So `latestAuditStep` is now unused. **Delete:**

```ts
// DELETE
const latestAuditStep = computed(() => auditSteps.value[auditSteps.value.length - 1]);
```

- [ ] **Step 5: Update `.research-process__status` styles**

Find these styles and adjust the icon styles:

```css
.research-process__status-dot,
.research-process__marker {
  display: block;
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: var(--color-success);
}

.research-process__status-dot {
  margin-top: 6px;
}

.research-process__status-dot.is-running,
.research-process__event.is-running .research-process__marker {
  background: var(--color-warning);
}

.research-process__event.is-failed .research-process__marker {
  background: var(--color-error);
}
```

Replace with:

```css
.research-process__status-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--color-research-soft-bg);
  color: var(--color-research);
  flex: 0 0 auto;
}

.research-process__status-icon.is-running {
  animation: phase-card-pulse 1.6s ease-in-out infinite;
}

.research-process__marker {
  display: none;
}
```

The `.research-process__marker` is no longer rendered (we replaced it with `.phase-card__icon`), so hiding it via `display: none` is safe.

- [ ] **Step 6: Run verification**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 7: Manual smoke test**

In a browser, open the chat with backend running:

1. Open an existing chat answer with audit trail — confirm the top status strip shows the latest phase's icon + headline.
2. Start a new research answer and watch the empty-running pulse card render before the first event arrives.
3. Trigger a multi-pass query (or replay a fixture with multiple `model_call_started`) — confirm consecutive thinking cards collapse into one with `pass N of M`.
4. Trigger a `job_failed` event — confirm the error card renders with the full error message.

- [ ] **Step 8: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): refresh status strip and add running/collapse states"
```

---

## Task 6: User verification of the visual redesign

**Goal:** User confirms in dev mode that the redesigned panel meets the spec.

**Files:** none

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:
```yaml
AskUserQuestion:
  question: "Did the redesigned research process panel render each phase as a visually distinct card (search / locate / read / cite / think / answer / error), with the audit ledger rows opening references correctly and no regressions in streaming or error states?"
  header: "Verification"
  options:
    - label: "Looks good"
      description: "Phase cards are visually distinct, references open on click, streaming and error states behave correctly"
    - label: "Needs rework"
      description: "Some phase cards still look abstract, references don't open, or a regression was noticed"
```

**If the user selects the negative option:** The task is NOT complete. Investigate which area failed (icons, badges, references, collapse, error card), fix in a follow-up commit, then re-verify with AskUserQuestion again.

**Acceptance Criteria:**
- [ ] User confirms cards are visually distinct
- [ ] User confirms evidence rows open references
- [ ] User confirms no regression in streaming flow

**Verify:** AskUserQuestion result is `Looks good`.

**Steps:**

- [ ] **Step 1: Start dev mode**

Run backend on `:8081` (per `CLAUDE.md`), then:

```bash
cd /home/charles/PaiSmart/frontend
pnpm dev
```

Open `http://localhost:9527` in a browser.

- [ ] **Step 2: Walk through the verification checklist**

- [ ] Open a chat answer with a complete audit trail. Confirm:
  - [ ] Each phase (search / locate / read / cite / think / answer) renders as a visually distinct card with its own icon
  - [ ] Count badges show meaningful numbers (papers / locations / passages / edges)
  - [ ] Read cards have an expandable list of evidence with page numbers
  - [ ] Search/locate cards show titles inline
  - [ ] Audit ledger (cited / read / candidate) renders as cards and clicking opens the reference

- [ ] Start a new research answer. Confirm:
  - [ ] Before the first event arrives, a single "Researching…" pulse card shows
  - [ ] As events arrive, each one renders as a phase card with the right icon
  - [ ] Multiple thinking passes collapse into one card with `pass N of M`
  - [ ] Top status strip mirrors the latest phase

- [ ] Trigger or replay an error. Confirm:
  - [ ] The error card shows the full error message
  - [ ] Icon and state pill use `--color-error`

- [ ] Toggle dark mode. Confirm:
  - [ ] All cards render with the dark-mode tokens
  - [ ] No white-on-white or other contrast issues

- [ ] **Step 3: Run final typecheck + lint**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Call AskUserQuestion**

Use the question and options from the verification block above.

- [ ] **Step 5: If user says "Looks good"** — done. No commit required (no code change in this task).

- [ ] **Step 5b: If user says "Needs rework"** — open a follow-up task, fix the issue, return to Task 6 Step 2.

```json:metadata
{"files": [], "verifyCommand": "", "acceptanceCriteria": ["user confirms cards are visually distinct", "user confirms evidence rows open references", "user confirms no regression in streaming flow"], "requiresUserVerification": true, "userVerificationPrompt": "Did the redesigned research process panel render each phase as a visually distinct card (search / locate / read / cite / think / answer / error), with the audit ledger rows opening references correctly and no regressions in streaming or error states?"}
```

---

## Self-Review

**1. Spec coverage:**
- Phase model ✓ Task 1, Task 2, Task 3
- Phase rules table ✓ Task 1 (all 7 phases)
- Item previews ✓ Task 1 (search/locate/read items), Task 2 (read collapse)
- Layout (icon rail, headline, badge, one-liner, items, connector) ✓ Task 2
- Audit ledger re-skin ✓ Task 4
- State mapping (running/completed/failed colors) ✓ Task 2
- Edge cases: empty / loading / error ✓ Task 5
- Long evidence quote (3 lines + ellipsis) ✓ Task 2 (`-webkit-line-clamp: 3`)
- Unknown tool/type fallthrough ✓ Task 1 (returns 'think' phase with 'Research progress' headline)
- Same-phase collapse ✓ Task 5
- Streaming → audit transition ✓ handled by `latestPresentedPhase` (Tasks 3, 5)
- Verification (typecheck + lint + manual) ✓ Task 5 Step 6, Task 6 Step 2
- Risks (visual regression) ✓ mitigated by Task 1 (no template change) as safe first commit
- Rollback ✓ single-file revert documented in spec

**2. Placeholder scan:** No TBDs, no "implement later", no "fill in details", no "add appropriate error handling".

**3. Type consistency:** `Phase`, `PhaseView`, `PhaseItem`, `PhaseInput` defined once in Task 1. `phaseOf`, `buildPhaseView`, `getPhaseIcon`, `auditStepToPhaseInput`, `latestPresentedPhase`, `collapsedPhaseCards` all reference the same names throughout. `presentedPhaseCards` referenced in Task 1, Task 3, Task 5 (replaced by `collapsedPhaseCards`). `latestPresentedPhase` defined and consumed in Task 5.

**4. Verification requirement scan:** YES — spec verification section + user's "优化一下" UI request. Task 6 has `requiresUserVerification: true` and the standard verification block.

No gaps. Plan ready.