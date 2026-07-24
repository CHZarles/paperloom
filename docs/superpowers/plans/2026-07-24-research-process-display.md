# Research Process Display — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drop chrome (badges, state pills, duration tokens, pass counters) from the research process panel. Each card shows only the useful info: paper names for search, sections for locate, page list and collapsed passages for read, and a one-line decision for think.

**Architecture:** Single-file Vue 3 `<script setup lang="ts">` change in `frontend/src/views/chat/modules/research-process-panel.vue`. Simplify the existing `PhaseView` type (drop `badge` and `durationMs`, add `decision` and `detail`). Adjust `buildPhaseView`, `presentedPhaseCards`, and `presentedAuditPhaseCards`. Simplify the live-events and audit-step template branches. Refresh the top status strip. No new components, no new dependencies.

**Tech Stack:** Vue 3 (`<script setup lang="ts">`), UnoCSS tokens, Iconify via `SvgIcon`. TypeScript. No new dependencies.

**User Verification:** YES — user opens dev mode (`pnpm dev` in `frontend/` with backend on `:8081`) and walks through the spec's verification checklist after Task 5.7 (verification gated by Task 6).

---

## File Structure

**Modify:** `frontend/src/views/chat/modules/research-process-panel.vue` (one file, currently ~830 lines after Tasks 1–3, will shrink by ~30 lines after simplifications).

No new files. The component has one responsibility and stays focused.

---

## Conventions for every task

- **Verify command:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
- **Commit subject:** `feat(chat): …`
- **Pre-commit hook:** `simple-git-hooks` runs `cd frontend && pnpm typecheck && pnpm lint && git diff --exit-code` before every commit.
- The orchestrator commits work (subagents stage but do not commit) because the `pre-commit-check-tasks` hook blocks commits while pending tasks exist in this multi-task plan. Subagents should leave changes staged and report back.

---

## Task 3.5: Simplify phase cards

**Goal:** Drop count badges, state pills, duration suffix, and token counts from the rendered cards. For think phase, derive a one-line decision from the next event. Update the `PhaseView` type to match.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] `PhaseView` no longer has `badge` or `durationMs` fields
- [ ] `PhaseView` has new `decision` and `detail` fields
- [ ] `buildPhaseView` no longer calls `badgeOf`; `headlineOf` drops pass-number suffix; think populates `decision`
- [ ] Live-events template has no count badge, no state pill, no duration span
- [ ] Audit-step template matches the same simplification
- [ ] Think events render a "Decided to …" line derived from the next event
- [ ] Read events render the page list in `detail`
- [ ] Search events render paper titles in `items`
- [ ] Locate events render section names in `items`
- [ ] Read events keep `<details>` collapsed by default (already in place)
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes for the modified file

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint` → both exit 0.

**Steps:**

- [ ] **Step 1: Update the `PhaseView` interface**

In `frontend/src/views/chat/modules/research-process-panel.vue`, find the `PhaseView` interface and replace it with:

```ts
interface PhaseView {
  key: string | number;
  phase: Phase;
  state: 'running' | 'completed' | 'failed';
  headline: string;       // e.g. "Searched papers", "Reasoning"
  decision: string;       // think-only: "Decided to search papers"
  detail: string;         // read-only: "pages 1, 2, 3"; error-only: error message
  items: PhaseItem[];
}
```

Keep `Phase` and `PhaseItem` as-is.

- [ ] **Step 2: Replace `headlineOf` and add `decisionOf`**

Find the existing `headlineOf` function (in the helpers block at the top of `<script setup>`). Replace it with:

```ts
function headlineOf(phase: Phase, _state: 'running' | 'completed' | 'failed'): string {
  switch (phase) {
    case 'search': return 'Searched papers';
    case 'locate': return 'Located sections';
    case 'read': return 'Read passages';
    case 'cite': return 'Traced citations';
    case 'think': return 'Reasoning';
    case 'answer': return 'Answer prepared';
    case 'error': return 'Research failed';
  }
  return 'Research progress';
}
```

Note: dropped the pass-number suffix. The second argument is kept for signature compatibility but unused.

Add a new `decisionOf` helper just below:

```ts
function decisionOf(allEvents: PhaseInput[], index: number): string {
  const next = allEvents[index + 1];
  if (!next) return '';
  const tool = next.tool || '';
  const type = next.eventType || next.type || '';
  if (tool === 'search_paper_candidates' || type === 'tool_started' && tool === 'search_paper_candidates') {
    return 'Decided to search papers';
  }
  if (tool === 'find_reading_locations') return 'Decided to locate sections';
  if (tool === 'read_locations') return 'Decided to read passages';
  if (tool === 'get_citation_edges') return 'Decided to trace citations';
  if (type === 'answer_completed') return 'Decided to answer';
  return '';
}
```

- [ ] **Step 3: Simplify `oneLinerOf` → `detailOf`**

Find `oneLinerOf` and rename it to `detailOf`. Drop the search paper-title branch (moved into items), drop the duration/token branch (deleted), drop the think oneLiner (now `decision`), and drop the error branch (now in `detail`). Keep only the read page-list branch and the error branch:

```ts
function detailOf(input: PhaseInput, phase: Phase): string {
  const output = input.output || {};
  switch (phase) {
    case 'read': {
      const pages = Array.isArray(output.pages) ? output.pages : [];
      return pages.length ? `pages ${pages.join(', ')}` : '';
    }
    case 'error': return input.message || 'The harness stopped before completing the answer.';
    default: return '';
  }
}
```

- [ ] **Step 4: Delete `badgeOf` and the duration-token plumbing**

Delete the entire `badgeOf` function. Also remove any reference to `pluralize` if it becomes unused.

- [ ] **Step 5: Update `buildPhaseView` to use the new helpers**

Find `buildPhaseView` and replace it with:

```ts
function buildPhaseView(input: PhaseInput, allEvents: PhaseInput[], index: number): PhaseView {
  const phase = phaseOf(input);
  const state = stateOf(input);
  return {
    key: `${phase}:${index}:${input.attempt ?? ''}`,
    phase,
    state,
    headline: headlineOf(phase, state),
    decision: phase === 'think' ? decisionOf(allEvents, index) : '',
    detail: detailOf(input, phase),
    items: itemsOf(input, phase)
  };
}
```

Note: `buildPhaseView` now takes `allEvents` so it can look ahead for the think decision.

- [ ] **Step 6: Update `presentedPhaseCards` and `presentedAuditPhaseCards`**

Find both computeds and update them to pass the events array:

```ts
const presentedPhaseCards = computed(() =>
  events.value
    .slice(-MAX_VISIBLE_EVENTS)
    .map((event, index, all) => buildPhaseView(event, all, index))
);

const presentedAuditPhaseCards = computed(() => {
  const inputs = auditSteps.value.map(auditStepToPhaseInput);
  return inputs.map((input, index, all) => buildPhaseView(input, all, index));
});
```

- [ ] **Step 7: Simplify the live-events template branch**

Find the `<div v-else-if="events.length" class="research-process__timeline">` block. Replace its inner content (the `<article>` loop) with:

```vue
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
          </div>
          <div v-if="card.decision" class="phase-card__decision">{{ card.decision }}</div>
          <div v-if="card.detail" class="phase-card__detail">{{ card.detail }}</div>
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
```

Differences from the previous template:
- No `.phase-card__state-pill`
- No `.phase-card__badge`
- New `.phase-card__decision` (for think)
- New `.phase-card__detail` (replaces `.phase-card__one-liner`, only used for read and error)
- `.phase-card__one-liner` removed

- [ ] **Step 8: Simplify the audit-step template branch**

Find the `<div v-if="presentedAuditPhaseCards.length" class="research-process__timeline">` block inside the audit branch. Replace its `<article>` loop with the same simplified shape:

```vue
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
          </div>
          <div v-if="card.decision" class="phase-card__decision">{{ card.decision }}</div>
          <div v-if="card.detail" class="phase-card__detail">{{ card.detail }}</div>
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
```

- [ ] **Step 9: Update scoped CSS**

Find the `.phase-card__state-pill`, `.phase-card__badge`, and `.phase-card__one-liner` rules in `<style scoped>`. **Delete** them.

Add new rules for `.phase-card__decision` and `.phase-card__detail`:

```css
.phase-card__decision {
  margin-top: 2px;
  color: var(--color-research);
  font-size: 12px;
  font-weight: 600;
}

.phase-card__detail {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
```

Also update the running-state CSS so the icon pulses:

```css
.phase-card.is-running .phase-card__icon {
  animation: phase-card-pulse 1.6s ease-in-out infinite;
}
```

(Already in place from Task 2 — verify it is still there.)

- [ ] **Step 10: Run verification**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 11: Commit**

The orchestrator commits (subagents leave changes staged).

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): simplify phase cards, drop badges and metrics"
```

---

## Task 3.6: Simplify top status strip

**Goal:** Status header shows only a pulse + current activity while running, a check when complete. No counts, no verbose activity labels with metrics.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Header has a small icon (pulse when running, check when complete, warning when failed)
- [ ] Header shows the latest event's headline (or "Researching…" / "Research complete" / "Research failed")
- [ ] No badge, no count, no metrics anywhere in the header
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes for the modified file

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint` → both exit 0.

**Steps:**

- [ ] **Step 1: Replace the status strip template**

Find the `<div class="research-process__status">` block in the template. Replace it with:

```vue
    <div class="research-process__status">
      <div class="research-process__status-icon" :class="`is-${latestPresentedPhase?.state ?? (isRunning ? 'running' : 'idle')}`">
        <SvgIcon
          :icon="latestPresentedPhase?.phase === 'error' ? 'lucide:alert-triangle' : (latestPresentedPhase?.phase === 'answer' ? 'lucide:check-circle' : (isRunning ? 'lucide:loader' : 'lucide:sparkles'))"
          class="text-16"
        />
      </div>
      <div class="research-process__status-title">
        {{
          latestPresentedPhase?.headline
            ?? (isRunning ? 'Researching…' : 'Research complete')
        }}
      </div>
    </div>
```

- [ ] **Step 2: Update `.research-process__status` CSS**

Find the existing `.research-process__status-dot`, `.research-process__status-icon`, and `.research-process__marker` rules in `<style scoped>`. Replace the icon rules with:

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

.research-process__status-icon.is-completed {
  background: rgba(22, 128, 57, 0.12);
  color: var(--color-success);
}

.research-process__status-icon.is-failed {
  background: rgba(217, 45, 32, 0.12);
  color: var(--color-error);
}

.research-process__status-title {
  font-size: 14px;
  font-weight: 700;
}

.research-process__marker {
  display: none;
}
```

- [ ] **Step 3: Update `latestPresentedPhase` if needed**

`latestPresentedPhase` was added in Task 5 of the previous plan; if it doesn't exist yet, add it just below `presentedAuditPhaseCards`:

```ts
const latestPresentedPhase = computed<PhaseView | undefined>(() => {
  if (hasAuditTrail.value && auditSteps.value.length) {
    const inputs = auditSteps.value.map(auditStepToPhaseInput);
    return buildPhaseView(inputs[inputs.length - 1], inputs, inputs.length - 1);
  }
  const cards = presentedPhaseCards.value;
  return cards[cards.length - 1];
});
```

- [ ] **Step 4: Delete the old status-detail helpers**

Delete `latestPresentedEvent` (no longer referenced). Delete the `.research-process__status-detail`, `.research-process__status-title` unused styles if any are left over.

- [ ] **Step 5: Run verification**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 6: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): simplify status strip to pulse + activity"
```

---

## Task 4: Re-skin audit ledger rows as cards

**Goal:** Update each `.research-process__evidence-row` to use the same minimal `.phase-card` shape. Preserve the three audit groups (cited / read but not cited / candidate only) and click-to-open-reference behavior.

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] Ledger rows render with the same minimal card visual
- [ ] Three groups preserved
- [ ] Click on row still emits `openReference`
- [ ] No count badges, no metrics in ledger row headings
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes for the modified file

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint` → both exit 0. Manual: click a ledger row, confirm reference opens.

**Steps:**

- [ ] **Step 1: Replace ledger row class binding**

Find `<button class="research-process__evidence-row"` and replace `class="research-process__evidence-row"` with `class="phase-card phase-card--ledger"`. Keep all other attributes unchanged.

- [ ] **Step 2: Simplify the ledger row body**

Inside each ledger button, the current content is:

```vue
            <div class="research-process__evidence-title">{{ evidenceTitle(row) }}</div>
            <p v-if="evidenceText(row)" class="research-process__result-text">{{ evidenceText(row) }}</p>
            <div class="research-process__evidence-meta">{{ evidenceMeta(row) }}</div>
            <div class="research-process__visual-state">{{ evidenceVisualLabel(row) }}</div>
```

Replace with:

```vue
            <div class="phase-card__heading">
              <strong>{{ evidenceTitle(row) }}</strong>
            </div>
            <p v-if="evidenceText(row)" class="phase-card__item-text">{{ evidenceText(row) }}</p>
            <div v-if="evidenceMeta(row)" class="phase-card__detail">{{ evidenceMeta(row) }}</div>
```

Differences:
- Drop `research-process__visual-state` (the "PDF page + bbox" / "Table image" label) — too noisy
- Reuse `.phase-card__heading`, `.phase-card__item-text`, `.phase-card__detail` from Task 3.5

- [ ] **Step 3: Adjust `.phase-card--ledger` CSS**

Find the existing `.phase-card--ledger` rules in `<style scoped>`. Replace with:

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

- [ ] **Step 4: Delete obsolete ledger CSS**

Delete the now-unused `.research-process__evidence-row`, `.research-process__evidence-title`, `.research-process__evidence-meta`, `.research-process__visual-state`, `.research-process__result-text` rules if any remain in the file.

- [ ] **Step 5: Run verification**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 6: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "feat(chat): re-skin audit ledger rows with minimal card shape"
```

---

## Task 5: Delete now-unused legacy helpers

**Goal:** Clean up the old helpers that were retained during the migration: `eventTitle`, `eventDetail`, `eventItems`, `itemTitle`, `itemText`, `eventState`, `presentEvent`, `presentationCache`, `PresentedEvent` interface, `presentedEvents`, `latestPresentedEvent`, `latestAuditStep`, `auditStepTitle`, `auditStepDetail`, `MAX_VISIBLE_EVENTS` (if no longer referenced).

**Files:**
- Modify: `frontend/src/views/chat/modules/research-process-panel.vue`

**Acceptance Criteria:**
- [ ] All referenced helpers above are deleted
- [ ] `pnpm typecheck` passes
- [ ] `pnpm lint` passes for the modified file
- [ ] `toolLabel` and `legacyToolLabel` retained for the legacy branch

**Verify:** `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint` → both exit 0.

**Steps:**

- [ ] **Step 1: Identify retained references**

Grep for the helpers listed above. Confirm which are now dead.

- [ ] **Step 2: Delete dead helpers**

Delete each confirmed-dead helper, the `PresentedEvent` interface, and the `presentationCache` constant.

- [ ] **Step 3: Run verification**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Commit**

```bash
cd /home/charles/PaiSmart
git add frontend/src/views/chat/modules/research-process-panel.vue
git commit -m "refactor(chat): remove legacy research-event helpers"
```

---

## Task 6: User verification of the visual redesign

**Goal:** User confirms in dev mode that the redesigned panel meets the spec.

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:
```yaml
AskUserQuestion:
  question: "Does the redesigned research process panel now show useful info (paper names for search, sections for locate, page list + collapsed passages for read, one-line decisions for think) with no metric plumbing (no badges, no state pills, no duration/tokens, no pass counters), and the audit ledger rows still open references on click?"
  header: "Verification"
  options:
    - label: "Looks good"
      description: "Cards are minimal, the agentic RAG flow is easy to scan, references still open"
    - label: "Needs rework"
      description: "Something still looks wrong, or a piece of useful info is hidden"
```

**If the user selects the negative option:** Investigate which area failed, fix in a follow-up commit, re-verify.

**Acceptance Criteria:**
- [ ] User confirms cards are minimal and useful
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
  - [ ] No count badges anywhere
  - [ ] No state pills (`running` / `completed` / `failed` text)
  - [ ] No duration or token text
  - [ ] Search cards show just paper titles
  - [ ] Locate cards show just section names
  - [ ] Read cards show page list with collapsed passages (`▸ 5 passages`)
  - [ ] Think cards show a `Decided to …` decision line
  - [ ] Audit ledger (cited / read / candidate) opens references on click

- [ ] Start a new research answer. Confirm:
  - [ ] Status header pulses while running
  - [ ] Cards reveal one by one as events arrive
  - [ ] Multiple think passes each show their own decision

- [ ] Trigger or replay an error. Confirm:
  - [ ] The error card shows the full error message
  - [ ] Status header shows the failed state

- [ ] Toggle dark mode. Confirm:
  - [ ] All cards render with the dark-mode tokens
  - [ ] No white-on-white or other contrast issues

- [ ] **Step 3: Run final typecheck + lint**

Run: `cd /home/charles/PaiSmart/frontend && pnpm typecheck && pnpm lint`
Expected: both exit 0.

- [ ] **Step 4: Call AskUserQuestion**

Use the question and options from the verification block above.

- [ ] **Step 5: If user says "Looks good"** — done. No commit required.

```json:metadata
{"files": [], "verifyCommand": "", "acceptanceCriteria": ["user confirms cards are minimal and useful", "user confirms evidence rows open references", "user confirms no regression in streaming flow"], "requiresUserVerification": true, "userVerificationPrompt": "Does the redesigned research process panel now show useful info (paper names for search, sections for locate, page list + collapsed passages for read, one-line decisions for think) with no metric plumbing (no badges, no state pills, no duration/tokens, no pass counters), and the audit ledger rows still open references on click?"}
```

---

## Self-Review

**1. Spec coverage:**
- Drop count badges ✓ Task 3.5 (deletes `badge` field, no `.phase-card__badge` in template)
- Drop state pills ✓ Task 3.5 (deletes `.phase-card__state-pill`)
- Drop duration suffix ✓ Task 3.5 (deletes `durationMs` field)
- Drop token counts ✓ Task 3.5 (drops the think branch from `oneLinerOf` → `detailOf`)
- Search shows just paper names ✓ Task 3.5 (search items already contain titles)
- Locate shows just section names ✓ Task 3.5 (locate items already contain section + page)
- Read stays collapsed ✓ Task 3.5 (already in place from Task 2)
- Think shows decision ✓ Task 3.5 (new `decisionOf` + `decision` field)
- Status header: pulse + activity ✓ Task 3.6
- Audit ledger three groups preserved ✓ Task 4
- Click-to-open-reference preserved ✓ Task 4
- Verification ✓ Task 6

**2. Placeholder scan:** No TBDs, no "implement later", no "fill in details", no "add appropriate error handling".

**3. Type consistency:** `Phase`, `PhaseView`, `PhaseItem`, `PhaseInput` defined once in Task 1 (PhaseView updated in Task 3.5). `phaseOf`, `buildPhaseView`, `getPhaseIcon`, `decisionOf`, `detailOf`, `itemsOf`, `auditStepToPhaseInput` all consistent across tasks.

**4. Verification requirement scan:** YES — user verification is part of the spec and the user explicitly said the display was "too abstract". Task 6 has `requiresUserVerification: true` and the standard verification block.

No gaps. Plan ready.