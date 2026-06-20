<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import { getServiceBaseURL } from '@/utils/service';
import PdfDocumentViewer from '@/components/custom/pdf-document-viewer.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  paperTitle: string;
  paperId?: string;
  originalFilename?: string;
  pageNumber?: number;
  anchorText?: string;
  searchText?: string;
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string;
  retrievalQuery?: string;
  evidenceSnippet?: string;
  matchedChunkText?: string;
  score?: number | null;
  chunkId?: number | null;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');
const previewType = ref<'pdf' | 'image' | 'text' | 'download'>('text');
const previewUrl = ref('');
const sourceUrl = ref('');
const resolvedPaperId = ref('');
const singlePageMode = ref(false);
const sourcePageNumber = ref<number | undefined>(undefined);
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL: serviceBaseUrl } = getServiceBaseURL(import.meta.env, isHttpProxy);

const resolvedPreviewUrl = computed(() => resolveFileAccessUrl(previewUrl.value));
const resolvedSourceUrl = computed(() => resolveFileAccessUrl(sourceUrl.value));
const displayTitle = computed(() => props.paperTitle || props.originalFilename || '');
const fileExtensionLabel = computed(() => {
  const extension = getFileExt(props.originalFilename || props.paperTitle)?.toUpperCase();
  return extension || 'FILE';
});
const resolvedHighlightAnchor = computed(() => props.anchorText || '');
const resolvedHighlightSearchText = computed(() => {
  return [props.matchedChunkText, props.searchText, props.anchorText]
    .map(text => text?.trim())
    .filter((text, index, values): text is string => Boolean(text) && values.indexOf(text) === index)
    .join('\n');
});
const displayScore = computed(() => {
  if (typeof props.score !== 'number' || Number.isNaN(props.score)) {
    return '';
  }
  return props.score.toFixed(3);
});
const displayPage = computed(() => sourcePageNumber.value || props.pageNumber || undefined);
const resolvedPdfPageNumber = computed(() => displayPage.value || 1);
const resolvedPdfSinglePageMode = computed(() => {
  if (previewType.value !== 'pdf') {
    return singlePageMode.value;
  }

  return Boolean(resolvedPaperId.value || singlePageMode.value);
});
const displayPageLabel = computed(() => (displayPage.value ? `第 ${displayPage.value} 页` : ''));
const headerMetaLine = computed(() => {
  if (previewType.value === 'pdf') {
    return [displayPageLabel.value, displayScore.value ? `分数 ${displayScore.value}` : ''].filter(Boolean).join(' / ');
  }
  if (previewType.value === 'image') {
    return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : '']
      .filter(Boolean)
      .join(' / ');
  }
  if (previewType.value === 'text') {
    return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : '']
      .filter(Boolean)
      .join(' / ');
  }
  return [fileExtensionLabel.value, displayScore.value ? `分数 ${displayScore.value}` : ''].filter(Boolean).join(' / ');
});
const retrievalMetaLine = computed(() => {
  return [
    props.retrievalLabel || props.retrievalMode || '',
    props.retrievalQuery ? `query: ${props.retrievalQuery}` : '',
    props.chunkId ? `chunk ${props.chunkId}` : ''
  ]
    .filter(Boolean)
    .join(' / ');
});
const resolvedPdfPreviewUrl = computed(() => {
  if (previewType.value !== 'pdf') {
    return resolvedPreviewUrl.value;
  }

  if (resolvedPaperId.value) {
    return resolveFileAccessUrl(
      `/api/v1/papers/${encodeURIComponent(resolvedPaperId.value)}/page-preview?pageNumber=${resolvedPdfPageNumber.value}`
    );
  }

  return '';
});
const canOpenInNewTab = computed(() => {
  if (previewType.value === 'pdf') {
    return Boolean(resolvedPdfPreviewUrl.value);
  }

  return Boolean(resolvedSourceUrl.value || resolvedPreviewUrl.value);
});

function resolveFileAccessUrl(url: string) {
  if (!url) return '';
  if (/^(https?:)?\/\//i.test(url) || /^(blob:|data:)/i.test(url)) {
    return url;
  }

  if (url.startsWith('/api/')) {
    if (serviceBaseUrl.startsWith('/proxy-')) {
      return `${serviceBaseUrl}${url.replace(/^\/api\/v\d+/, '')}`;
    }

    if (/^https?:\/\//i.test(serviceBaseUrl)) {
      return `${new URL(serviceBaseUrl).origin}${url}`;
    }

    const serviceOrigin = serviceBaseUrl.replace(/\/api(?:\/v\d+)?\/?$/, '');
    return `${serviceOrigin}${url}`;
  }

  if (url.startsWith('/')) {
    return url;
  }

  return `${serviceBaseUrl.replace(/\/$/, '')}/${url.replace(/^\//, '')}`;
}

// 获取文件图标
function getFileIcon(paperTitle: string) {
  const ext = getFileExt(paperTitle);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

// 监听论文标题变化，加载预览内容
watch(
  () => [props.paperTitle, props.paperId, props.pageNumber],
  async () => {
    if (displayTitle.value && props.visible) {
      await loadPreviewContent();
    }
  },
  { immediate: true }
);

// 监听可见性变化
watch(
  () => props.visible,
  async visible => {
    if (visible && displayTitle.value) {
      await loadPreviewContent();
    }
  }
);

// 加载预览内容
async function loadPreviewContent() {
  if (!displayTitle.value) return;

  loading.value = true;
  error.value = '';
  content.value = '';
  previewUrl.value = '';
  sourceUrl.value = '';
  resolvedPaperId.value = props.paperId || '';
  singlePageMode.value = false;
  sourcePageNumber.value = undefined;
  previewType.value = 'text';

  try {
    if (!props.paperId) {
      error.value = '预览失败：缺少 paperId';
      return;
    }

    const { error: requestError, data } = await request<{
      paperTitle: string;
      originalFilename?: string;
      sourceFileSizeBytes: number;
      paperId?: string;
      content?: string;
      previewUrl?: string;
      sourceUrl?: string;
      singlePageMode?: boolean;
      sourcePageNumber?: number;
      previewType?: 'pdf' | 'image' | 'text' | 'download';
    }>({
      url: `/papers/${props.paperId}/preview`,
      params: {
        pageNumber: props.pageNumber
      }
    });

    if (requestError) {
      error.value = `预览失败：${requestError.message || '未知错误'}`;
    } else if (data) {
      previewType.value = data.previewType || 'download';
      content.value = data.content || '';
      previewUrl.value = data.previewUrl || '';
      sourceUrl.value = data.sourceUrl || data.previewUrl || '';
      resolvedPaperId.value = data.paperId || props.paperId || '';
      singlePageMode.value = Boolean(data.singlePageMode);
      sourcePageNumber.value = data.sourcePageNumber || props.pageNumber;
    }
  } catch (err: any) {
    error.value = `预览失败：${err.message || '网络错误'}`;
  } finally {
    loading.value = false;
  }
}

// 下载文件
async function downloadFile() {
  if (!displayTitle.value) return;

  downloading.value = true;

  try {
    const targetPaperId = resolvedPaperId.value || props.paperId;
    if (!targetPaperId) {
      window.$message?.error('下载失败：缺少 paperId');
      return;
    }

    const { error: requestError, data } = await request<Api.Paper.DownloadResponse>({
      url: `/papers/${targetPaperId}/download`
    });

    if (requestError) {
      window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
    } else if (data) {
      const link = document.createElement('a');
      link.href = data.downloadUrl;
      link.download = data.originalFilename || data.paperTitle;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.$message?.success('开始下载论文');
    }
  } catch (err: any) {
    window.$message?.error(`下载失败：${err.message || '网络错误'}`);
  } finally {
    downloading.value = false;
  }
}

function openPreviewInNewTab() {
  const targetUrl =
    previewType.value === 'pdf' ? resolvedPdfPreviewUrl.value : resolvedSourceUrl.value || resolvedPreviewUrl.value;
  if (!targetUrl) return;

  if (previewType.value === 'pdf' && displayPage.value) {
    window.open(`${targetUrl}#page=${displayPage.value}`, '_blank', 'noopener,noreferrer');
    return;
  }

  window.open(targetUrl, '_blank', 'noopener,noreferrer');
}

// 关闭预览
function closePreview() {
  emit('close');
}
</script>

<template>
  <div class="file-preview-container">
    <div class="preview-backdrop" />

    <div class="preview-content">
      <template v-if="loading">
        <div class="state-panel">
          <div class="state-orb">
            <NSpin size="large" />
          </div>
          <div class="state-copy">
            <strong>正在装载 source evidence</strong>
            <span>整理检索线索、页码定位和可预览内容。</span>
          </div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="state-panel state-panel--error">
          <div class="state-orb state-orb--error">
            <icon-mdi-alert-circle class="text-34" />
          </div>
          <div class="state-copy">
            <strong>这份 source 暂时没能打开</strong>
            <span>{{ error }}</span>
          </div>
        </div>
      </template>
      <template v-else>
        <div
          class="content-wrapper"
          :class="{
            'content-wrapper--immersive': previewType === 'pdf' && previewUrl,
            'content-wrapper--pdf': previewType === 'pdf' && previewUrl
          }"
        >
          <aside class="insight-rail">
            <section class="info-card source-card">
              <div class="source-card-top">
                <div class="file-badge-shell">
                  <div class="file-badge-icon">
                    <SvgIcon :local-icon="getFileIcon(displayTitle)" class="text-18" />
                  </div>
                  <div class="file-badge-copy">
                    <h2 class="preview-title">{{ displayTitle }}</h2>
                    <p v-if="headerMetaLine" class="preview-subtitle">{{ headerMetaLine }}</p>
                  </div>
                </div>
              </div>
              <div class="source-actions">
                <NButton
                  v-if="previewType !== 'pdf'"
                  size="small"
                  secondary
                  :disabled="!canOpenInNewTab"
                  @click="openPreviewInNewTab"
                >
                  <template #icon>
                    <icon-mdi-open-in-new />
                  </template>
                  新窗口
                </NButton>
                <NButton size="small" secondary :loading="downloading" @click="downloadFile">
                  <template #icon>
                    <icon-mdi-download />
                  </template>
                  下载
                </NButton>
                <NButton size="small" quaternary @click="closePreview">
                  <template #icon>
                    <icon-mdi-close />
                  </template>
                  关闭
                </NButton>
              </div>
            </section>

            <section v-if="retrievalMetaLine" class="info-card">
              <span class="info-label">Retrieval</span>
              <p class="support-copy">{{ retrievalMetaLine }}</p>
            </section>

            <section v-if="evidenceSnippet" class="info-card">
              <span class="info-label">Evidence</span>
              <p class="support-copy">{{ evidenceSnippet }}</p>
            </section>

            <section v-else-if="resolvedHighlightAnchor" class="info-card">
              <span class="info-label">Anchor</span>
              <p class="support-copy">{{ resolvedHighlightAnchor }}</p>
            </section>
          </aside>

          <section class="preview-stage">
            <div class="stage-body">
              <template v-if="previewType === 'pdf' && resolvedPdfPreviewUrl">
                <div class="pdf-preview-stack">
                  <PdfDocumentViewer
                    :url="resolvedPdfPreviewUrl"
                    :paper-title="displayTitle"
                    :page-number="pageNumber"
                    :single-page-mode="resolvedPdfSinglePageMode"
                    :source-page-number="resolvedPdfPageNumber"
                    :anchor-text="resolvedHighlightAnchor"
                    :search-text="resolvedHighlightSearchText"
                    :visible="visible"
                  />
                </div>
              </template>
              <template v-else-if="previewType === 'image' && resolvedPreviewUrl">
                <div class="image-preview-shell">
                  <img :src="resolvedPreviewUrl" :alt="displayTitle" class="preview-image" />
                </div>
              </template>
              <template v-else-if="previewType === 'text'">
                <div class="text-preview-shell">
                  <pre class="preview-text">{{ content }}</pre>
                </div>
              </template>
              <template v-else>
                <div class="download-placeholder">
                  <div class="placeholder-icon">
                    <SvgIcon :local-icon="getFileIcon(displayTitle)" class="text-28" />
                  </div>
                  <div class="state-copy">
                    <strong>当前 source 格式暂不支持在线预览</strong>
                    <span>你可以先下载文件，或在新窗口中尝试打开原始资源。</span>
                  </div>
                  <div class="placeholder-actions">
                    <NButton secondary :disabled="!canOpenInNewTab" @click="openPreviewInNewTab">
                      <template #icon>
                        <icon-mdi-open-in-new />
                      </template>
                      新窗口打开
                    </NButton>
                    <NButton type="primary" @click="downloadFile">
                      <template #icon>
                        <icon-mdi-download />
                      </template>
                      下载后查看
                    </NButton>
                  </div>
                </div>
              </template>
            </div>
          </section>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.file-preview-container {
  position: relative;
  display: flex;
  height: min(92vh, calc(100vh - 20px));
  min-height: min(760px, calc(100vh - 20px));
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #c9c1b2;
  background: #fbfaf6;
  color: #20242a;
}

.preview-backdrop {
  display: none;
}

.preview-content {
  position: relative;
  z-index: 1;
  min-height: 0;
  flex: 1 1 0;
  overflow: hidden;
  background: #fbfaf6;
  padding: 12px;
}

.content-wrapper {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 12px;
  overflow: hidden;
}

.content-wrapper--immersive {
  grid-template-columns: 240px minmax(0, 1fr);
}

.state-panel {
  display: flex;
  height: 100%;
  min-height: 420px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
  padding: 40px;
  text-align: center;
}

.state-panel--error {
  border-color: rgba(38, 54, 74, 0.32);
  background: #fff2f0;
}

.state-orb,
.placeholder-icon {
  display: flex;
  height: 64px;
  width: 64px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #e2dccc;
  color: #26364a;
}

.state-orb--error {
  border-color: rgba(38, 54, 74, 0.32);
  background: #e7dde0;
  color: #26364a;
}

.state-copy {
  display: flex;
  max-width: 520px;
  flex-direction: column;
  gap: 8px;
  color: #5e6470;
}

.state-copy strong {
  color: #20242a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 18px;
  font-weight: 700;
}

.file-badge-shell {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.file-badge-icon {
  display: flex;
  height: 48px;
  width: 48px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9c1b2;
  border-radius: 6px;
  background: #e2dccc;
  color: #26364a;
}

.file-badge-copy {
  min-width: 0;
}

.preview-title {
  margin: 0;
  overflow: hidden;
  color: #20242a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 17px;
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-subtitle {
  margin: 5px 0 0;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.insight-rail {
  display: flex;
  min-height: 0;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 2px;
}

.info-card {
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #e2dccc;
  color: #394150;
  padding: 14px;
}

.source-card {
  background: #fbfaf6;
}

.source-card-top {
  min-width: 0;
  overflow: hidden;
}

.source-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.info-label {
  color: #26364a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.support-copy,
.info-copy,
.spotlight-copy {
  margin: 9px 0 0;
  overflow-wrap: anywhere;
  color: #394150;
  font-size: 14px;
  line-height: 1.75;
}

.preview-stage {
  min-height: 0;
  overflow: hidden;
  background: #fbfaf6;
}

.stage-body {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
}

.pdf-preview-stack {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

.text-preview-shell {
  height: 100%;
  background: #fbfaf6;
  padding: 16px;
}

.preview-text {
  height: 100%;
  margin: 0;
  overflow: auto;
  color: #20242a;
  font-family: SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 14px;
  line-height: 1.68;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.image-preview-shell {
  display: flex;
  height: 100%;
  min-height: 0;
  align-items: center;
  justify-content: center;
  overflow: auto;
  background: #fbfaf6;
  padding: 16px;
}

.preview-image {
  max-height: 100%;
  max-width: 100%;
  border-radius: 8px;
  object-fit: contain;
}

.download-placeholder {
  display: flex;
  height: 100%;
  min-height: 320px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  background: #e2dccc;
  color: #5e6470;
  padding: 32px;
  text-align: center;
}

.placeholder-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

@media (max-width: 960px) {
  .file-preview-container {
    height: min(92vh, calc(100vh - 24px));
    min-height: auto;
  }

  .preview-content {
    padding: 12px;
  }

  .content-wrapper,
  .content-wrapper--immersive {
    grid-template-columns: 1fr;
  }

  .insight-rail {
    max-height: 30vh;
    padding-right: 0;
  }

  .preview-stage {
    min-height: 58vh;
  }
}
</style>
