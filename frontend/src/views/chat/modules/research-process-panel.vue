<script setup lang="ts">
import { computed } from 'vue';

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
  decision: string;
  detail: string;
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

function searchHeadline(inputData: Record<string, any>): string {
  const query = String(inputData.query || '').trim();
  return query ? `Searched papers · "${query}"` : 'Searched papers';
}

function locateHeadline(inputData: Record<string, any>): string {
  const query = String(inputData.query || '').trim();
  return query ? `Located sections · "${query}"` : 'Located sections';
}

function readHeadline(inputData: Record<string, any>, output: Record<string, any>): string {
  const count = Number(output.evidenceCount || output.readCount || inputData.locationCount || 0);
  return count > 0 ? `Read ${count} passage${count === 1 ? '' : 's'}` : 'Read passages';
}

function citeHeadline(output: Record<string, any>): string {
  const count = Number(output.edgeCount || 0);
  return count > 0 ? `Traced ${count} citation${count === 1 ? '' : 's'}` : 'Traced citations';
}

// eslint-disable-next-line complexity
function headlineOf(input: PhaseInput, phase: Phase): string {
  const inputData = input.input || {};
  const output = input.output || {};
  switch (phase) {
    case 'search':
      return searchHeadline(inputData);
    case 'locate':
      return locateHeadline(inputData);
    case 'read':
      return readHeadline(inputData, output);
    case 'cite':
      return citeHeadline(output);
    case 'think':
      return 'Reasoning';
    case 'answer':
      return 'Answer prepared';
    case 'error':
      return 'Research failed';
    default:
      return 'Research progress';
  }
}

function searchDecisionText(input: Record<string, any>): string {
  const query = String(input.query || '').trim();
  return query ? `→ Search for "${query}"` : '→ Search papers';
}

function locateDecisionText(input: Record<string, any>): string {
  const query = String(input.query || '').trim();
  return query ? `→ Locate sections about "${query}"` : '→ Locate sections';
}

function readDecisionText(input: Record<string, any>, output: Record<string, any>): string {
  const count = Number(output.evidenceCount || output.readCount || input.locationCount || 0);
  return count > 0 ? `→ Read ${count} passage${count === 1 ? '' : 's'}` : '→ Read passages';
}

function decisionOf(_input: PhaseInput, allEvents: PhaseInput[], index: number): string {
  const next = allEvents[index + 1];
  if (!next) return '→ Reasoning';
  const tool = next.tool || '';
  const type = next.eventType || next.type || '';
  const nextInput = next.input || {};
  const nextOutput = next.output || {};

  switch (tool) {
    case 'search_paper_candidates':
      return searchDecisionText(nextInput);
    case 'find_reading_locations':
      return locateDecisionText(nextInput);
    case 'read_locations':
      return readDecisionText(nextInput, nextOutput);
    case 'get_citation_edges':
      return '→ Trace citation edges';
    default:
      break;
  }
  if (type === 'answer_completed') return '→ Final answer';
  return '→ Reasoning';
}

function detailOf(input: PhaseInput, phase: Phase): string {
  const output = input.output || {};
  switch (phase) {
    case 'read': {
      const pages = Array.isArray(output.pages) ? output.pages : [];
      return pages.length ? `pages ${pages.join(', ')}` : '';
    }
    case 'error':
      return input.message || 'The harness stopped before completing the answer.';
    default:
      return '';
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

function buildPhaseView(input: PhaseInput, allEvents: PhaseInput[], index: number): PhaseView {
  const phase = phaseOf(input);
  const state = stateOf(input);
  return {
    key: `${phase}:${index}:${input.attempt ?? ''}`,
    phase,
    state,
    headline: headlineOf(input, phase),
    decision: phase === 'think' ? decisionOf(input, allEvents, index) : '',
    detail: detailOf(input, phase),
    items: itemsOf(input, phase)
  };
}

defineOptions({ name: 'ResearchProcessPanel' });

const props = defineProps<{
  message?: Api.Chat.Message | null;
}>();

const emit = defineEmits<{
  (
    e: 'openReference',
    payload: Api.Chat.ReferenceEvidence & { paperTitle: string; referenceNumber: number; conversationRecordId?: number }
  ): void;
}>();

const auditTrail = computed(() => props.message?.researchAuditTrail || null);
const auditEvidence = computed(() => auditTrail.value?.evidence || []);
const auditSteps = computed(() => auditTrail.value?.steps || []);
const hasAuditTrail = computed(() => auditSteps.value.length > 0 || auditEvidence.value.length > 0);
const events = computed(() => props.message?.researchEvents || []);
const legacyTools = computed(() => props.message?.toolEvents || []);
const isRunning = computed(() => ['pending', 'loading'].includes(props.message?.status || ''));
const MAX_VISIBLE_EVENTS = 100;

function toolLabel(tool?: string, running = false) {
  const labels: Record<string, [string, string]> = {
    search_paper_candidates: ['Searching papers', 'Searched papers'],
    find_reading_locations: ['Finding relevant locations', 'Found relevant locations'],
    read_locations: ['Reading paper locations', 'Read paper locations'],
    get_citation_edges: ['Tracing citations', 'Traced citations'],
    get_research_skill: ['Loading research guidance', 'Loaded research guidance']
  };
  const pair = labels[tool || ''];
  if (pair) return running ? pair[0] : pair[1];
  return running ? `Running ${tool || 'tool'}` : `Completed ${tool || 'tool'}`;
}

// Introduced for the phase-card template migration in Task 2.
const presentedPhaseCards = computed<PhaseView[]>(() => {
  // Filter out *_started events so each tool/model call shows once as completion.
  const completed = events.value.slice(-MAX_VISIBLE_EVENTS).filter(event => {
    const type = eventTypeOf(event);
    return type !== 'tool_started' && type !== 'model_call_started';
  });
  return collapseConsecutivePhases(completed);
});

function collapseConsecutivePhases(inputs: PhaseInput[]): PhaseView[] {
  const collapsed: PhaseView[] = [];
  for (let index = 0; index < inputs.length; index += 1) {
    const input = inputs[index];
    const last = collapsed[collapsed.length - 1];
    const view = buildPhaseView(input, inputs, index);
    if (last && last.phase === view.phase) {
      collapsed[collapsed.length - 1] = view;
    } else {
      collapsed.push(view);
    }
  }
  return collapsed;
}

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

const presentedAuditPhaseCards = computed(() => {
  const inputs = auditSteps.value.map(auditStepToPhaseInput);
  return collapseConsecutivePhases(inputs);
});
const latestPresentedPhase = computed<PhaseView | undefined>(() => {
  if (hasAuditTrail.value && auditSteps.value.length) {
    const inputs = auditSteps.value.map(auditStepToPhaseInput);
    return buildPhaseView(inputs[inputs.length - 1], inputs, inputs.length - 1);
  }
  const cards = presentedPhaseCards.value;
  return cards[cards.length - 1];
});

const auditDiagnostics = computed(() => auditTrail.value?.diagnostics || {});

const citedEvidence = computed(() => auditEvidence.value.filter(row => row.status === 'cited'));

function legacyToolLabel(event: Api.Chat.AgentToolEvent) {
  return toolLabel(event.tool, event.status === 'executing');
}

function evidenceTitle(row: Api.Chat.ResearchAuditEvidence) {
  return [
    row.paperTitle || row.originalFilename || row.paperId || 'Evidence',
    row.pageNumber ? `p. ${row.pageNumber}` : ''
  ]
    .filter(Boolean)
    .join(' · ');
}

function evidenceText(row: Api.Chat.ResearchAuditEvidence) {
  return row.content || row.evidenceSnippet || row.matchedChunkText || row.anchorText || row.sectionTitle || '';
}

function evidenceKey(row: Api.Chat.ResearchAuditEvidence, index: number) {
  return String(
    row.auditEvidenceId || row.sourceQuoteRef || row.evidenceRef || row.locationRef || row.paperId || index
  );
}

function canOpenEvidence(row: Api.Chat.ResearchAuditEvidence) {
  return Boolean(row.paperId || row.sourceQuoteRef || evidenceText(row));
}

function referenceNumber(row: Api.Chat.ResearchAuditEvidence) {
  if (typeof row.referenceNumber === 'number' && row.referenceNumber > 0) return row.referenceNumber;
  const matched = String(row.citationRef || '').match(/\[(\d+)]/);
  return matched ? Number.parseInt(matched[1], 10) : 0;
}

function openEvidence(row: Api.Chat.ResearchAuditEvidence) {
  if (!canOpenEvidence(row)) return;
  emit('openReference', {
    ...row,
    referenceNumber: referenceNumber(row),
    paperTitle: row.paperTitle || row.originalFilename || row.paperId || 'Evidence',
    evidenceSnippet: row.evidenceSnippet || row.content || row.anchorText || '',
    matchedChunkText: row.matchedChunkText || row.content || row.anchorText || '',
    conversationRecordId: props.message?.conversationRecordId
  });
}
</script>

<template>
  <section class="research-process">
    <div class="research-process__status">
      <span
        class="research-process__status-dot"
        :class="{ 'is-running': isRunning, 'is-failed': latestPresentedPhase?.phase === 'error' }"
      />
      <div class="research-process__status-title">
        {{
          latestPresentedPhase?.phase === 'error'
            ? 'Research failed'
            : (latestPresentedPhase?.headline ?? (isRunning ? 'Researching…' : 'Research complete'))
        }}
      </div>
    </div>

    <div v-if="hasAuditTrail" class="research-process__audit">
      <div class="research-process__metrics">
        <div>
          <strong>{{ auditDiagnostics.searchedPaperCount || 0 }}</strong>
          <span>Papers</span>
        </div>
        <div>
          <strong>{{ auditDiagnostics.readEvidenceCount || 0 }}</strong>
          <span>Read</span>
        </div>
        <div>
          <strong>{{ auditDiagnostics.citedEvidenceCount || 0 }}</strong>
          <span>Cited</span>
        </div>
        <div>
          <strong>{{ auditDiagnostics.visualEvidenceAvailableCount || 0 }}</strong>
          <span>Visual</span>
        </div>
      </div>

      <div v-if="presentedAuditPhaseCards.length" class="research-process__timeline">
        <article
          v-for="card in presentedAuditPhaseCards"
          :key="card.key"
          class="phase-card"
          :class="[`phase-card--${card.phase}`, `is-${card.state}`]"
        >
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
            <details
              v-if="card.items.length && card.phase === 'read'"
              class="phase-card__items phase-card__items--collapsed"
            >
              <summary>{{ card.items.length }} passage{{ card.items.length === 1 ? '' : 's' }}</summary>
              <div v-for="item in card.items" :key="item.key" class="phase-card__item">
                <span v-if="item.title" class="phase-card__item-title">{{ item.title }}</span>
                <p v-if="item.text" class="phase-card__item-text">{{ item.text }}</p>
              </div>
            </details>
          </div>
        </article>
      </div>

      <div v-if="citedEvidence.length" class="research-process__ledger">
        <section class="research-process__ledger-group">
          <button
            v-for="(row, rowIndex) in citedEvidence"
            :key="evidenceKey(row, rowIndex)"
            type="button"
            class="phase-card phase-card--ledger"
            :disabled="!canOpenEvidence(row)"
            @click="openEvidence(row)"
          >
            <span class="phase-card__heading">{{ evidenceTitle(row) }}</span>
          </button>
        </section>
      </div>
    </div>

    <div v-else-if="events.length" class="research-process__timeline">
      <article
        v-for="card in presentedPhaseCards"
        :key="card.key"
        class="phase-card"
        :class="[`phase-card--${card.phase}`, `is-${card.state}`]"
      >
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
          <details
            v-if="card.items.length && card.phase === 'read'"
            class="phase-card__items phase-card__items--collapsed"
          >
            <summary>{{ card.items.length }} passage{{ card.items.length === 1 ? '' : 's' }}</summary>
            <div v-for="item in card.items" :key="item.key" class="phase-card__item">
              <span v-if="item.title" class="phase-card__item-title">{{ item.title }}</span>
              <p v-if="item.text" class="phase-card__item-text">{{ item.text }}</p>
            </div>
          </details>
        </div>
      </article>
    </div>

    <div v-else-if="legacyTools.length" class="research-process__timeline">
      <article
        v-for="event in legacyTools"
        :key="event.id || `${event.tool}:${event.timestamp}`"
        class="research-process__event"
      >
        <span class="research-process__marker" />
        <div class="research-process__event-body">
          <div class="research-process__event-heading">
            <strong>{{ legacyToolLabel(event) }}</strong>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="research-process__empty">
      Select an assistant answer with research activity to review its model and retrieval process.
    </div>
  </section>
</template>

<style scoped>
.research-process {
  min-height: 0;
  overflow: auto;
  padding: 14px 16px 24px;
  color: var(--color-text);
}

.research-process__status {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  border-bottom: 1px solid var(--color-border);
  padding: 2px 0 14px;
}

.research-process__status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--color-success);
  flex: 0 0 auto;
}

.research-process__status-dot.is-running {
  background: var(--color-research);
  animation: research-process-pulse 1.4s ease-in-out infinite;
}

.research-process__status-dot.is-failed {
  background: var(--color-error);
}

@keyframes research-process-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

.research-process__marker {
  display: none;
}

.research-process__status-title {
  font-size: 14px;
  font-weight: 700;
}

.research-process__event-detail,
.research-process__result-ref {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.research-process__timeline {
  padding-top: 8px;
}

.research-process__audit {
  display: grid;
  gap: 14px;
  padding-top: 12px;
}

.research-process__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.research-process__metrics > div {
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface-alt);
  padding: 8px;
}

.research-process__metrics strong {
  display: block;
  font-size: 15px;
  line-height: 1.1;
}

.research-process__metrics span {
  display: block;
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.research-process__event {
  position: relative;
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr);
  gap: 10px;
  padding: 11px 0;
}

.research-process__event:not(:last-child)::after {
  position: absolute;
  top: 23px;
  bottom: -3px;
  left: 3px;
  width: 1px;
  background: var(--color-border);
  content: '';
}

.research-process__marker {
  margin-top: 5px;
}

.research-process__event-heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
}

.research-process__event-heading span {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 11px;
}

.research-process__results {
  margin-top: 8px;
  border-left: 2px solid var(--color-border);
  padding-left: 10px;
}

.research-process__result {
  padding: 6px 0;
}

.research-process__result + .research-process__result {
  border-top: 1px solid var(--color-border);
}

.research-process__result-title {
  overflow-wrap: anywhere;
  font-size: 12px;
  font-weight: 650;
}

.research-process__result-ref {
  overflow-wrap: anywhere;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.research-process__ledger {
  display: grid;
  gap: 12px;
}

.research-process__ledger-group {
  display: grid;
  gap: 7px;
}

.research-process__ledger-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--color-text);
  font-size: 12px;
}

.research-process__empty {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
  color: var(--color-text-muted);
  padding: 24px;
  text-align: center;
}

.phase-card {
  padding: 12px 0;
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

.phase-card--ledger {
  display: block;
  width: 100%;
  padding: 4px 8px;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
  border-radius: 4px;
  transition: background 0.12s ease;
}

.phase-card--ledger:hover:not(:disabled),
.phase-card--ledger:focus-visible {
  background: var(--color-accent-soft-bg);
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
</style>
