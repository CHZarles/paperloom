<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { request } from '@/service/request';

defineOptions({ name: 'SourceEvidencePanel' });

interface EvidenceBoundingBox {
  left?: number;
  top?: number;
  right?: number;
  bottom?: number;
  coordinateSystem?: string;
}

interface Props {
  referenceNumber?: number;
  paperId?: string | null;
  paperTitle?: string | null;
  originalFilename?: string | null;
  pageNumber?: number | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  chunkId?: number | null;
  bboxJson?: string | null;
  sourceKind?: Api.Chat.ReferenceEvidence['sourceKind'];
  tableId?: string | null;
  figureId?: string | null;
  formulaId?: string | null;
  tableText?: string | null;
  tableMarkdown?: string | null;
  tableScreenshotAvailable?: boolean | null;
  sourceType?: Api.Chat.ReferenceEvidence['sourceType'];
  evidenceAssetLevel?: Api.Chat.ReferenceEvidence['evidenceAssetLevel'];
  pdfEvidenceAvailable?: boolean | null;
  pageScreenshotAvailable?: boolean | null;
  figureScreenshotAvailable?: boolean | null;
  assetWarnings?: string[] | null;
  conversationRecordId?: number | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'askAboutThis', payload: Api.Chat.Scope): void;
}>();

const openingOriginal = ref(false);
const openingTableScreenshot = ref(false);
const openingFigureScreenshot = ref(false);
const openingPageScreenshot = ref(false);
const evidenceImageVisible = ref(false);
const evidenceImageTitle = ref('');
const evidenceImageUrl = ref('');
const evidenceImageFallback = ref('');
const evidenceImageBboxJson = ref<string | null>('');
const evidenceImageKind = ref<'page' | 'asset'>('asset');
const evidenceImageElement = ref<HTMLImageElement | null>(null);
const evidenceImageDisplaySize = ref({ width: 0, height: 0 });

const assetWarningLabels: Record<string, string> = {
  page_screenshots_missing: 'Page screenshot is unavailable.',
  table_screenshot_missing: 'Table image is unavailable.',
  figure_screenshot_missing: 'Figure image is unavailable.',
  parser_artifact_missing: 'Parser artifact is unavailable.'
};

const displayPaper = computed(() => props.paperTitle || props.originalFilename || 'Unknown paper');
const displayFilename = computed(() => props.originalFilename || props.paperTitle || '');
const displayPage = computed(() => (props.pageNumber ? `Page ${props.pageNumber}` : 'Not captured'));
const isTableSource = computed(() => props.sourceKind === 'TABLE' || Boolean(props.tableId));
const isFigureSource = computed(
  () => props.sourceKind === 'FIGURE' || props.sourceKind === 'CHART' || Boolean(props.figureId)
);
const isFormulaSource = computed(() => props.sourceKind === 'FORMULA' || Boolean(props.formulaId));
const displaySourceKind = computed(() => {
  if (isTableSource.value) return 'TABLE';
  if (isFigureSource.value) return props.sourceKind === 'CHART' ? 'CHART' : 'FIGURE';
  if (isFormulaSource.value) return 'FORMULA';
  return props.sourceKind || 'TEXT';
});
const matchedText = computed(() => props.matchedChunkText || props.evidenceSnippet || '');
const tableEvidenceText = computed(() => props.tableMarkdown || props.tableText || '');
const sourceTypeLabel = computed(() => props.sourceType || 'PDF');
const evidenceReadinessLabel = computed(() => {
  if (props.pdfEvidenceAvailable || props.evidenceAssetLevel === 'PDF_VISUAL') return 'PDF visual evidence available';
  return 'PDF visual assets unavailable';
});
const readableAssetWarnings = computed(() =>
  (props.assetWarnings || []).map(warning => assetWarningLabels[warning] || warning)
);
const canDownloadOriginalPdf = computed(() => Boolean(props.paperId));
const canOpenPageEvidence = computed(
  () => Boolean(props.paperId && props.pageNumber) && props.pageScreenshotAvailable !== false
);
const tableImageUnavailable = computed(() => isTableSource.value && props.tableScreenshotAvailable === false);
const figureImageUnavailable = computed(() => isFigureSource.value && props.figureScreenshotAvailable === false);
const evidenceRows = computed(() =>
  [
    { label: 'Paper', value: displayPaper.value },
    { label: 'Page', value: displayPage.value },
    { label: 'Source type', value: sourceTypeLabel.value },
    { label: 'Source', value: displaySourceKind.value }
  ].filter(row => row.value)
);

const pageSnapshotTitle = computed(() => {
  const page = props.pageNumber ? `Page ${props.pageNumber}` : 'Page snapshot';
  const file = displayFilename.value || displayPaper.value;
  return `${page} · ${file}`;
});

const evidenceModalEyebrow = computed(() =>
  evidenceImageKind.value === 'page' ? 'Page Evidence' : 'Evidence Snapshot'
);
const evidenceRegionStyle = computed(() => {
  if (evidenceImageKind.value !== 'page' || !evidenceImageBboxJson.value) {
    return null;
  }
  if (!evidenceImageDisplaySize.value.width || !evidenceImageDisplaySize.value.height) {
    return null;
  }

  const box = parseEvidenceBoundingBox(evidenceImageBboxJson.value);
  if (!box || box.coordinateSystem !== 'top_left_1000') {
    return null;
  }

  const left = clampNumber(box.left ?? 0, 0, 999);
  const top = clampNumber(box.top ?? 0, 0, 999);
  const right = clampNumber(box.right ?? 0, left + 1, 1000);
  const bottom = clampNumber(box.bottom ?? 0, top + 1, 1000);
  const displayWidth = evidenceImageDisplaySize.value.width;
  const displayHeight = evidenceImageDisplaySize.value.height;

  return {
    left: `${(left / 1000) * displayWidth}px`,
    top: `${(top / 1000) * displayHeight}px`,
    width: `${((right - left) / 1000) * displayWidth}px`,
    height: `${((bottom - top) / 1000) * displayHeight}px`
  };
});
const evidenceRegionUnavailable = computed(
  () => evidenceImageKind.value === 'page' && Boolean(evidenceImageUrl.value) && !evidenceRegionStyle.value
);

function showEvidenceImage(options: {
  title: string;
  imageUrl: string;
  fallbackText: string;
  bboxJson?: string | null;
  imageKind?: 'page' | 'asset';
}) {
  const { title, imageUrl, fallbackText, bboxJson, imageKind = 'asset' } = options;
  evidenceImageTitle.value = title;
  evidenceImageUrl.value = imageUrl;
  evidenceImageFallback.value = fallbackText;
  evidenceImageBboxJson.value = bboxJson || '';
  evidenceImageKind.value = imageKind;
  evidenceImageDisplaySize.value = { width: 0, height: 0 };
  evidenceImageVisible.value = true;
  nextTick(updateEvidenceImageSize);
}

async function openPageScreenshot() {
  if (!props.paperId) {
    window.$message?.warning('Missing paper id.');
    return;
  }

  if (!props.pageNumber) {
    window.$message?.warning('Page number was not captured for this reference.');
    return;
  }

  openingPageScreenshot.value = true;
  try {
    const { error, data } = await request<Api.Paper.VisualAssetResponse>({
      url: `/papers/${props.paperId}/pages/${props.pageNumber}/screenshot`
    });

    if (error || !data?.downloadUrl) {
      showEvidenceImage({
        title: pageSnapshotTitle.value,
        imageUrl: '',
        fallbackText:
          'Page evidence is not available. You can download the original PDF to inspect the source document.',
        bboxJson: props.bboxJson,
        imageKind: 'page'
      });
      return;
    }

    showEvidenceImage({
      title: pageSnapshotTitle.value,
      imageUrl: data.downloadUrl,
      fallbackText: 'Page evidence is not available. You can download the original PDF to inspect the source document.',
      bboxJson: props.bboxJson,
      imageKind: 'page'
    });
  } catch {
    showEvidenceImage({
      title: pageSnapshotTitle.value,
      imageUrl: '',
      fallbackText: 'Page evidence is not available. You can download the original PDF to inspect the source document.',
      bboxJson: props.bboxJson,
      imageKind: 'page'
    });
  } finally {
    openingPageScreenshot.value = false;
  }
}

async function openTableScreenshot() {
  if (!props.paperId || !props.tableId) {
    window.$message?.warning('缺少 tableId，无法打开表格截图');
    return;
  }

  openingTableScreenshot.value = true;
  try {
    const { error, data } = await request<Api.Paper.VisualAssetResponse>({
      url: `/papers/${props.paperId}/tables/${props.tableId}/screenshot`
    });

    if (error || !data?.downloadUrl) {
      showEvidenceImage({
        title: `Table ${props.tableId} · ${displayPage.value}`,
        imageUrl: '',
        fallbackText: 'Table screenshot is not available. The extracted table text is still shown below.'
      });
      return;
    }

    showEvidenceImage({
      title: `Table ${props.tableId} · ${displayPage.value}`,
      imageUrl: data.downloadUrl,
      fallbackText: 'Table screenshot is not available. The extracted table text is still shown below.'
    });
  } catch {
    showEvidenceImage({
      title: `Table ${props.tableId} · ${displayPage.value}`,
      imageUrl: '',
      fallbackText: 'Table screenshot is not available. The extracted table text is still shown below.'
    });
  } finally {
    openingTableScreenshot.value = false;
  }
}

async function openFigureScreenshot() {
  if (!props.paperId || !props.figureId) {
    window.$message?.warning('Missing figure id.');
    return;
  }

  openingFigureScreenshot.value = true;
  try {
    const { error, data } = await request<Api.Paper.VisualAssetResponse>({
      url: `/papers/${props.paperId}/figures/${props.figureId}/screenshot`
    });

    if (error || !data?.downloadUrl) {
      showEvidenceImage({
        title: `Figure ${props.figureId} · ${displayPage.value}`,
        imageUrl: '',
        fallbackText:
          'Figure screenshot is not available. You can use the page snapshot to inspect the source document.'
      });
      return;
    }

    showEvidenceImage({
      title: `Figure ${props.figureId} · ${displayPage.value}`,
      imageUrl: data.downloadUrl,
      fallbackText: 'Figure screenshot is not available. You can use the page snapshot to inspect the source document.'
    });
  } catch {
    showEvidenceImage({
      title: `Figure ${props.figureId} · ${displayPage.value}`,
      imageUrl: '',
      fallbackText: 'Figure screenshot is not available. You can use the page snapshot to inspect the source document.'
    });
  } finally {
    openingFigureScreenshot.value = false;
  }
}

async function downloadOriginalPdf() {
  if (!props.paperId) {
    window.$message?.warning('Missing paper id.');
    return;
  }

  openingOriginal.value = true;
  try {
    const { error, data } = await request<Api.Paper.DownloadResponse>({
      url: `/papers/${props.paperId}/download`
    });

    if (error || !data?.downloadUrl) {
      window.$message?.error(error?.message || 'Original PDF download is unavailable.');
      return;
    }

    downloadExternalUrl(data.downloadUrl, data.originalFilename || data.paperTitle || displayFilename.value);
  } catch (error: any) {
    window.$message?.error(error?.message || 'Original PDF download is unavailable.');
  } finally {
    openingOriginal.value = false;
  }
}

function downloadExternalUrl(url: string, filename?: string | null) {
  const link = document.createElement('a');
  link.href = url;
  link.download = normalizePdfFilename(
    filename || props.originalFilename || props.paperTitle || props.paperId || 'paper.pdf'
  );
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

function openEvidenceImageInNewTab() {
  if (!evidenceImageUrl.value) return;
  window.open(evidenceImageUrl.value, '_blank', 'noopener,noreferrer');
}

function askAboutThisEvidence() {
  if (!props.paperId) {
    window.$message?.warning('Missing paper id.');
    return;
  }
  emit('askAboutThis', {
    paperIds: [props.paperId],
    paperTitles: [displayPaper.value],
    referenceNumber: props.referenceNumber,
    conversationRecordId: props.conversationRecordId || undefined,
    chunkId: props.chunkId || undefined,
    pageNumber: props.pageNumber || undefined,
    paperId: props.paperId,
    paperTitle: displayPaper.value,
    originalFilename: props.originalFilename || undefined,
    matchedText: matchedText.value,
    matchedChunkText: props.matchedChunkText || undefined,
    evidenceSnippet: props.evidenceSnippet || undefined,
    bboxJson: props.bboxJson || undefined,
    sourceKind: props.sourceKind || undefined
  });
}

function normalizePdfFilename(filename: string) {
  const normalized = filename.trim().replace(/[\\/\r\n\t"]/g, '_') || 'paper.pdf';
  return normalized.toLowerCase().endsWith('.pdf') ? normalized : `${normalized}.pdf`;
}

function parseEvidenceBoundingBox(raw: string): EvidenceBoundingBox | null {
  try {
    const parsed = JSON.parse(raw) as EvidenceBoundingBox;
    if (
      typeof parsed.left !== 'number' ||
      typeof parsed.top !== 'number' ||
      typeof parsed.right !== 'number' ||
      typeof parsed.bottom !== 'number'
    ) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

function updateEvidenceImageSize() {
  const element = evidenceImageElement.value;
  if (!element) {
    evidenceImageDisplaySize.value = { width: 0, height: 0 };
    return;
  }
  evidenceImageDisplaySize.value = {
    width: element.clientWidth,
    height: element.clientHeight
  };
}

function clampNumber(value: number, min: number, max: number) {
  if (max < min) {
    return min;
  }
  return Math.min(Math.max(value, min), max);
}

watch(evidenceImageVisible, visible => {
  if (visible) {
    nextTick(updateEvidenceImageSize);
  }
});

window.addEventListener('resize', updateEvidenceImageSize);

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateEvidenceImageSize);
});
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

    <div class="source-evidence__asset-state">
      <span
        class="source-evidence__asset-pill"
        :class="{
          'source-evidence__asset-pill--ok': pdfEvidenceAvailable || evidenceAssetLevel === 'PDF_VISUAL',
          'source-evidence__asset-pill--warning': !pdfEvidenceAvailable
        }"
      >
        {{ evidenceReadinessLabel }}
      </span>
      <span
        v-for="warning in readableAssetWarnings"
        :key="warning"
        class="source-evidence__asset-pill source-evidence__asset-pill--warning"
      >
        {{ warning }}
      </span>
    </div>

    <div class="source-evidence__text-block">
      <div class="source-evidence__text-label">Original text</div>
      <p v-if="matchedText">{{ matchedText }}</p>
      <p v-else class="source-evidence__empty-text">No matched text is available for this reference.</p>
    </div>

    <div v-if="isTableSource && tableEvidenceText" class="source-evidence__text-block">
      <div class="source-evidence__text-label">Table evidence</div>
      <pre class="source-evidence__table-text">{{ tableEvidenceText }}</pre>
    </div>

    <div v-if="tableImageUnavailable || figureImageUnavailable" class="source-evidence__missing-asset">
      <span v-if="tableImageUnavailable">Table image unavailable. Extracted table text remains available.</span>
      <span v-if="figureImageUnavailable">Figure image unavailable. Caption or matched text remains available.</span>
    </div>

    <div class="source-evidence__actions">
      <NButton type="primary" secondary :disabled="!paperId || !matchedText" @click="askAboutThisEvidence">
        <template #icon>
          <icon-lucide:message-square-plus />
        </template>
        Ask about this
      </NButton>
      <NButton
        v-if="isTableSource"
        type="primary"
        secondary
        :loading="openingTableScreenshot"
        :disabled="!paperId || !tableId || tableScreenshotAvailable === false"
        @click="openTableScreenshot"
      >
        <template #icon>
          <icon-lucide:table-2 />
        </template>
        View table screenshot
      </NButton>
      <NButton
        v-if="isFigureSource"
        type="primary"
        secondary
        :loading="openingFigureScreenshot"
        :disabled="!paperId || !figureId || figureScreenshotAvailable === false"
        @click="openFigureScreenshot"
      >
        <template #icon>
          <icon-lucide:image />
        </template>
        View figure screenshot
      </NButton>
      <NButton
        :type="isTableSource || isFigureSource ? 'default' : 'primary'"
        secondary
        :loading="openingPageScreenshot"
        :disabled="!canOpenPageEvidence"
        @click="openPageScreenshot"
      >
        <template #icon>
          <icon-lucide:image />
        </template>
        View page evidence
      </NButton>
      <NButton v-if="canDownloadOriginalPdf" secondary :loading="openingOriginal" @click="downloadOriginalPdf">
        <template #icon>
          <icon-lucide:download />
        </template>
        Download original PDF
      </NButton>
    </div>

    <NModal v-model:show="evidenceImageVisible" class="evidence-image-modal-shell" :auto-focus="false">
      <div class="evidence-image-modal">
        <header class="evidence-image-modal__header">
          <div class="evidence-image-modal__title-wrap">
            <div class="evidence-image-modal__eyebrow">{{ evidenceModalEyebrow }}</div>
            <div class="evidence-image-modal__title">{{ evidenceImageTitle }}</div>
          </div>
          <div class="evidence-image-modal__tools">
            <NButton secondary size="small" :disabled="!evidenceImageUrl" @click="openEvidenceImageInNewTab">
              <template #icon>
                <icon-lucide:external-link />
              </template>
              Open image in new tab
            </NButton>
            <NButton
              v-if="canDownloadOriginalPdf"
              secondary
              size="small"
              :loading="openingOriginal"
              @click="downloadOriginalPdf"
            >
              <template #icon>
                <icon-lucide:download />
              </template>
              Download original PDF
            </NButton>
            <NButton quaternary circle size="small" @click="evidenceImageVisible = false">
              <template #icon>
                <icon-lucide:x />
              </template>
            </NButton>
          </div>
        </header>

        <div v-if="evidenceImageUrl" class="evidence-image-modal__frame">
          <div class="evidence-image-modal__image-wrap">
            <img
              ref="evidenceImageElement"
              :src="evidenceImageUrl"
              :alt="evidenceImageTitle"
              @load="updateEvidenceImageSize"
            />
            <div v-if="evidenceRegionStyle" class="evidence-image-modal__region" :style="evidenceRegionStyle" />
          </div>
          <div v-if="evidenceRegionUnavailable" class="evidence-image-modal__hint">
            Evidence region coordinates are not available for this page snapshot.
          </div>
        </div>
        <div v-else class="evidence-image-modal__fallback">
          {{ evidenceImageFallback }}
        </div>
      </div>
    </NModal>
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

.source-evidence__asset-state {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.source-evidence__asset-pill {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-card-band);
  padding: 3px 8px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.25;
}

.source-evidence__asset-pill--ok {
  border-color: var(--color-success);
  color: var(--color-success);
}

.source-evidence__asset-pill--warning {
  border-color: var(--color-warning);
  color: var(--color-warning);
}

.source-evidence__asset-pill--muted {
  color: var(--color-text-muted);
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

.source-evidence__table-text {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  padding: 11px 12px;
  color: var(--color-text);
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.source-evidence__empty-text {
  color: var(--color-text-muted) !important;
}

.source-evidence__missing-asset {
  display: grid;
  gap: 4px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-card-band);
  padding: 10px 12px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.source-evidence__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-start;
}

.evidence-image-modal-shell {
  width: min(1040px, calc(100vw - 32px));
}

.evidence-image-modal {
  display: flex;
  max-height: calc(100vh - 48px);
  min-height: 260px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  box-shadow: var(--shadow-card);
}

.evidence-image-modal__header {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
  padding: 14px 16px;
}

.evidence-image-modal__title-wrap {
  min-width: 0;
}

.evidence-image-modal__eyebrow {
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0;
  text-transform: uppercase;
}

.evidence-image-modal__title {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text);
  font-size: 15px;
  font-weight: 750;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-image-modal__tools {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.evidence-image-modal__frame {
  min-height: 0;
  overflow: auto;
  background: var(--color-surface-alt);
  padding: 16px;
}

.evidence-image-modal__image-wrap {
  position: relative;
  width: min(100%, 960px);
  margin: 0 auto;
  line-height: 0;
}

.evidence-image-modal__frame img {
  display: block;
  width: 100%;
  height: auto;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: white;
}

.evidence-image-modal__region {
  position: absolute;
  z-index: 1;
  border: 2px solid #d6a84f;
  border-radius: 5px;
  background: rgb(214 168 79 / 18%);
  box-shadow:
    0 0 0 1px rgb(255 255 255 / 72%),
    0 8px 24px rgb(106 72 9 / 20%);
  pointer-events: none;
}

.evidence-image-modal__hint {
  width: min(100%, 960px);
  margin: 10px auto 0;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 18px;
}

.evidence-image-modal__fallback {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
  padding: 24px;
  color: var(--color-text-muted);
  font-size: 13px;
  text-align: center;
}

@media (max-width: 640px) {
  .source-evidence__grid {
    grid-template-columns: 1fr;
  }

  .evidence-image-modal__header {
    flex-direction: column;
  }

  .evidence-image-modal__tools {
    justify-content: flex-start;
  }
}
</style>
