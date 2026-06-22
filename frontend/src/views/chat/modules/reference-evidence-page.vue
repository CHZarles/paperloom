<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NEmpty, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import SourceEvidencePanel from './source-evidence-panel.vue';

defineOptions({ name: 'ReferenceEvidencePage' });

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const loadError = ref('');
const paperTitle = ref('');
const originalFilename = ref('');
const paperId = ref('');
const pageNumber = ref<number | undefined>(undefined);
const anchorText = ref('');
const retrievalMode = ref<Api.Chat.ReferenceEvidence['retrievalMode']>(null);
const retrievalLabel = ref('');
const retrievalQuery = ref('');
const evidenceSnippet = ref('');
const matchedChunkText = ref('');
const score = ref<number | null>(null);
const chunkId = ref<number | null>(null);
const elementType = ref('');
const sectionTitle = ref('');
const sectionLevel = ref<number | null>(null);
const bboxJson = ref('');
const parserName = ref('');
const parserVersion = ref('');
const sourceKind = ref<Api.Chat.ReferenceEvidence['sourceKind']>(null);
const tableId = ref('');
const figureId = ref('');
const formulaId = ref('');
const evidenceRole = ref('');
const retrievalRoute = ref('');
const intent = ref('');
const rankReason = ref('');
const tableText = ref('');
const tableMarkdown = ref('');
const tableScreenshotAvailable = ref<boolean | null>(null);
const referenceNumber = ref<number | undefined>(undefined);
const conversationRecordId = ref<number | undefined>(undefined);
const evidenceKey = computed(() => String(route.query.evidenceKey || ''));
const hasEvidenceTarget = computed(() => Boolean(paperTitle.value || paperId.value));

function readOptionalNumber(value: unknown) {
  const parsed = Number.parseInt(String(value || ''), 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function syncFallbackFromQuery() {
  paperTitle.value = String(route.query.paperTitle || '');
  originalFilename.value = String(route.query.originalFilename || '');
  paperId.value = String(route.query.paperId || '');
  pageNumber.value = readOptionalNumber(route.query.pageNumber);
  anchorText.value = String(route.query.anchorText || '');
  retrievalQuery.value = String(route.query.retrievalQuery || '');
  conversationRecordId.value = readOptionalNumber(route.query.conversationRecordId);
  referenceNumber.value = readOptionalNumber(route.query.referenceNumber);
}

// eslint-disable-next-line complexity
function syncFromStorage() {
  if (!evidenceKey.value) return false;

  const raw = localStorage.getItem(evidenceKey.value);
  if (!raw) return false;

  try {
    const payload = JSON.parse(raw) as Partial<Api.Paper.ReferenceDetailResponse> & {
      paperTitle?: string;
      originalFilename?: string | null;
      paperId?: string;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number | null;
      referenceNumber?: number | null;
    };

    paperTitle.value = payload.paperTitle || paperTitle.value;
    originalFilename.value = payload.originalFilename || originalFilename.value;
    paperId.value = payload.paperId || paperId.value;
    pageNumber.value = payload.pageNumber || pageNumber.value;
    anchorText.value = payload.anchorText || anchorText.value;
    retrievalMode.value = payload.retrievalMode ?? retrievalMode.value;
    retrievalLabel.value = payload.retrievalLabel || retrievalLabel.value;
    retrievalQuery.value = payload.retrievalQuery || retrievalQuery.value;
    evidenceSnippet.value = payload.evidenceSnippet || evidenceSnippet.value;
    matchedChunkText.value = payload.matchedChunkText || matchedChunkText.value;
    score.value = payload.score ?? score.value;
    chunkId.value = payload.chunkId ?? chunkId.value;
    elementType.value = payload.elementType || elementType.value;
    sectionTitle.value = payload.sectionTitle || sectionTitle.value;
    sectionLevel.value = payload.sectionLevel ?? sectionLevel.value;
    bboxJson.value = payload.bboxJson || bboxJson.value;
    parserName.value = payload.parserName || parserName.value;
    parserVersion.value = payload.parserVersion || parserVersion.value;
    sourceKind.value = payload.sourceKind ?? sourceKind.value;
    tableId.value = payload.tableId || tableId.value;
    figureId.value = payload.figureId || figureId.value;
    formulaId.value = payload.formulaId || formulaId.value;
    evidenceRole.value = payload.evidenceRole || evidenceRole.value;
    retrievalRoute.value = payload.retrievalRoute || retrievalRoute.value;
    intent.value = payload.intent || intent.value;
    rankReason.value = payload.rankReason || rankReason.value;
    tableText.value = payload.tableText || tableText.value;
    tableMarkdown.value = payload.tableMarkdown || tableMarkdown.value;
    tableScreenshotAvailable.value = payload.tableScreenshotAvailable ?? tableScreenshotAvailable.value;
    conversationRecordId.value = payload.conversationRecordId || conversationRecordId.value;
    referenceNumber.value = payload.referenceNumber || referenceNumber.value;
    return true;
  } catch {
    return false;
  }
}

// Reference evidence loading supports URL fallback, localStorage handoff, and endpoint refresh.
// eslint-disable-next-line complexity
async function loadReferenceDetail() {
  syncFallbackFromQuery();
  const restoredFromStorage = syncFromStorage();
  loadError.value = '';

  if (restoredFromStorage && (paperId.value || matchedChunkText.value || evidenceSnippet.value)) {
    return;
  }

  if (!conversationRecordId.value || !referenceNumber.value) {
    return;
  }

  loading.value = true;

  try {
    const { error, data } = await request<Api.Paper.ReferenceDetailResponse>({
      url: 'papers/reference-detail',
      params: {
        conversationRecordId: conversationRecordId.value,
        referenceNumber: String(referenceNumber.value)
      }
    });

    if (error || !data) {
      loadError.value = error?.message || '引用详情加载失败';
      return;
    }

    paperTitle.value = data.paperTitle || paperTitle.value;
    originalFilename.value = data.originalFilename || originalFilename.value;
    paperId.value = data.paperId || paperId.value;
    pageNumber.value = data.pageNumber || pageNumber.value;
    anchorText.value = data.anchorText || anchorText.value;
    retrievalMode.value = data.retrievalMode ?? null;
    retrievalLabel.value = data.retrievalLabel || '';
    retrievalQuery.value = data.retrievalQuery || '';
    evidenceSnippet.value = data.evidenceSnippet || '';
    matchedChunkText.value = data.matchedChunkText || '';
    score.value = data.score ?? null;
    chunkId.value = data.chunkId ?? null;
    elementType.value = data.elementType || '';
    sectionTitle.value = data.sectionTitle || '';
    sectionLevel.value = data.sectionLevel ?? null;
    bboxJson.value = data.bboxJson || '';
    parserName.value = data.parserName || '';
    parserVersion.value = data.parserVersion || '';
    sourceKind.value = data.sourceKind ?? null;
    tableId.value = data.tableId || '';
    figureId.value = data.figureId || '';
    formulaId.value = data.formulaId || '';
    evidenceRole.value = data.evidenceRole || '';
    retrievalRoute.value = data.retrievalRoute || '';
    intent.value = data.intent || '';
    rankReason.value = data.rankReason || '';
    tableText.value = data.tableText || '';
    tableMarkdown.value = data.tableMarkdown || '';
    tableScreenshotAvailable.value = data.tableScreenshotAvailable ?? null;
  } catch (error: any) {
    loadError.value = error?.message || '引用详情加载失败';
  } finally {
    loading.value = false;
  }
}

function handleBack() {
  if (window.history.length > 1) {
    router.back();
    return;
  }

  router.push('/chat');
}

watch(
  () => route.query,
  () => {
    loadReferenceDetail();
  },
  { immediate: true }
);
</script>

<template>
  <div class="flex-col gap-4">
    <div v-if="loading && !hasEvidenceTarget" class="evidence-page-state">
      <NSpin size="large" />
      <span>正在加载 source evidence...</span>
    </div>

    <div v-else-if="!hasEvidenceTarget" class="evidence-page-empty">
      <NEmpty :description="loadError || '没有拿到引用证据信息'" />
    </div>

    <div v-else class="evidence-page-shell">
      <div v-if="loadError && !evidenceKey" class="evidence-page-tip">
        {{ loadError }}
      </div>
      <SourceEvidencePanel
        :reference-number="referenceNumber"
        :paper-title="paperTitle"
        :original-filename="originalFilename"
        :paper-id="paperId || undefined"
        :page-number="pageNumber"
        :evidence-snippet="evidenceSnippet"
        :matched-chunk-text="matchedChunkText"
        :bbox-json="bboxJson"
        :source-kind="sourceKind"
        :table-id="tableId"
        :figure-id="figureId"
        :formula-id="formulaId"
        :table-text="tableText"
        :table-markdown="tableMarkdown"
        :table-screenshot-available="tableScreenshotAvailable"
      />
      <NButton secondary class="evidence-page-back" @click="handleBack">
        <template #icon>
          <icon-lucide:arrow-left />
        </template>
        返回聊天
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
.evidence-page-state {
  @apply flex min-h-220px flex-col items-center justify-center gap-4 text-stone-500;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
}

.evidence-page-empty {
  @apply py-10;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
}

.evidence-page-shell {
  @apply flex min-h-0 flex-col gap-3;
}

.evidence-page-back {
  align-self: flex-start;
}

.evidence-page-tip {
  @apply px-4 py-3 text-sm;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-card-band);
  color: var(--color-accent);
}
</style>
