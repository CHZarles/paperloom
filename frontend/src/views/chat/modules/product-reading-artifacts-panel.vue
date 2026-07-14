<script setup lang="ts">
defineOptions({ name: 'ProductReadingArtifactsPanel' });

const props = defineProps<{
  artifacts?: Api.Chat.ReadingTurnArtifacts | null;
  legacyItems?: Api.Chat.ProductStateItem[] | null;
}>();

const emit = defineEmits<{
  (
    e: 'openSourceQuote',
    payload: {
      sourceQuoteRef?: string;
      paperId?: string;
      paperTitle?: string;
      originalFilename?: string;
      referenceNumber?: number;
      evidenceSnippet?: string;
      matchedChunkText?: string;
    }
  ): void;
}>();

const chatStore = useChatStore();

const goal = computed(() => props.artifacts?.goalCard || null);
const shortlist = computed(() =>
  (props.artifacts?.paperShortlist?.items || [])
    .filter(item => displayPaperTitle(item))
    .slice(0, 5)
);
const groupedShortlist = computed(() => {
  const groups: Array<{
    key: string;
    title: string;
    items: Api.Chat.ReadingPaperShortlistItem[];
  }> = [];
  const items = shortlist.value;
  if (!items.length) return groups;

  groups.push({
    key: 'start-here',
    title: 'Start here',
    items: [items[0]]
  });

  const byRole = new Map<string, Api.Chat.ReadingPaperShortlistItem[]>();
  items.slice(1).forEach(item => {
    const key = normalizedRole(item.role) || 'needs-role';
    byRole.set(key, [...(byRole.get(key) || []), item]);
  });

  const roleOrder = ['survey', 'benchmark', 'method', 'critique', 'background', 'example', 'needs-role'];
  roleOrder.forEach(role => {
    const roleItems = byRole.get(role);
    if (!roleItems?.length) return;
    groups.push({
      key: role,
      title: roleGroupLabel(role),
      items: roleItems
    });
  });

  byRole.forEach((roleItems, role) => {
    if (roleOrder.includes(role) || !roleItems.length) return;
    groups.push({
      key: role,
      title: roleGroupLabel(role),
      items: roleItems
    });
  });

  return groups;
});
const readingSteps = computed(() =>
  (props.artifacts?.readingPlan?.steps || [])
    .filter(step => step.locationLabel || step.paperTitle)
    .slice(0, 5)
);
const evidenceRows = computed(() =>
  (props.artifacts?.claimEvidencePanel?.rows || [])
    .filter(row => row.claim || row.quote)
    .slice(0, 5)
);
const decisionRows = computed(() => {
  const frame = props.artifacts?.intentFrame;
  const rows: Array<{
    id: string;
    title: string;
    meta?: string;
    text?: string;
    status?: string;
  }> = [];
  (frame?.paperQueryTexts || [])
    .filter(Boolean)
    .slice(0, 2)
    .forEach((query, index) => {
      rows.push({
        id: `paper-query-${index}`,
        title: 'Paper discovery',
        text: query,
        status: 'Metadata candidates only until a passage is read'
      });
    });
  (frame?.locationQueryPlans || [])
    .filter(plan => plan?.queryText || plan?.intent || plan?.sectionRoles?.length)
    .slice(0, 3)
    .forEach((plan, index) => {
      rows.push({
        id: `location-plan-${index}`,
        title: locationPlanTitle(plan),
        meta: locationPlanMeta(plan),
        text: plan.queryText || undefined,
        status: 'Typed plan declared before location retrieval'
      });
    });
  if (!rows.length && frame?.planningStatus && frame.planningStatus !== 'not_planned') {
    rows.push({
      id: 'planning-status',
      title: 'Planning state',
      status: planningStatusLabel(frame.planningStatus)
    });
  }
  const trace = props.artifacts?.traceSummary;
  (trace?.steps || [])
    .filter(step => step?.label || step?.detail || step?.status)
    .slice(0, 6)
    .forEach((step, index) => {
      rows.push({
        id: `trace-step-${index}`,
        title: step.label || traceStageLabel(step.stage),
        meta: traceStageLabel(step.stage),
        text: traceStepDetail(step),
        status: traceStatusLabel(step.status)
      });
    });
  const evidenceText = evidenceSummaryText(trace?.evidence);
  if (evidenceText) {
    rows.push({
      id: 'trace-evidence-summary',
      title: 'Evidence ledger',
      text: evidenceText,
      status: missingEvidenceLabels(trace?.evidence?.missing).join(' · ')
    });
  }
  const claimText = claimSummaryText(trace?.claims);
  if (claimText) {
    rows.push({
      id: 'trace-claim-summary',
      title: 'Claim graph',
      text: claimText,
      status: claimTraceStatus(trace?.claims)
    });
  }
  const verificationText = verificationSummaryText(trace?.verification);
  if (verificationText) {
    rows.push({
      id: 'trace-verification-summary',
      title: 'Verification',
      text: verificationText,
      status: traceStatusLabel(trace?.verification?.resultStatus || trace?.verification?.requiredEvidenceStatus)
    });
  }
  return rows;
});
const missingEvidence = computed(() => props.artifacts?.missingEvidence || null);
const uncertaintyNotes = computed(() => (props.artifacts?.uncertaintyNotes || []).filter(Boolean).slice(0, 3));
const hasArtifacts = computed(
  () =>
    Boolean(goal.value?.interpretedGoal || goal.value?.scopeLabel || typeof goal.value?.readablePaperCount === 'number') ||
    decisionRows.value.length > 0 ||
    shortlist.value.length > 0 ||
    readingSteps.value.length > 0 ||
    evidenceRows.value.length > 0 ||
    Boolean(missingEvidence.value?.explanation) ||
    uncertaintyNotes.value.length > 0
);

function displayPaperTitle(item: Api.Chat.ReadingPaperShortlistItem) {
  return (item.title || item.originalFilename || '').trim();
}

function compactPaperMeta(item: Api.Chat.ReadingPaperShortlistItem) {
  const parts: string[] = [];
  const authors = (item.authors || []).filter(Boolean).slice(0, 3).join(', ');
  if (authors) parts.push(authors);
  if (item.year) parts.push(String(item.year));
  if (item.venue) parts.push(item.venue);
  return parts.join(' · ');
}

function scopeText() {
  const currentGoal = goal.value;
  if (!currentGoal) return '';
  const parts: string[] = [];
  if (currentGoal.scopeLabel) parts.push(currentGoal.scopeLabel);
  if (typeof currentGoal.readablePaperCount === 'number') parts.push(`${currentGoal.readablePaperCount} papers`);
  if (currentGoal.scopeLocked) parts.push('locked');
  return parts.join(' · ');
}

function rowActions(actions?: Api.Chat.ReadingUiAction[] | null) {
  return (actions || []).filter(action => action?.action && action.payload).slice(0, 3);
}

function actionIcon(action: string) {
  if (action === 'OPEN_SOURCE_QUOTE') return 'quote';
  if (action === 'READ_LOCATION') return 'book-open';
  if (action === 'LIST_LOCATIONS') return 'list-tree';
  if (action === 'REFINE_GOAL') return 'sliders-horizontal';
  return 'message-square-plus';
}

function actionLabel(action: Api.Chat.ReadingUiAction) {
  if (action.label) return action.label;
  if (action.action === 'OPEN_SOURCE_QUOTE') return 'Open citation';
  if (action.action === 'READ_LOCATION') return 'Read';
  if (action.action === 'LIST_LOCATIONS') return 'Locations';
  if (action.action === 'REFINE_GOAL') return 'Refine';
  return 'Open';
}

function roleLabel(role?: string | null) {
  const normalized = normalizedRole(role);
  const labels: Record<string, string> = {
    survey: 'Survey',
    benchmark: 'Benchmark',
    method: 'Method',
    critique: 'Critique',
    background: 'Background',
    example: 'Example'
  };
  return labels[normalized] || normalized;
}

function normalizedRole(role?: string | null) {
  return (role || '').trim().toLowerCase().replaceAll('_', '-').replaceAll(' ', '-');
}

function roleGroupLabel(role: string) {
  const labels: Record<string, string> = {
    survey: 'Survey or map',
    benchmark: 'Benchmark',
    method: 'Method details',
    critique: 'Critique or pitfalls',
    background: 'Background',
    example: 'Example',
    'needs-role': 'Needs role evidence'
  };
  return labels[role] || roleLabel(role) || 'Other';
}

function missingEvidenceLabels(missing?: string[] | null) {
  const labels: Record<string, string> = {
    visual_pdf_page_evidence: 'Visual PDF/page evidence',
    paper_content_quote: 'Quoted paper passage',
    paper_role_metadata: 'Explicit beginner role metadata',
    read_location_quote: 'Quote from selected reading location',
    checkable_reading_target: 'Checkable reading target',
    validated_final_answer: 'Validated final answer'
  };
  return (missing || [])
    .map(item => labels[item] || 'Required reading evidence')
    .filter(Boolean);
}

function locationPlanTitle(plan: Api.Chat.ReadingLocationQueryPlan) {
  const intent = intentLabel(plan.intent);
  const sections = (plan.sectionRoles || []).map(sectionRoleLabel).filter(Boolean).slice(0, 2);
  if (intent && sections.length) return `${intent} in ${sections.join(', ')}`;
  return intent || sections.join(', ') || 'Location search plan';
}

function locationPlanMeta(plan: Api.Chat.ReadingLocationQueryPlan) {
  const parts: string[] = [];
  const sourceLanguage = languageLabel(plan.sourceLanguage);
  const retrievalLanguage = languageLabel(plan.retrievalLanguage);
  if (sourceLanguage) parts.push(`Source request: ${sourceLanguage}`);
  if (retrievalLanguage) parts.push(`Search language: ${retrievalLanguage}`);
  const types = (plan.locationTypes || []).map(locationTypeLabel).filter(Boolean).slice(0, 2);
  if (types.length) parts.push(`Targets: ${types.join(', ')}`);
  return parts.join(' · ');
}

function intentLabel(value?: string | null) {
  const labels: Record<string, string> = {
    METHOD: 'Method',
    EXPERIMENT_SETUP: 'Experiment setup',
    MAIN_CLAIM: 'Main claim',
    LIMITATION: 'Limitations',
    DATASET: 'Dataset',
    BASELINE: 'Baselines',
    ABLATION: 'Ablation',
    METRIC: 'Metrics',
    GENERAL: 'General reading'
  };
  return labels[(value || '').trim().toUpperCase()] || '';
}

function sectionRoleLabel(value?: string | null) {
  const labels: Record<string, string> = {
    ABSTRACT: 'Abstract',
    INTRODUCTION: 'Introduction',
    BACKGROUND: 'Background',
    METHOD: 'Method sections',
    EXPERIMENT: 'Experiment sections',
    RESULT: 'Results',
    DISCUSSION: 'Discussion',
    LIMITATION: 'Limitations',
    CONCLUSION: 'Conclusion',
    APPENDIX: 'Appendix'
  };
  return labels[(value || '').trim().toUpperCase()] || '';
}

function locationTypeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    PAGE: 'Pages',
    SECTION: 'Sections',
    TABLE: 'Tables',
    FIGURE: 'Figures'
  };
  return labels[(value || '').trim().toUpperCase()] || '';
}

function languageLabel(value?: string | null) {
  const labels: Record<string, string> = {
    zh: 'Chinese',
    cn: 'Chinese',
    en: 'English'
  };
  const normalized = (value || '').trim().toLowerCase();
  return labels[normalized] || (normalized ? normalized.toUpperCase() : '');
}

function planningStatusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    typed_location_query_plan_missing: 'Typed location query plan is missing',
    typed_location_query_plan_observed: 'Typed location query plan observed',
    paper_query_observed: 'Paper discovery query observed'
  };
  return labels[(value || '').trim()] || 'Planning state recorded';
}

function traceStageLabel(value?: string | null) {
  const labels: Record<string, string> = {
    INTENT: 'Intent',
    RETRIEVAL: 'Retrieval',
    EVIDENCE: 'Evidence',
    CLAIMS: 'Claims',
    VERIFICATION: 'Verification'
  };
  return labels[(value || '').trim().toUpperCase()] || 'Step';
}

function traceStepDetail(step: Api.Chat.ReadingTraceStep) {
  if ((step.stage || '').trim().toUpperCase() === 'VERIFICATION') return undefined;
  return step.detail || undefined;
}

function traceStatusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    observed: 'Observed',
    satisfied: 'Satisfied',
    missing: 'Missing',
    supported: 'Supported',
    incomplete: 'Incomplete',
    COMPLETED: 'Completed',
    INCOMPLETE_PRECISE: 'Incomplete: precision preserved',
    FAILED: 'Needs attention',
    TOOL_FAILED: 'Reading step failed',
    ANSWER_SCHEMA_INVALID: 'Needs more evidence',
    CITATION_VALIDATION_FAILED: 'Citation check failed'
  };
  const normalized = (value || '').trim();
  return labels[normalized] || labels[normalized.toUpperCase()] || '';
}

function evidenceSummaryText(evidence?: Api.Chat.ReadingEvidenceSummary | null) {
  if (!evidence) return '';
  const parts: string[] = [];
  if (typeof evidence.acceptedCount === 'number') parts.push(`${evidence.acceptedCount} accepted`);
  if (typeof evidence.rejectedCount === 'number' && evidence.rejectedCount > 0) {
    parts.push(`${evidence.rejectedCount} rejected`);
  }
  if (typeof evidence.missingCount === 'number' && evidence.missingCount > 0) {
    parts.push(`${evidence.missingCount} missing`);
  }
  return parts.join(' · ');
}

function claimSummaryText(claims?: Api.Chat.ReadingClaimSummary | null) {
  if (!claims || typeof claims.totalCount !== 'number' || claims.totalCount <= 0) return '';
  const parts = [`${claims.totalCount} total`];
  if (typeof claims.supportedCount === 'number') parts.push(`${claims.supportedCount} supported`);
  if (typeof claims.underdeterminedCount === 'number' && claims.underdeterminedCount > 0) {
    parts.push(`${claims.underdeterminedCount} incomplete`);
  }
  if (typeof claims.contradictedCount === 'number' && claims.contradictedCount > 0) {
    parts.push(`${claims.contradictedCount} contradicted`);
  }
  return parts.join(' · ');
}

function claimTraceStatus(claims?: Api.Chat.ReadingClaimSummary | null) {
  if (!claims || typeof claims.totalCount !== 'number' || claims.totalCount <= 0) return '';
  if (claims.underdeterminedCount || claims.contradictedCount) return 'Incomplete';
  return 'Supported';
}

function verificationSummaryText(verification?: Api.Chat.ReadingVerificationSummary | null) {
  if (!verification) return '';
  if (verification.valid) return 'The verification check passed.';
  const gaps =
    (verification.missingRequiredEvidenceCount || 0) + (verification.failedObligationCount || 0);
  return gaps > 0
    ? `${gaps} verification check${gaps === 1 ? '' : 's'} still need evidence.`
    : 'More evidence is needed before this answer is fully verified.';
}

function performAction(
  action: Api.Chat.ReadingUiAction,
  context?: {
    paperTitle?: string | null;
    originalFilename?: string | null;
    quote?: string | null;
  }
) {
  const payload = action.payload || {};
  if (action.action === 'OPEN_SOURCE_QUOTE') {
    chatStore.setReferenceFocus({
      sourceQuoteRef: stringValue(payload.sourceQuoteRef),
      paperId: stringValue(payload.paperId),
      paperHandle: stringValue(payload.paperHandle),
      paperTitle: stringValue(payload.paperTitle),
      referenceNumber: referenceNumber(payload.citationMarker)
    });
    emit('openSourceQuote', {
      sourceQuoteRef: stringValue(payload.sourceQuoteRef),
      paperId: stringValue(payload.paperId),
      paperTitle: stringValue(payload.paperTitle) || stringValue(context?.paperTitle),
      originalFilename: stringValue(payload.originalFilename) || stringValue(context?.originalFilename),
      referenceNumber: referenceNumber(payload.citationMarker),
      evidenceSnippet: stringValue(context?.quote),
      matchedChunkText: stringValue(context?.quote)
    });
    return;
  }

  if (action.action === 'READ_LOCATION') {
    chatStore.setReferenceFocus({
      paperId: stringValue(payload.paperId),
      paperHandle: stringValue(payload.paperHandle),
      paperTitle: stringValue(payload.paperTitle),
      locationRef: stringValue(payload.locationRef),
      readingAction: 'READ_LOCATION'
    });
    setDraftMessage('读取这个位置');
    return;
  }

  if (action.action === 'LIST_LOCATIONS') {
    chatStore.setReferenceFocus({
      paperId: stringValue(payload.paperId),
      paperHandle: stringValue(payload.paperHandle),
      paperTitle: stringValue(payload.paperTitle),
      originalFilename: stringValue(payload.originalFilename),
      readingAction: 'LIST_LOCATIONS'
    });
    setDraftMessage('列出这篇论文可阅读的位置');
    return;
  }

  if (action.action === 'REFINE_GOAL') {
    chatStore.setReferenceFocus(null);
    setDraftMessage(goal.value?.interpretedGoal || '');
    return;
  }

  chatStore.setReferenceFocus({
    paperId: stringValue(payload.paperId),
    paperHandle: stringValue(payload.paperHandle),
    paperTitle: stringValue(payload.paperTitle),
    originalFilename: stringValue(payload.originalFilename)
  });
  setDraftMessage('看这篇论文');
}

function setDraftMessage(value: string) {
  if (!chatStore.input.message.trim() && value.trim()) {
    chatStore.input.message = value.trim();
  }
}

function stringValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function referenceNumber(marker: unknown) {
  if (typeof marker !== 'string') return undefined;
  const match = marker.match(/\[(\d+)]/);
  return match ? Number.parseInt(match[1], 10) : undefined;
}
</script>

<template>
  <div v-if="hasArtifacts" class="reading-artifacts">
    <section v-if="goal?.interpretedGoal || scopeText()" class="reading-artifacts__section">
      <div class="reading-artifacts__eyebrow">Goal</div>
      <div v-if="goal?.interpretedGoal" class="reading-artifacts__goal">{{ goal.interpretedGoal }}</div>
      <div v-if="scopeText()" class="reading-artifacts__meta">{{ scopeText() }}</div>
      <div v-if="rowActions(goal?.actions).length" class="reading-artifacts__actions">
        <NButton
          v-for="action in rowActions(goal?.actions)"
          :key="action.action"
          secondary
          size="tiny"
          :title="actionLabel(action)"
          :aria-label="actionLabel(action)"
          @click="performAction(action)"
        >
          <template #icon>
            <icon-lucide:sliders-horizontal v-if="actionIcon(action.action) === 'sliders-horizontal'" />
            <icon-lucide:message-square-plus v-else />
          </template>
          {{ actionLabel(action) }}
        </NButton>
      </div>
    </section>

    <section v-if="decisionRows.length" class="reading-artifacts__section">
      <div class="reading-artifacts__eyebrow">Decision Path</div>
      <div class="reading-artifacts__rows">
        <article v-for="row in decisionRows" :key="row.id" class="reading-row reading-row--single">
          <div class="reading-row__body">
            <div class="reading-row__title">{{ row.title }}</div>
            <div v-if="row.meta" class="reading-artifacts__meta">{{ row.meta }}</div>
            <div v-if="row.text" class="reading-row__text">{{ row.text }}</div>
            <div v-if="row.status" class="reading-row__status">{{ row.status }}</div>
          </div>
        </article>
      </div>
    </section>

    <section v-if="shortlist.length" class="reading-artifacts__section">
      <div class="reading-artifacts__eyebrow">Paper Shortlist</div>
      <div class="reading-shortlist-groups">
        <div v-for="group in groupedShortlist" :key="group.key" class="reading-shortlist-group">
          <div class="reading-shortlist-group__title">{{ group.title }}</div>
          <div class="reading-artifacts__rows">
            <article v-for="item in group.items" :key="item.paperHandle || displayPaperTitle(item)" class="reading-row">
              <div class="reading-row__body">
                <div class="reading-row__head">
                  <span v-if="roleLabel(item.role)" class="reading-pill">{{ roleLabel(item.role) }}</span>
                  <span class="reading-row__title">{{ displayPaperTitle(item) }}</span>
                </div>
                <div v-if="compactPaperMeta(item)" class="reading-artifacts__meta">{{ compactPaperMeta(item) }}</div>
                <div v-if="item.matchReason" class="reading-row__text">{{ item.matchReason }}</div>
                <div v-if="item.evidenceStatus" class="reading-row__status">{{ item.evidenceStatus }}</div>
                <div v-if="item.roleEvidenceStatus" class="reading-row__status">{{ item.roleEvidenceStatus }}</div>
              </div>
              <div v-if="rowActions(item.actions).length" class="reading-row__actions">
                <NButton
                  v-for="action in rowActions(item.actions)"
                  :key="action.action"
                  circle
                  secondary
                  size="small"
                  :title="actionLabel(action)"
                  :aria-label="actionLabel(action)"
                  @click="performAction(action, item)"
                >
                  <template #icon>
                    <icon-lucide:list-tree v-if="actionIcon(action.action) === 'list-tree'" />
                    <icon-lucide:message-square-plus v-else />
                  </template>
                </NButton>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>

    <section v-if="readingSteps.length" class="reading-artifacts__section">
      <div class="reading-artifacts__eyebrow">Reading Plan</div>
      <div class="reading-artifacts__rows">
        <article
          v-for="(step, index) in readingSteps"
          :key="step.locationRef || step.locationLabel || `reading-step-${index}`"
          class="reading-row"
        >
          <div class="reading-row__body">
            <div class="reading-row__title">{{ step.paperTitle || step.locationLabel }}</div>
            <div v-if="step.paperTitle && step.locationLabel" class="reading-artifacts__meta">
              {{ step.locationLabel }}
            </div>
            <div v-if="step.preview" class="reading-row__text">You'll inspect: {{ step.preview }}</div>
            <div v-if="step.evidenceStatus" class="reading-row__status">{{ step.evidenceStatus }}</div>
          </div>
          <div v-if="rowActions(step.actions).length" class="reading-row__actions">
            <NButton
              v-for="action in rowActions(step.actions)"
              :key="action.action"
              circle
              secondary
              size="small"
              :title="actionLabel(action)"
              :aria-label="actionLabel(action)"
              @click="performAction(action, step)"
            >
              <template #icon>
                <icon-lucide:book-open v-if="actionIcon(action.action) === 'book-open'" />
                <icon-lucide:message-square-plus v-else />
              </template>
            </NButton>
          </div>
        </article>
      </div>
    </section>

    <section v-if="evidenceRows.length" class="reading-artifacts__section">
      <div class="reading-artifacts__eyebrow">Evidence</div>
      <div class="reading-artifacts__rows">
        <article
          v-for="(row, index) in evidenceRows"
          :key="row.sourceQuoteRef || row.claim || `evidence-row-${index}`"
          class="reading-row"
        >
          <div class="reading-row__body">
            <div v-if="row.claim" class="reading-row__title">{{ row.claim }} {{ row.citationMarker }}</div>
            <blockquote v-if="row.quote" class="reading-quote">{{ row.quote }}</blockquote>
            <div v-if="row.paperTitle || row.locationLabel" class="reading-artifacts__meta">
              {{ [row.paperTitle, row.locationLabel].filter(Boolean).join(' · ') }}
            </div>
            <div v-if="row.cannotProve?.length" class="reading-row__status">
              Cannot prove: {{ row.cannotProve.join('; ') }}
            </div>
          </div>
          <div v-if="rowActions(row.actions).length" class="reading-row__actions">
            <NButton
              v-for="action in rowActions(row.actions)"
              :key="action.action"
              circle
              secondary
              size="small"
              :title="actionLabel(action)"
              :aria-label="actionLabel(action)"
              @click="performAction(action, row)"
            >
              <template #icon>
                <icon-lucide:quote v-if="actionIcon(action.action) === 'quote'" />
                <icon-lucide:message-square-plus v-else />
              </template>
            </NButton>
          </div>
        </article>
      </div>
    </section>

    <section
      v-if="missingEvidence?.explanation || missingEvidence?.missing?.length || uncertaintyNotes.length"
      class="reading-artifacts__section"
    >
      <div class="reading-artifacts__eyebrow">Not Verified</div>
      <div v-if="missingEvidence?.explanation" class="reading-row__text">{{ missingEvidence.explanation }}</div>
      <div v-if="missingEvidence?.missing?.length" class="reading-artifacts__meta">
        {{ missingEvidenceLabels(missingEvidence.missing).join(' · ') }}
      </div>
      <ul v-if="uncertaintyNotes.length" class="reading-artifacts__notes">
        <li v-for="note in uncertaintyNotes" :key="note">{{ note }}</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.reading-artifacts {
  display: grid;
  width: 100%;
  gap: 12px;
}

.reading-artifacts__section {
  display: grid;
  gap: 8px;
  border-top: 1px solid var(--color-border);
  padding-top: 10px;
}

.reading-artifacts__eyebrow {
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.2;
  text-transform: uppercase;
}

.reading-artifacts__goal {
  color: var(--color-text);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.45;
}

.reading-artifacts__meta {
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.reading-artifacts__actions,
.reading-row__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.reading-artifacts__rows {
  display: grid;
  gap: 8px;
}

.reading-shortlist-groups {
  display: grid;
  gap: 10px;
}

.reading-shortlist-group {
  display: grid;
  gap: 6px;
}

.reading-shortlist-group__title {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 750;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.reading-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-surface) 86%, var(--color-bg));
  padding: 10px 12px;
}

.reading-row--single {
  grid-template-columns: minmax(0, 1fr);
}

.reading-row__body {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.reading-row__head {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.reading-row__title {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--color-text);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
}

.reading-row__text,
.reading-row__status {
  overflow-wrap: anywhere;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.reading-row__status {
  color: color-mix(in srgb, var(--color-text-muted) 78%, var(--color-citation));
}

.reading-pill {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  border: 1px solid color-mix(in srgb, var(--color-success) 42%, var(--color-border));
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-success) 13%, var(--color-surface));
  padding: 2px 7px;
  color: var(--color-success);
  font-size: 11px;
  font-weight: 700;
  line-height: 15px;
  overflow-wrap: anywhere;
}

.reading-quote {
  margin: 2px 0;
  border-left: 3px solid color-mix(in srgb, var(--color-citation) 48%, var(--color-border));
  padding-left: 9px;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.reading-artifacts__notes {
  margin: 0;
  padding-left: 18px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.55;
}
</style>
