<script setup lang="ts">
import { computed } from 'vue';

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

function eventTitle(event: Api.Chat.ResearchProgressEvent) {
  const type = eventType(event);
  if (type === 'job_started') return 'Research started';
  if (type === 'model_call_started')
    return `Thinking${event.attempt && event.attempt > 1 ? `, pass ${event.attempt}` : ''}`;
  if (type === 'model_call_completed') return `Model pass ${event.attempt || ''} completed`.trim();
  if (type === 'answer_completed') return 'Answer prepared';
  if (type === 'job_failed') return 'Research failed';
  if (type === 'job_cancelled') return 'Research cancelled';
  if (type === 'tool_started') return toolLabel(event.tool, true);
  if (type === 'tool_completed') return toolLabel(event.tool, false);
  return 'Research progress';
}

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

// Runtime events are intentionally rendered by tool-specific deterministic branches.
// eslint-disable-next-line complexity
function eventDetail(event: Api.Chat.ResearchProgressEvent) {
  const input = event.input || {};
  const output = event.output || {};
  const type = eventType(event);
  if (type === 'model_call_completed') {
    const usage = event.usage || {};
    const tokens = Number(usage.totalTokens || 0);
    return [event.durationMs ? `${event.durationMs} ms` : '', tokens ? `${tokens.toLocaleString()} tokens` : '']
      .filter(Boolean)
      .join(' · ');
  }
  if (type === 'job_failed') {
    const message = event.message || 'The harness stopped before completing the answer.';
    return event.errorType ? `${event.errorType}: ${message}` : message;
  }
  if (event.tool === 'search_paper_candidates') {
    const query = String(input.query || '').trim();
    const count = Number(output.resultCount || 0);
    return type === 'tool_completed'
      ? `${count} paper${count === 1 ? '' : 's'} returned${query ? ` for “${query}”` : ''}`
      : query || 'Searching the authorized paper set';
  }
  if (event.tool === 'find_reading_locations') {
    const query = String(input.query || '').trim();
    const count = Number(output.resultCount || 0);
    return type === 'tool_completed'
      ? `${count} location${count === 1 ? '' : 's'} found${event.durationMs ? ` · ${event.durationMs} ms` : ''}`
      : query || 'Locating relevant sections and pages';
  }
  if (event.tool === 'read_locations') {
    const count = Number(output.readCount || input.locationCount || 0);
    const evidenceCount = Number(output.evidenceCount || 0);
    const pages = Array.isArray(output.pages) ? output.pages.join(', ') : '';
    return type === 'tool_completed'
      ? `${count} location${count === 1 ? '' : 's'} read · ${evidenceCount} evidence passage${evidenceCount === 1 ? '' : 's'}${pages ? ` · pages ${pages}` : ''}`
      : `${count} location${count === 1 ? '' : 's'} selected`;
  }
  if (event.tool === 'get_citation_edges') return `${Number(output.edgeCount || 0)} citation edges`;
  if (event.tool === 'get_research_skill') return String(input.skillId || output.skillId || '');
  return event.durationMs ? `${event.durationMs} ms` : '';
}

function eventItems(event: Api.Chat.ResearchProgressEvent) {
  const output = event.output || {};
  if (Array.isArray(output.evidence)) return output.evidence.slice(0, 10);
  if (Array.isArray(output.locations)) return output.locations.slice(0, 10);
  if (Array.isArray(output.papers)) return output.papers.slice(0, 10);
  return [];
}

function itemTitle(item: Record<string, any>) {
  return [item.title, item.section, item.page ? `p. ${item.page}` : ''].filter(Boolean).join(' · ');
}

function itemText(item: Record<string, any>) {
  return String(item.quote || '').trim();
}

function eventState(event: Api.Chat.ResearchProgressEvent) {
  const type = eventType(event);
  if (type === 'job_failed' || event.status === 'failed') return 'failed';
  if (type === 'tool_started' || type === 'model_call_started') return 'running';
  return 'completed';
}

function presentEvent(event: Api.Chat.ResearchProgressEvent, index: number): PresentedEvent {
  const cached = presentationCache.get(event);
  if (cached) return cached;

  const presented: PresentedEvent = {
    key: event.sequence || `${event.type}:${event.timestamp}:${index}`,
    title: eventTitle(event),
    detail: eventDetail(event),
    durationMs: event.durationMs,
    state: eventState(event),
    items: eventItems(event).map((item, itemIndex) => ({
      key: item.evidenceId || item.locationRef || item.paperId || itemIndex,
      title: itemTitle(item),
      text: itemText(item),
      reference: String(item.evidenceId || item.locationRef || '')
    }))
  };
  presentationCache.set(event, presented);
  return presented;
}

const presentedEvents = computed(() =>
  events.value.slice(-MAX_VISIBLE_EVENTS).map((event, index) => presentEvent(event, index))
);
const latestPresentedEvent = computed(() => presentedEvents.value[presentedEvents.value.length - 1]);
const latestAuditStep = computed(() => auditSteps.value[auditSteps.value.length - 1]);

const auditDiagnostics = computed(() => auditTrail.value?.diagnostics || {});

const auditGroups = computed(() => [
  {
    key: 'cited',
    title: 'Cited',
    rows: auditEvidence.value.filter(row => row.status === 'cited')
  },
  {
    key: 'read',
    title: 'Read but not cited',
    rows: auditEvidence.value.filter(row => row.status === 'read' || row.status === 'unavailable_visual')
  },
  {
    key: 'candidate',
    title: 'Candidate only',
    rows: auditEvidence.value.filter(row => !row.status || row.status === 'candidate')
  }
]);

function legacyToolLabel(event: Api.Chat.AgentToolEvent) {
  return toolLabel(event.tool, event.status === 'executing');
}

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

function evidenceTitle(row: Api.Chat.ResearchAuditEvidence) {
  return [row.paperTitle || row.originalFilename || row.paperId || 'Evidence', row.pageNumber ? `p. ${row.pageNumber}` : '']
    .filter(Boolean)
    .join(' · ');
}

function evidenceText(row: Api.Chat.ResearchAuditEvidence) {
  return row.content || row.evidenceSnippet || row.matchedChunkText || row.anchorText || row.sectionTitle || '';
}

function evidenceMeta(row: Api.Chat.ResearchAuditEvidence) {
  return [row.citationRef, row.sourceQuoteRef, row.evidenceRef, row.locationRef, row.sectionTitle]
    .filter(Boolean)
    .join(' · ');
}

function evidenceKey(row: Api.Chat.ResearchAuditEvidence, index: number) {
  return String(row.auditEvidenceId || row.sourceQuoteRef || row.evidenceRef || row.locationRef || row.paperId || index);
}

function evidenceVisualLabel(row: Api.Chat.ResearchAuditEvidence) {
  if (row.pageScreenshotAvailable) return row.bboxJson ? 'PDF page + bbox' : 'PDF page';
  if (row.tableScreenshotAvailable) return 'Table image';
  if (row.figureScreenshotAvailable) return 'Figure image';
  return 'Text only';
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

      <div v-if="auditEvidence.length" class="research-process__ledger">
        <section v-for="group in auditGroups" :key="group.key" class="research-process__ledger-group">
          <div class="research-process__ledger-heading">
            <strong>{{ group.title }}</strong>
            <span>{{ group.rows.length }}</span>
          </div>
          <button
            v-for="(row, rowIndex) in group.rows"
            :key="evidenceKey(row, rowIndex)"
            type="button"
            class="research-process__evidence-row"
            :disabled="!canOpenEvidence(row)"
            @click="openEvidence(row)"
          >
            <div class="research-process__evidence-title">{{ evidenceTitle(row) }}</div>
            <p v-if="evidenceText(row)" class="research-process__result-text">{{ evidenceText(row) }}</p>
            <div class="research-process__evidence-meta">{{ evidenceMeta(row) }}</div>
            <div class="research-process__visual-state">{{ evidenceVisualLabel(row) }}</div>
          </button>
        </section>
      </div>
    </div>

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

.research-process__status-title {
  font-size: 14px;
  font-weight: 700;
}

.research-process__status-detail,
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

.research-process__result-text {
  margin: 4px 0 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
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

.research-process__ledger-heading span {
  color: var(--color-text-muted);
}

.research-process__evidence-row {
  width: 100%;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  padding: 9px 10px;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.research-process__evidence-row:hover:not(:disabled),
.research-process__evidence-row:focus-visible {
  border-color: var(--color-primary);
  background: var(--color-accent-soft-bg);
}

.research-process__evidence-row:disabled {
  cursor: default;
}

.research-process__evidence-title {
  overflow-wrap: anywhere;
  font-size: 12px;
  font-weight: 700;
}

.research-process__evidence-meta,
.research-process__visual-state {
  margin-top: 4px;
  overflow-wrap: anywhere;
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.research-process__visual-state {
  font-family: inherit;
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
</style>
