<script setup lang="ts">
import { computed } from 'vue';

defineOptions({ name: 'ResearchProcessPanel' });

const props = defineProps<{
  message?: Api.Chat.Message | null;
}>();

const events = computed(() => props.message?.researchEvents || []);
const legacyTools = computed(() => props.message?.toolEvents || []);
const latestEvent = computed(() => events.value[events.value.length - 1]);
const isRunning = computed(() => ['pending', 'loading'].includes(props.message?.status || ''));

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

function legacyToolLabel(event: Api.Chat.AgentToolEvent) {
  return toolLabel(event.tool, event.status === 'executing');
}
</script>

<template>
  <section class="research-process">
    <div class="research-process__status">
      <span class="research-process__status-dot" :class="{ 'is-running': isRunning }" />
      <div>
        <div class="research-process__status-title">
          {{ latestEvent ? eventTitle(latestEvent) : isRunning ? 'Researching' : 'No process selected' }}
        </div>
        <div v-if="latestEvent && eventDetail(latestEvent)" class="research-process__status-detail">
          {{ eventDetail(latestEvent) }}
        </div>
      </div>
    </div>

    <div v-if="events.length" class="research-process__timeline">
      <article
        v-for="event in events"
        :key="event.sequence || `${event.type}:${event.timestamp}`"
        class="research-process__event"
        :class="`is-${eventState(event)}`"
      >
        <span class="research-process__marker" />
        <div class="research-process__event-body">
          <div class="research-process__event-heading">
            <strong>{{ eventTitle(event) }}</strong>
            <span v-if="event.durationMs">{{ event.durationMs }} ms</span>
          </div>
          <div v-if="eventDetail(event)" class="research-process__event-detail">{{ eventDetail(event) }}</div>
          <div v-if="eventItems(event).length" class="research-process__results">
            <div
              v-for="(item, index) in eventItems(event)"
              :key="item.evidenceId || item.locationRef || item.paperId || index"
              class="research-process__result"
            >
              <div v-if="itemTitle(item)" class="research-process__result-title">{{ itemTitle(item) }}</div>
              <p v-if="itemText(item)" class="research-process__result-text">{{ itemText(item) }}</p>
              <div v-if="item.evidenceId || item.locationRef" class="research-process__result-ref">
                {{ item.evidenceId || item.locationRef }}
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
