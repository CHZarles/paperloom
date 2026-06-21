<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NEmpty, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import FilePreview from '@/components/custom/file-preview.vue';

defineOptions({ name: 'ReferencePreviewPage' });

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const loadError = ref('');
const paperTitle = ref('');
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
const referenceNumber = ref<number | undefined>(undefined);
const conversationRecordId = ref<number | undefined>(undefined);
const previewKey = computed(() => String(route.query.previewKey || ''));
const hasPreviewTarget = computed(() => Boolean(paperTitle.value || paperId.value));

function readOptionalNumber(value: unknown) {
  const parsed = Number.parseInt(String(value || ''), 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}

function syncFallbackFromQuery() {
  paperTitle.value = String(route.query.paperTitle || '');
  paperId.value = String(route.query.paperId || '');
  pageNumber.value = readOptionalNumber(route.query.pageNumber);
  anchorText.value = String(route.query.anchorText || '');
  retrievalQuery.value = String(route.query.retrievalQuery || '');
  conversationRecordId.value = readOptionalNumber(route.query.conversationRecordId);
  referenceNumber.value = readOptionalNumber(route.query.referenceNumber);
}

function syncFromStorage() {
  if (!previewKey.value) return false;

  const raw = localStorage.getItem(previewKey.value);
  if (!raw) return false;

  try {
    const payload = JSON.parse(raw) as Partial<Api.Paper.ReferenceDetailResponse> & {
      paperTitle?: string;
      paperId?: string;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number | null;
      referenceNumber?: number | null;
    };

    paperTitle.value = payload.paperTitle || paperTitle.value;
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
    conversationRecordId.value = payload.conversationRecordId || conversationRecordId.value;
    referenceNumber.value = payload.referenceNumber || referenceNumber.value;
    return true;
  } catch {
    return false;
  }
}

// Existing reference loading supports URL fallback, localStorage handoff, and endpoint refresh.
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
    <div v-if="loading && !hasPreviewTarget" class="preview-page-state">
      <NSpin size="large" />
      <span>正在加载 source evidence...</span>
    </div>

    <div v-else-if="!hasPreviewTarget" class="preview-page-empty">
      <NEmpty :description="loadError || '没有拿到可预览的引用信息'" />
    </div>

    <div v-else class="preview-page-shell">
      <div v-if="loadError && !previewKey" class="preview-page-tip">
        {{ loadError }}
      </div>
      <FilePreview
        :paper-title="paperTitle"
        :paper-id="paperId || undefined"
        :page-number="pageNumber"
        :anchor-text="anchorText"
        :retrieval-mode="retrievalMode"
        :retrieval-label="retrievalLabel"
        :retrieval-query="retrievalQuery"
        :evidence-snippet="evidenceSnippet"
        :matched-chunk-text="matchedChunkText"
        :score="score"
        :chunk-id="chunkId"
        :visible="true"
        @close="handleBack"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.preview-page-state {
  @apply flex min-h-220px flex-col items-center justify-center gap-4 text-stone-500;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
}

.preview-page-empty {
  @apply py-10;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
}

.preview-page-shell {
  @apply flex min-h-0 flex-col gap-3;
}

.preview-page-tip {
  @apply px-4 py-3 text-sm;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-card-band);
  color: var(--color-accent);
}
</style>
