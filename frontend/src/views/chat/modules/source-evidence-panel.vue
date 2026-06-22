<script setup lang="ts">
import { computed, ref } from 'vue';
import { request } from '@/service/request';

defineOptions({ name: 'SourceEvidencePanel' });

interface Props {
  referenceNumber?: number;
  paperId?: string | null;
  paperTitle?: string | null;
  originalFilename?: string | null;
  pageNumber?: number | null;
  chunkId?: number | null;
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  score?: number | null;
  anchorText?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  elementType?: string | null;
  sectionTitle?: string | null;
  sectionLevel?: number | null;
  bboxJson?: string | null;
  parserName?: string | null;
  parserVersion?: string | null;
}

const props = defineProps<Props>();

const openingOriginal = ref(false);

const displayPaper = computed(() => props.paperTitle || props.originalFilename || 'Unknown paper');
const displayFilename = computed(() => props.originalFilename || props.paperTitle || '');
const displayPage = computed(() => (props.pageNumber ? `Page ${props.pageNumber}` : 'Not captured'));
const displayChunk = computed(() =>
  props.chunkId !== null && props.chunkId !== undefined ? `Chunk ${props.chunkId}` : 'Not captured'
);
const displayRetrieval = computed(() => props.retrievalLabel || props.retrievalMode || 'Not captured');
const displayScore = computed(() => {
  if (typeof props.score !== 'number' || Number.isNaN(props.score)) {
    return 'Not captured';
  }
  return props.score.toFixed(3);
});
const displaySection = computed(() => {
  if (!props.sectionTitle) {
    return '';
  }
  return props.sectionLevel ? `${props.sectionTitle} · level ${props.sectionLevel}` : props.sectionTitle;
});
const displayParser = computed(() => {
  if (!props.parserName && !props.parserVersion) {
    return '';
  }
  return [props.parserName, props.parserVersion].filter(Boolean).join(' ');
});
const matchedText = computed(() => props.matchedChunkText || props.evidenceSnippet || props.anchorText || '');
const evidenceRows = computed(() =>
  [
    { label: 'Paper', value: displayPaper.value },
    { label: 'Page', value: displayPage.value },
    { label: 'Chunk', value: displayChunk.value },
    { label: 'Retrieval', value: displayRetrieval.value },
    { label: 'Query', value: props.retrievalQuery || '' },
    { label: 'Score', value: displayScore.value },
    { label: 'Section', value: displaySection.value },
    { label: 'Element', value: props.elementType || '' },
    { label: 'Parser', value: displayParser.value }
  ].filter(row => row.value)
);

async function openOriginalPdf() {
  if (!props.paperId) {
    window.$message?.warning('缺少 paperId，无法打开原始 PDF');
    return;
  }

  openingOriginal.value = true;
  try {
    const { error, data } = await request<Api.Paper.DownloadResponse>({
      url: `/papers/${props.paperId}/download`
    });

    if (error || !data?.downloadUrl) {
      window.$message?.error(error?.message || '打开原始 PDF 失败');
      return;
    }

    const link = document.createElement('a');
    link.href = data.downloadUrl;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  } catch (error: any) {
    window.$message?.error(error?.message || '打开原始 PDF 失败');
  } finally {
    openingOriginal.value = false;
  }
}
</script>

<template>
  <section class="source-evidence">
    <header class="source-evidence__header">
      <div class="source-evidence__title-row">
        <span class="source-evidence__title">Source Evidence</span>
        <span v-if="referenceNumber" class="source-evidence__badge">#{{ referenceNumber }}</span>
      </div>
      <div v-if="displayFilename" class="source-evidence__filename">{{ displayFilename }}</div>
    </header>

    <dl class="source-evidence__grid">
      <template v-for="row in evidenceRows" :key="row.label">
        <dt>{{ row.label }}</dt>
        <dd>{{ row.value }}</dd>
      </template>
    </dl>

    <div class="source-evidence__text-block">
      <div class="source-evidence__text-label">Matched text</div>
      <p v-if="matchedText">{{ matchedText }}</p>
      <p v-else class="source-evidence__empty-text">No matched text is available for this reference.</p>
    </div>

    <div v-if="anchorText && anchorText !== matchedText" class="source-evidence__text-block">
      <div class="source-evidence__text-label">Anchor text</div>
      <p>{{ anchorText }}</p>
    </div>

    <div v-if="bboxJson" class="source-evidence__bbox">
      <span>BBox</span>
      <code>{{ bboxJson }}</code>
    </div>

    <div class="source-evidence__actions">
      <NButton type="primary" secondary :loading="openingOriginal" :disabled="!paperId" @click="openOriginalPdf">
        <template #icon>
          <icon-lucide:external-link />
        </template>
        Open original PDF
      </NButton>
    </div>
  </section>
</template>

<style scoped lang="scss">
.source-evidence {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 14px;
  color: var(--color-text);
}

.source-evidence__header {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.source-evidence__title-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.source-evidence__title {
  font-size: 15px;
  font-weight: 750;
}

.source-evidence__badge {
  border: 1px solid var(--color-border);
  border-radius: 999px;
  padding: 1px 8px;
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 700;
}

.source-evidence__filename {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-evidence__grid {
  display: grid;
  grid-template-columns: minmax(74px, max-content) minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 12px;
}

.source-evidence__grid dt {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.source-evidence__grid dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--color-text);
  font-size: 13px;
  line-height: 18px;
}

.source-evidence__text-block {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.source-evidence__text-label {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.source-evidence__text-block p {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  padding: 11px 12px;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.72;
  white-space: pre-wrap;
}

.source-evidence__empty-text {
  color: var(--color-text-muted) !important;
}

.source-evidence__bbox {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.source-evidence__bbox code {
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-alt);
  padding: 8px;
  color: var(--color-text);
  font-size: 12px;
  white-space: pre-wrap;
}

.source-evidence__actions {
  display: flex;
  justify-content: flex-start;
}

@media (max-width: 640px) {
  .source-evidence__grid {
    grid-template-columns: 1fr;
  }
}
</style>
