<script setup lang="tsx">
import { defineAsyncComponent } from 'vue';
import { NButton, NDropdown, NModal } from 'naive-ui';
import type { FlatResponseData } from '@sa/axios';
import { UploadStatus } from '@/enum';
import UploadDialog from './modules/upload-dialog.vue';
import CollectionsPanel from './modules/collections-panel.vue';
import PaperMobileList from './modules/paper-mobile-list.vue';

const FilePreview = defineAsyncComponent(() => import('@/components/custom/file-preview.vue'));

const authStore = useAuthStore();
const canUploadPapers = computed(() => authStore.userInfo.role !== 'GUEST');
const route = useRoute();
const router = useRouter();
const isMobileLibrary = ref(typeof window !== 'undefined' ? window.innerWidth < 768 : false);

function normalizeLibraryView(value: unknown): 'papers' | 'collections' {
  return value === 'collections' ? 'collections' : 'papers';
}

const libraryView = ref<'papers' | 'collections'>(normalizeLibraryView(route.query.view));
const libraryViewOptions = [
  { label: 'Papers', value: 'papers' },
  { label: 'Paper Sets', value: 'collections' }
] as const;

function handleLibraryViewUpdate(value: 'papers' | 'collections') {
  libraryView.value = value;
  router.replace({
    name: 'knowledge-base',
    query: value === 'collections' ? { view: 'collections' } : {}
  });
}

// 文件预览相关状态
const previewVisible = ref(false);
const previewPaperTitle = ref('');
const previewPaperId = ref('');
const tableModalVisible = ref(false);
const tableModalLoading = ref(false);
const tableModalTitle = ref('');
const tableModalRows = ref<Api.Paper.TableItem[]>([]);
const figureModalVisible = ref(false);
const figureModalLoading = ref(false);
const figureModalTitle = ref('');
const figureModalRows = ref<Api.Paper.FigureItem[]>([]);
const figureModalPaperId = ref('');
const paperLibraryQuery = ref('');
const activePaperLibraryQuery = ref('');

type PaperLibrarySearchParams = Api.Common.CommonSearchParams & {
  query?: string;
};

const activeProcessingStatuses = new Set<Api.Paper.UploadTask['processingStatus']>([
  'PENDING',
  'PROCESSING',
  'MINERU_RUNNING',
  'MINERU_ARTIFACT_SAVED',
  'MAPPING_STRUCTURED_CONTENT',
  'RENDERING_VISUAL_ASSETS',
  'EMBEDDING',
  'INDEXING'
]);

function mapUploadStatusToTaskStatus(uploadStatus?: Api.Paper.UploadTask['uploadStatus']) {
  if (uploadStatus === 'COMPLETED' || uploadStatus === 1) return UploadStatus.Completed;
  if (uploadStatus === 'MERGING' || uploadStatus === 2) return UploadStatus.Uploading;
  if (uploadStatus === 'UPLOADING' || uploadStatus === 0) return UploadStatus.Uploading;
  return UploadStatus.Break;
}

function normalizeBytes(value?: number | string | null) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : 0;
}

function normalizeRemotePaper(row: Api.Paper.UploadTask): Api.Paper.UploadTask {
  return {
    ...row,
    totalSize: normalizeBytes(row.totalSize ?? row.sourceFileSizeBytes),
    paperTitle: row.paperTitle || row.originalFilename,
    originalFilename: row.originalFilename || row.paperTitle,
    uploadedChunks: row.uploadedChunks || [],
    progress: row.progress ?? (row.uploadStatus === 'COMPLETED' ? 100 : 0),
    status: row.status ?? mapUploadStatusToTaskStatus(row.uploadStatus),
    chunk: row.chunk ?? null
  };
}

async function apiFn(params: PaperLibrarySearchParams = {}): Promise<FlatResponseData<Api.Paper.List>> {
  const page = params.page && params.page > 0 ? params.page : 1;
  const size = params.size && params.size > 0 ? params.size : 10;
  const query = params.query?.trim() || '';
  const requestParams = { ...params, page, size, query };
  const response = await request<Api.Paper.UploadTask[] | Api.Paper.List>({
    url: '/papers?scope=accessible',
    params: requestParams
  });
  if (response.error) return response as FlatResponseData<Api.Paper.List>;

  const payload = response.data;
  if (!Array.isArray(payload)) {
    const rows = payload?.data || payload?.content || [];
    if (payload) {
      const normalizedRows = rows.map(normalizeRemotePaper);
      payload.data = normalizedRows;
      payload.content = normalizedRows;
    }
    return response as FlatResponseData<Api.Paper.List>;
  }

  const start = (page - 1) * size;
  const pageData = payload.slice(start, start + size).map(normalizeRemotePaper);

  return {
    ...response,
    data: {
      data: pageData,
      content: pageData,
      number: page,
      size,
      totalElements: payload.length
    }
  };
}

function canManageFile(row: Api.Paper.UploadTask) {
  if (!canUploadPapers.value) return false;
  if (row.file && !row.userId) return true;
  return String(row.userId) === String(authStore.userInfo.id);
}

function isGlobalPaper(row: Api.Paper.UploadTask) {
  return row.libraryScope === 'GLOBAL';
}

function paperRowMenuOptions(row: Api.Paper.UploadTask) {
  return [
    { label: 'Extracted figures', key: 'figures', disabled: !Number(row.figureAsset?.figureCount || 0) },
    { label: 'Extracted tables', key: 'tables', disabled: !Number(row.tableAsset?.tableCount || 0) },
    {
      label: 'Parser JSON',
      key: 'parser',
      disabled: !canManageFile(row) || !row.parserArtifact?.available
    },
    ...(canRetryVectorization(row) ? [{ label: 'Retry processing', key: 'retry' }] : []),
    ...(authStore.isAdmin
      ? [
          isGlobalPaper(row)
            ? { label: 'Remove from global library', key: 'unpublish' }
            : { label: 'Publish to global library', key: 'publish', disabled: !isPaperSearchable(row) }
        ]
      : []),
    ...(canManageFile(row)
      ? [{ label: isGlobalPaper(row) ? 'Remove from personal library' : 'Delete paper', key: 'delete' }]
      : [])
  ];
}

function handlePaperRowMenuAction(key: string | number, row: Api.Paper.UploadTask) {
  if (key === 'figures') handleOpenFigures(row);
  if (key === 'tables') handleOpenTables(row);
  if (key === 'parser') handleOpenParserArtifact(row);
  if (key === 'retry') handleRetryVectorization(row);
  if (key === 'publish') handleUpdatePublication(row, true);
  if (key === 'unpublish') {
    window.$dialog?.warning({
      title: 'Remove from global library?',
      content: row.originalFilename,
      positiveText: 'Remove',
      negativeText: 'Cancel',
      onPositiveClick: () => handleUpdatePublication(row, false)
    });
  }
  if (key !== 'delete') return;

  window.$dialog?.warning({
    title: isGlobalPaper(row) ? 'Remove from personal library?' : 'Delete paper?',
    content: row.originalFilename,
    positiveText: isGlobalPaper(row) ? 'Remove' : 'Delete',
    negativeText: 'Cancel',
    onPositiveClick: () => handleDelete(row.paperId)
  });
}

// 处理文件预览
function handleFilePreview(originalFilename: string, paperId: string) {
  previewPaperTitle.value = originalFilename;
  previewPaperId.value = paperId;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  previewVisible.value = false;
  previewPaperTitle.value = '';
  previewPaperId.value = '';
}

const { data, getData, loading, mobilePagination, updateSearchParams } = useTable({
  apiFn,
  showTotal: true,
  immediate: false,
  columns: () => []
});

function catalogCounts(row: Api.Paper.UploadTask) {
  const tokens = Number(row.retrievalIndexedTokenCount || 0);
  const locations = Number(row.retrievalIndexedLocationCount || 0);
  return `${compactNumber(tokens)} tokens · ${locations.toLocaleString()} 处索引`;
}

function catalogState(row: Api.Paper.UploadTask): {
  label: string;
  tone: 'ready' | 'processing' | 'broken' | 'warning';
} {
  if (row.file && Number(row.progress || 0) < 100)
    return { label: `上传中 ${Math.round(Number(row.progress || 0))}%`, tone: 'processing' };
  if (isPipelineFailed(row)) return { label: '解析失败', tone: 'broken' };
  if (isPaperSearchable(row)) return { label: '可检索', tone: 'ready' };
  if (isPipelineActive(row)) return { label: '解析中', tone: 'processing' };
  return { label: '待处理', tone: 'warning' };
}

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const tableTasks = computed(() => {
  const activeQuery = activePaperLibraryQuery.value.trim().toLowerCase();
  const remoteRows = data.value.map(item => tasks.value.find(task => task.paperId === item.paperId) || item);
  const localRows = tasks.value.filter(
    task =>
      task.file &&
      !remoteRows.some(item => item.paperId === task.paperId) &&
      matchesLocalPaperLibraryQuery(task, activeQuery)
  );

  return [...localRows, ...remoteRows];
});

const libraryStats = computed(() => {
  const rows = tableTasks.value;
  const searchable = rows.filter(item => isPaperSearchable(item)).length;
  const processing = rows.filter(item => isPipelineActive(item)).length;
  const failed = rows.filter(item => isPipelineFailed(item)).length;
  const indexedLocations = rows.reduce((sum, item) => sum + Number(item.retrievalIndexedLocationCount || 0), 0);

  return [
    {
      label: 'Documents',
      value: formatNumber(mobilePagination.value.itemCount || rows.length),
      detail: activePaperLibraryQuery.value ? 'matched' : 'accessible'
    },
    {
      label: 'Searchable',
      value: formatNumber(searchable),
      detail: 'indexed'
    },
    {
      label: 'Processing',
      value: formatNumber(processing),
      detail: 'parse/index'
    },
    {
      label: 'Failed',
      value: formatNumber(failed),
      detail: 'needs action'
    },
    {
      label: 'Locations',
      value: compactNumber(indexedLocations),
      detail: 'lexical index'
    }
  ];
});

function syncLibraryViewport() {
  isMobileLibrary.value = window.innerWidth < 768;
}

onMounted(async () => {
  syncLibraryViewport();
  window.addEventListener('resize', syncLibraryViewport);
  await getList();
});

watch(
  () => route.query.view,
  value => {
    libraryView.value = normalizeLibraryView(value);
  }
);

function syncTaskFromServer(target: Api.Paper.UploadTask, source: Api.Paper.UploadTask) {
  Object.assign(target, {
    originalFilename: source.originalFilename,
    paperTitle: source.paperTitle,
    totalSize: normalizeBytes(source.totalSize ?? source.sourceFileSizeBytes),
    sourceFileSizeBytes: source.sourceFileSizeBytes,
    status: source.status,
    uploadStatus: source.uploadStatus,
    userId: source.userId,
    libraryScope: source.libraryScope,
    searchable: source.searchable,
    createdAt: source.createdAt,
    mergedAt: source.mergedAt,
    retrievalIndexedTokenCount: source.retrievalIndexedTokenCount,
    retrievalIndexedLocationCount: source.retrievalIndexedLocationCount,
    processingStatus: source.processingStatus,
    processingErrorMessage: source.processingErrorMessage,
    sourceType: source.sourceType,
    evidenceAssetLevel: source.evidenceAssetLevel,
    assetWarnings: source.assetWarnings,
    pdfEvidenceAvailable: source.pdfEvidenceAvailable,
    parserArtifact: source.parserArtifact,
    tableAsset: source.tableAsset,
    figureAsset: source.figureAsset,
    formulaAsset: source.formulaAsset,
    visualAsset: source.visualAsset
  });
}

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList() {
  await getData();

  data.value.forEach(item => {
    const index = tasks.value.findIndex(task => task.paperId === item.paperId);
    if (index !== -1) {
      syncTaskFromServer(tasks.value[index], item);
    } else if (item.status === UploadStatus.Completed) {
      tasks.value.push(item);
    } else if (!tasks.value.some(task => task.paperId === item.paperId)) {
      item.status = UploadStatus.Break;
      tasks.value.push(item);
    }
  });
}

function matchesLocalPaperLibraryQuery(task: Api.Paper.UploadTask, activeQuery: string) {
  if (!activeQuery) return true;

  return [task.originalFilename, task.paperTitle, task.paperId].some(value =>
    String(value || '')
      .toLowerCase()
      .includes(activeQuery)
  );
}

async function handlePaperLibrarySearch() {
  const query = paperLibraryQuery.value.trim();
  activePaperLibraryQuery.value = query;
  updateSearchParams({
    page: 1,
    size: Number(mobilePagination.value.pageSize || 10),
    query
  });
  await getList();
}

async function handlePaperLibrarySearchReset() {
  if (!paperLibraryQuery.value && !activePaperLibraryQuery.value) return;

  paperLibraryQuery.value = '';
  activePaperLibraryQuery.value = '';
  updateSearchParams({
    page: 1,
    size: Number(mobilePagination.value.pageSize || 10),
    query: ''
  });
  await getList();
}

async function handleMobilePageUpdate(page: number) {
  updateSearchParams({
    page,
    size: Number(mobilePagination.value.pageSize || 10),
    query: activePaperLibraryQuery.value
  });
  await getList();
}

async function handleDelete(paperId: string) {
  const index = tasks.value.findIndex(task => task.paperId === paperId);
  const task = index !== -1 ? tasks.value[index] : null;
  const paper = task || data.value.find(row => row.paperId === paperId);
  const removesPersonalCopy = paper ? isGlobalPaper(paper) : false;

  if (task) {
    task.requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  if (task && isLocalOnlyPendingUpload(task, paperId)) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/papers/${paperId}`, method: 'DELETE' });
  if (!error) {
    if (index !== -1) {
      tasks.value.splice(index, 1);
    }
    window.$message?.success(removesPersonalCopy ? 'Removed from personal library' : 'Paper deleted');
    await getData();
  }
}

async function handleOpenParserArtifact(row: Api.Paper.UploadTask) {
  const { error, data: parserArtifact } = await request<Api.Paper.ParserArtifactResponse>({
    url: `/papers/${row.paperId}/parser-artifact`
  });

  if (error || !parserArtifact?.downloadUrl) {
    window.$message?.error(error?.message || 'Parser JSON is unavailable');
    return;
  }

  openExternalUrl(parserArtifact.downloadUrl);
}

async function handleOpenTables(row: Api.Paper.UploadTask) {
  tableModalVisible.value = true;
  tableModalLoading.value = true;
  tableModalTitle.value = row.paperTitle || row.originalFilename;
  tableModalRows.value = [];

  const { error, data: tables } = await request<Api.Paper.TableItem[]>({
    url: `/papers/${row.paperId}/tables`
  });

  tableModalLoading.value = false;
  if (error) {
    window.$message?.error(error.message || 'Could not load extracted tables');
    return;
  }
  tableModalRows.value = tables || [];
}

async function handleOpenFigures(row: Api.Paper.UploadTask) {
  figureModalVisible.value = true;
  figureModalLoading.value = true;
  figureModalTitle.value = row.paperTitle || row.originalFilename;
  figureModalRows.value = [];
  figureModalPaperId.value = row.paperId;

  const { error, data: figures } = await request<Api.Paper.FigureItem[]>({
    url: `/papers/${row.paperId}/figures`
  });

  figureModalLoading.value = false;
  if (error) {
    window.$message?.error(error.message || 'Could not load extracted figures');
    return;
  }
  figureModalRows.value = figures || [];
}

async function handleFigureScreenshot(figureId: string) {
  if (!figureModalPaperId.value || !figureId) return;
  const { error, data: asset } = await request<Api.Paper.VisualAssetResponse>({
    url: `/papers/${figureModalPaperId.value}/figures/${figureId}/screenshot`
  });
  if (error || !asset?.downloadUrl) {
    window.$message?.error(error?.message || 'No image available for this figure');
    return;
  }
  openExternalUrl(asset.downloadUrl);
}

function openExternalUrl(url: string) {
  const link = document.createElement('a');
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

function isLocalOnlyPendingUpload(task: Api.Paper.UploadTask, paperId: string) {
  const hasRemoteRecord =
    data.value.some(item => item.paperId === paperId) ||
    Boolean(task.createdAt || task.userId || task.uploadStatus !== undefined);

  return (
    Boolean(task.file) && !hasRemoteRecord && task.status === UploadStatus.Pending && task.uploadedChunks.length === 0
  );
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

function isVectorizationProcessing(row: Api.Paper.UploadTask) {
  return activeProcessingStatuses.has(row.processingStatus || null);
}

function isUploadCompleted(row: Api.Paper.UploadTask) {
  return row.status === UploadStatus.Completed || row.uploadStatus === 'COMPLETED' || row.uploadStatus === 1;
}

function isPaperSearchable(row: Api.Paper.UploadTask) {
  if (row.searchable !== undefined) return row.searchable;
  return isUploadCompleted(row) && row.processingStatus === 'COMPLETED' && hasRetrievalIndexUsage(row);
}

function isPipelineActive(row: Api.Paper.UploadTask) {
  if (row.status === UploadStatus.Pending || row.status === UploadStatus.Uploading) return true;
  return isUploadCompleted(row) && isVectorizationProcessing(row);
}

function isPipelineFailed(row: Api.Paper.UploadTask) {
  return row.status === UploadStatus.Break || row.processingStatus === 'FAILED';
}

function hasRetrievalIndexUsage(row: Api.Paper.UploadTask) {
  return Number(row.retrievalIndexedLocationCount || 0) > 0;
}

function canRetryVectorization(row: Api.Paper.UploadTask) {
  return canManageFile(row) && row.processingStatus === 'FAILED';
}

async function handleRetryVectorization(row: Api.Paper.UploadTask) {
  const { error } = await request({
    url: `/papers/${row.paperId}/processing/retry`,
    method: 'POST'
  });

  if (error) return;

  row.processingStatus = 'PROCESSING';
  row.processingErrorMessage = null;
  row.searchable = false;
  row.retrievalIndexedTokenCount = undefined;
  row.retrievalIndexedLocationCount = undefined;
  window.$message?.success('Processing retry queued');
  await getList();
}

async function handleUpdatePublication(row: Api.Paper.UploadTask, publish: boolean) {
  const { error } = await request({
    url: `/admin/papers/${row.paperId}/publication`,
    method: publish ? 'POST' : 'DELETE'
  });
  if (error) return;

  row.libraryScope = publish ? 'GLOBAL' : 'PRIVATE';
  window.$message?.success(publish ? 'Paper published globally' : 'Paper removed from global library');
  await getList();
}

let vectorizationPollingTimer: number | null = null;

function clearVectorizationPolling() {
  if (vectorizationPollingTimer) {
    window.clearTimeout(vectorizationPollingTimer);
    vectorizationPollingTimer = null;
  }
}

function scheduleVectorizationPolling() {
  clearVectorizationPolling();

  if (!tasks.value.some(item => isVectorizationProcessing(item))) {
    return;
  }

  vectorizationPollingTimer = window.setTimeout(async () => {
    await getList();
    scheduleVectorizationPolling();
  }, 3000);
}

watch(
  () =>
    tasks.value
      .map(item => `${item.paperId}:${item.processingStatus || ''}:${item.retrievalIndexedLocationCount ?? ''}`)
      .join('|'),
  () => {
    scheduleVectorizationPolling();
  },
  { immediate: true }
);

onUnmounted(() => {
  window.removeEventListener('resize', syncLibraryViewport);
  clearVectorizationPolling();
});

// #region 文件续传

function formatNumber(value?: number | string | null) {
  return Number(value || 0).toLocaleString();
}

function compactNumber(value?: number | string | null) {
  const number = Number(value || 0);
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 1_000) return `${(number / 1_000).toFixed(1)}K`;
  return number.toLocaleString();
}

// 任务列表存在文件，直接续传
</script>

<template>
  <div class="paper-library-shell">
    <main class="paper-library-page">
      <div class="paper-library-card">
        <div class="library-topbar">
          <button type="button" class="library-back" @click="router.push('/chat')">
            <icon-lucide:arrow-left class="text-16px" />
            <span>返回 Chat</span>
          </button>
          <div class="library-title-block">
            <div>
              <p class="library-eyebrow">PAPERLOOM · RESEARCH LIBRARY</p>
              <h1>Library</h1>
              <p class="library-sub">Research PDFs, parser state, evidence assets, and reusable source sets.</p>
            </div>
          </div>
          <NRadioGroup
            :value="libraryView"
            name="library-view"
            size="small"
            class="library-view-switch"
            @update:value="handleLibraryViewUpdate"
          >
            <NRadioButton v-for="option in libraryViewOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </NRadioButton>
          </NRadioGroup>
        </div>

        <div v-if="libraryView === 'papers'" class="library-toolbar">
          <div class="library-search-control">
            <NInput
              v-model:value="paperLibraryQuery"
              size="small"
              clearable
              placeholder="Filename, title, paper ID"
              @keyup.enter="handlePaperLibrarySearch"
              @clear="handlePaperLibrarySearchReset"
            >
              <template #prefix>
                <icon-lucide:file-search class="text-icon" />
              </template>
            </NInput>
            <NButton size="small" secondary :loading="loading" @click="handlePaperLibrarySearch">
              <template #icon>
                <icon-lucide:search class="text-icon" />
              </template>
              Search
            </NButton>
            <NButton
              v-if="activePaperLibraryQuery"
              size="small"
              quaternary
              :disabled="loading"
              @click="handlePaperLibrarySearchReset"
            >
              Reset
            </NButton>
          </div>
          <TableHeaderOperation :addable="false" :loading="loading" compact @refresh="getList">
            <template #default>
              <NButton v-if="canUploadPapers" size="small" type="primary" @click="handleUpload">
                <template #icon>
                  <icon-lucide:upload class="text-icon" />
                </template>
                Upload
              </NButton>
            </template>
          </TableHeaderOperation>
        </div>

        <template v-if="libraryView === 'papers'">
          <div class="library-summary">
            <div v-for="item in libraryStats" :key="item.label" class="library-summary__item">
              <span class="library-summary__label">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <span>{{ item.detail }}</span>
            </div>
          </div>

          <PaperMobileList
            v-if="isMobileLibrary"
            :rows="tableTasks"
            :can-manage="canManageFile"
            :is-admin="authStore.isAdmin"
            @preview="row => handleFilePreview(row.originalFilename, row.paperId)"
            @tables="handleOpenTables"
            @figures="handleOpenFigures"
            @parser="handleOpenParserArtifact"
            @retry="handleRetryVectorization"
            @publish="row => handleUpdatePublication(row, true)"
            @unpublish="row => handleUpdatePublication(row, false)"
            @delete="handleDelete"
          />
          <NPagination
            v-if="isMobileLibrary && Number(mobilePagination.itemCount || 0) > Number(mobilePagination.pageSize || 10)"
            class="library-mobile-pagination"
            :page="Number(mobilePagination.page || 1)"
            :page-size="Number(mobilePagination.pageSize || 10)"
            :item-count="Number(mobilePagination.itemCount || 0)"
            :page-slot="5"
            @update:page="handleMobilePageUpdate"
          />
          <!-- 桌面：馆藏目录——每篇论文一条有分量的条目 -->
          <template v-else>
            <NSpin :show="loading">
              <div v-if="tableTasks.length" class="library-catalog">
                <article v-for="row in tableTasks" :key="row.paperId" class="catalog-item">
                  <div class="catalog-main">
                    <button
                      type="button"
                      class="catalog-title"
                      title="预览 PDF"
                      @click="handleFilePreview(row.originalFilename, row.paperId)"
                    >
                      {{ row.paperTitle || row.originalFilename }}
                    </button>
                    <p class="catalog-meta">
                      <span v-if="row.authors">{{ row.authors }}</span>
                      <span v-if="row.publicationYear">· {{ row.publicationYear }}</span>
                      <span v-if="row.venue">· {{ row.venue }}</span>
                      <span v-if="row.arxivId" class="catalog-arxiv">arXiv:{{ row.arxivId }}</span>
                      <span
                        v-if="row.originalFilename && row.paperTitle && row.originalFilename !== row.paperTitle"
                        class="catalog-file"
                      >
                        {{ row.originalFilename }}
                      </span>
                    </p>
                    <p v-if="row.abstractText" class="catalog-abstract">{{ row.abstractText }}</p>
                  </div>
                  <div class="catalog-side">
                    <span class="catalog-status" :data-tone="catalogState(row).tone">
                      {{ catalogState(row).label }}
                    </span>
                    <p v-if="Number(row.retrievalIndexedTokenCount || 0) > 0" class="catalog-counts">
                      {{ catalogCounts(row) }}
                    </p>
                    <div class="catalog-actions">
                      <NButton
                        quaternary
                        circle
                        size="small"
                        aria-label="预览 PDF"
                        @click="handleFilePreview(row.originalFilename, row.paperId)"
                      >
                        <icon-lucide:eye class="text-14px" />
                      </NButton>
                      <NDropdown
                        trigger="click"
                        :options="paperRowMenuOptions(row)"
                        @select="key => handlePaperRowMenuAction(key, row)"
                      >
                        <NButton quaternary circle size="small" aria-label="更多操作">
                          <icon-lucide:ellipsis class="text-14px" />
                        </NButton>
                      </NDropdown>
                    </div>
                  </div>
                </article>
              </div>
              <div v-else class="library-empty">
                <span class="library-empty__icon">
                  <icon-lucide:file-search class="text-18px" />
                </span>
                <div class="library-empty__title">
                  {{ activePaperLibraryQuery ? 'No paper matches this search' : 'No papers yet' }}
                </div>
                <p class="library-empty__hint">
                  {{ activePaperLibraryQuery ? '换个标题、作者或文件名再试。' : '上传一篇 PDF 开始建立你的文献库。' }}
                </p>
              </div>
            </NSpin>
            <NPagination
              v-if="Number(mobilePagination.itemCount || 0) > Number(mobilePagination.pageSize || 10)"
              class="library-catalog-pagination"
              :page="Number(mobilePagination.page || 1)"
              :page-size="Number(mobilePagination.pageSize || 10)"
              :item-count="Number(mobilePagination.itemCount || 0)"
              :page-slot="7"
              @update:page="handleMobilePageUpdate"
            />
          </template>
        </template>

        <CollectionsPanel v-else />
      </div>
    </main>
    <UploadDialog v-if="canUploadPapers" v-model:visible="uploadVisible" />

    <NModal v-model:show="tableModalVisible" class="paper-table-modal-shell" :auto-focus="false">
      <div class="paper-table-modal">
        <header class="paper-table-modal__header">
          <div>
            <div class="paper-table-modal__title">Extracted Tables</div>
            <div class="paper-table-modal__subtitle">{{ tableModalTitle }}</div>
          </div>
          <NButton quaternary circle size="small" @click="tableModalVisible = false">
            <template #icon>
              <icon-lucide:x />
            </template>
          </NButton>
        </header>

        <div v-if="tableModalLoading" class="paper-table-modal__state">Loading tables...</div>
        <div v-else-if="!tableModalRows.length" class="paper-table-modal__state">No extracted tables.</div>
        <div v-else class="paper-table-modal__list">
          <article v-for="table in tableModalRows" :key="table.tableId" class="paper-table-modal__item">
            <div class="paper-table-modal__item-head">
              <strong>{{ table.caption || table.tableId }}</strong>
              <span>Page {{ table.pageNumber || 'N/A' }}</span>
            </div>
            <div class="paper-table-modal__meta">
              <span>{{ table.rowCount || 0 }} rows</span>
              <span>{{ table.columnCount || 0 }} cols</span>
              <span>{{ table.screenshotAvailable ? 'screenshot saved' : 'no screenshot' }}</span>
            </div>
            <pre>{{ table.tableMarkdown || table.tableText || 'No table text captured.' }}</pre>
          </article>
        </div>
      </div>
    </NModal>

    <NModal v-model:show="figureModalVisible" class="paper-figure-modal-shell" :auto-focus="false">
      <div class="paper-figure-modal">
        <header class="paper-table-modal__header">
          <div>
            <div class="paper-table-modal__title">Extracted Figures</div>
            <div class="paper-table-modal__subtitle">{{ figureModalTitle }}</div>
          </div>
          <NButton quaternary circle size="small" @click="figureModalVisible = false">
            <template #icon>
              <icon-lucide:x />
            </template>
          </NButton>
        </header>

        <div v-if="figureModalLoading" class="paper-table-modal__state">Loading figures...</div>
        <div v-else-if="!figureModalRows.length" class="paper-table-modal__state">No extracted figures.</div>
        <div v-else class="paper-figure-modal__list">
          <article v-for="figure in figureModalRows" :key="figure.figureId" class="paper-figure-modal__item">
            <div class="paper-figure-modal__head">
              <div>
                <strong>{{ figure.caption || figure.figureId }}</strong>
                <div class="paper-figure-modal__meta">
                  <span>Page {{ figure.pageNumber || 'N/A' }}</span>
                  <span>{{ figure.elementType || 'IMAGE' }}</span>
                  <span v-if="figure.sectionTitle">{{ figure.sectionTitle }}</span>
                  <span v-if="figure.confidence">confidence: {{ figure.confidence }}</span>
                </div>
              </div>
              <NButton
                v-if="figure.screenshotAvailable"
                size="tiny"
                secondary
                @click="handleFigureScreenshot(figure.figureId)"
              >
                Open image
              </NButton>
            </div>
            <p v-if="figure.searchableText" class="paper-figure-modal__caption">{{ figure.searchableText }}</p>
            <p v-else class="paper-figure-modal__caption paper-figure-modal__caption--empty">No caption captured.</p>
          </article>
        </div>
      </div>
    </NModal>

    <!-- 文件预览弹窗 -->
    <NModal v-model:show="previewVisible" class="document-preview-modal" :auto-focus="false">
      <div class="document-preview-modal-shell">
        <FilePreview
          v-if="previewVisible"
          :paper-title="previewPaperTitle"
          :paper-id="previewPaperId"
          :visible="previewVisible"
          @close="closeFilePreview"
        />
      </div>
    </NModal>
  </div>
</template>

<style lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

.paper-library-shell {
  display: flex;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: var(--color-bg);
  color: var(--color-text);
}

.library-empty {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 34px 16px 40px;
  text-align: center;
}

.library-empty__icon {
  display: inline-flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-soft);
  border-radius: 50%;
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  margin-bottom: 4px;
}

.library-empty__title {
  color: var(--color-text);
  font-size: var(--text-md);
  font-weight: 600;
}

.library-empty__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.library-mobile-pagination {
  justify-content: center;
  margin-top: 16px;
}

.settings-modal-host {
  width: calc(100vw - 32px);
}

.paper-library-page {
  flex: 1 1 0;
  min-width: 0;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0;
}

/* 整页式布局：内容直接铺满页面，只有大边距，不再有卡片壳 */
.paper-library-page {
  padding: 28px clamp(20px, 5vw, 64px) 56px;
}

.paper-library-card {
  min-height: fit-content;
  max-width: 1080px;
  margin: 0 auto;
}

.library-topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  margin-bottom: 18px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--color-border-soft);
}

.library-back {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  padding: var(--space-1, 4px) 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  cursor: pointer;
  transition:
    background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.library-back:hover {
  background: var(--color-surface-alt);
  color: var(--color-text);
}

.library-eyebrow {
  margin: 0 0 6px;
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.14em;
}

.library-topbar h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 650;
  line-height: 1.25;
}

.library-sub {
  margin: 4px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

/* ---------- 馆藏目录：整页目录流，条目之间细线分隔 ---------- */
.library-catalog {
  display: flex;
  flex-direction: column;
}

.catalog-item {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 4px;
  border-bottom: 1px solid var(--color-border-soft);
}

.catalog-item:first-child {
  border-top: 1px solid var(--color-border-soft);
}

.catalog-main {
  flex: 1 1 auto;
  min-width: 0;
}

.catalog-title {
  overflow: hidden;
  border: 0;
  padding: 0;
  background: none;
  color: var(--color-text);
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.45;
  text-align: left;
  text-overflow: ellipsis;
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-standard);
}

.catalog-title:hover {
  color: var(--color-research);
}

.catalog-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 6px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.catalog-arxiv {
  font-family: var(--font-utility);
}

.catalog-file {
  overflow: hidden;
  max-width: 260px;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 0.7;
}

.catalog-abstract {
  display: -webkit-box;
  overflow: hidden;
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.catalog-side {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.catalog-status {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 500;

  &[data-tone='ready'] {
    background: rgba(22, 128, 57, 0.1);
    color: var(--color-success);
  }

  &[data-tone='processing'] {
    background: var(--color-surface-alt);
    color: var(--color-text-muted);
  }

  &[data-tone='broken'] {
    background: rgba(217, 45, 32, 0.1);
    color: var(--color-error);
  }

  &[data-tone='warning'] {
    background: rgba(183, 121, 31, 0.12);
    color: var(--color-warning-text);
  }
}

.catalog-counts {
  margin: 0;
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: 11px;
}

.catalog-actions {
  display: flex;
  gap: 2px;
}

.library-catalog-pagination {
  justify-content: flex-end;
  margin-top: 18px;
}

.library-title-block {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 240px;
  max-width: 640px;
}

.library-title-icon {
  display: none;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-card-band);
  color: var(--color-primary);
  font-size: 17px;
}

.library-title-block h1 {
  margin: 0 0 2px;
  color: var(--color-text);
  font-size: 24px;
  font-weight: 620;
  letter-spacing: 0;
  line-height: 1.1;
}

.library-title-block p {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.35;
}

.library-toolbar {
  display: flex;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.library-search-control {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.library-search-control .n-input {
  width: clamp(220px, 26vw, 340px);
}

.library-view-switch {
  flex: 0 0 auto;
}

.library-search-control .n-button {
  border-radius: var(--radius-sm);
}

.library-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-bottom: 22px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  overflow: hidden;
}

.library-summary__item {
  min-width: 0;
  padding: 13px 16px;
  border-right: 1px solid var(--color-border-soft);
}

.library-summary__item:last-child {
  border-right: 0;
}

.library-summary__label,
.library-summary__item > span:last-child {
  display: block;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: 1.3;
}

.library-summary__value,
.library-summary__item > strong {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 650;
}

.library-summary__label {
  font-family: var(--font-interface);
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.library-summary__item strong {
  display: block;
  margin: 4px 0 3px;
  color: var(--color-text);
  font-family: var(--font-interface);
  font-size: 21px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  line-height: 1.05;
  letter-spacing: -0.01em;
}

.paper-library-card .n-data-table {
  --n-td-color: var(--color-surface) !important;
  --n-th-color: var(--color-surface-alt) !important;
  --n-border-color: var(--color-border-soft) !important;
  --n-th-text-color: var(--color-text-muted) !important;
  --n-td-text-color: var(--color-text) !important;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.paper-library-card .n-data-table-th {
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-family: var(--font-interface);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  padding: 9px 12px;
  text-transform: uppercase;
}

.paper-library-card .n-data-table-td {
  background: var(--color-surface);
  vertical-align: middle;
  border-bottom: 1px solid var(--color-border-soft);
  padding: 10px 12px;
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.paper-library-card .n-data-table-tr:last-child .n-data-table-td {
  border-bottom: 0;
}

.paper-library-card .n-data-table-tr:hover .n-data-table-td {
  background: var(--color-surface-alt);
}

.library-file-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.library-file-cell__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.library-file-cell__icon:hover {
  border-color: var(--color-border);
  color: var(--color-text);
}

.library-file-cell__icon:disabled,
.library-file-cell__name:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.library-file-cell__icon:disabled:hover {
  border-color: var(--color-border-soft);
}

.library-file-icon {
  font-size: 18px;
}

.library-file-cell__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.library-file-cell__name {
  display: block;
  max-width: 100%;
  overflow: hidden;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font-family: var(--font-interface);
  font-size: var(--text-md);
  font-weight: 600;
  line-height: 1.35;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-file-cell__name:hover {
  color: var(--color-primary);
}

.library-file-cell__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: 1.3;
}
.library-file-cell__file {
  overflow: hidden;
  max-width: 220px;
  color: var(--color-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-digest-chip {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-family: var(--font-utility);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

.library-digest-chip:hover {
  color: var(--color-text);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.library-size-cell {
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

/* Evidence column: one line of index usage, one line of asset readiness.
   Both are plain text with a leading dot so the row stays scannable. */
.library-evidence-cell {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.library-index-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  color: var(--color-text);
  font-family: var(--font-utility);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-index-line__sep {
  color: var(--color-text-placeholder);
}

.library-index-line__text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.library-index-line--processing {
  color: var(--color-warning-text);
}

.library-index-line--failed {
  color: var(--color-error);
}

.library-index-line--empty {
  color: var(--color-text-placeholder);
}

.library-inline-action {
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-error);
  cursor: pointer;
  font-size: var(--text-xs);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.library-asset-strip {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: 1.35;
  white-space: nowrap;
}

.library-asset-dot,
.library-asset-count {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.library-asset-dot::before {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--color-text-placeholder);
  content: '';
}

.library-asset-dot--ok::before {
  background: var(--color-success);
}

.library-asset-dot--warn::before {
  background: var(--color-warning);
}

.library-asset-count {
  color: var(--color-text-placeholder);
  font-family: var(--font-utility);
  font-variant-numeric: tabular-nums;
}

.library-pipeline-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 24px;
  padding: 2px 10px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-card-band);
  color: var(--color-text-muted);
  font-family: var(--font-interface);
  font-size: var(--text-xs);
  font-weight: 600;
  white-space: nowrap;
}

.library-pipeline-status__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
}

.library-pipeline-status--ready {
  border-color: var(--color-success);
  background: var(--color-card-band);
  color: var(--color-success);
}

.library-pipeline-status--ready .library-pipeline-status__dot {
  background: var(--color-success);
}

.library-pipeline-status--processing {
  border-color: var(--color-accent);
  background: var(--color-card-band);
  color: var(--color-accent);
}

.library-pipeline-status--processing .library-pipeline-status__dot {
  background: var(--color-accent);
}

.library-pipeline-status--warning {
  border-color: var(--color-warning);
  background: var(--color-card-band);
  color: var(--color-warning);
}

.library-pipeline-status--warning .library-pipeline-status__dot {
  background: var(--color-warning);
}

.library-pipeline-status--broken {
  border-color: var(--color-error);
  background: var(--color-card-band);
  color: var(--color-error);
}

.library-pipeline-status--broken .library-pipeline-status__dot {
  background: var(--color-error);
}

.library-progress-cell {
  display: grid;
  grid-template-columns: minmax(60px, 1fr) 38px;
  align-items: center;
  gap: 8px;
  color: var(--color-accent);
  font-family: var(--font-interface);
  font-size: var(--text-xs);
  font-weight: 700;
}

.library-date-cell {
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.library-scope {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  white-space: nowrap;
}

.library-scope::before {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-text-placeholder);
  content: '';
}

.library-scope--global::before {
  background: var(--color-success);
}

.library-action-group {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: nowrap;
}

.library-action-group .n-button {
  min-height: 26px;
  border-radius: var(--radius-sm);
}

.n-progress-icon.n-progress-icon--as-text {
  white-space: nowrap;
}

.document-preview-modal {
  width: min(96vw, 1320px);
}

.document-preview-modal-shell {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  box-shadow: 10px 10px 0 var(--color-border);
}

.paper-table-modal-shell {
  width: min(92vw, 860px);
}

.paper-table-modal {
  max-height: min(82vh, 760px);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  box-shadow: 10px 10px 0 var(--color-border);
}

.paper-table-modal__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-card-band);
  padding: 14px 16px;
}

.paper-table-modal__title {
  color: var(--color-primary);
  font-size: 16px;
  font-weight: 800;
}

.paper-table-modal__subtitle {
  max-width: 680px;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.paper-table-modal__state {
  padding: 28px 16px;
  color: var(--color-text-muted);
  font-size: 13px;
}

.paper-table-modal__list {
  display: grid;
  max-height: calc(min(82vh, 760px) - 62px);
  gap: 12px;
  overflow: auto;
  padding: 14px 16px 18px;
}

.paper-table-modal__item {
  display: grid;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 12px;
}

.paper-table-modal__item-head,
.paper-table-modal__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.paper-table-modal__item-head strong {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.paper-table-modal__item-head span,
.paper-table-modal__meta {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 700;
}

.paper-table-modal__meta {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.paper-table-modal__item pre {
  max-height: 260px;
  overflow: auto;
  margin: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  padding: 10px;
  color: var(--color-text);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

@media (max-width: 1180px) {
  .library-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .library-search-control {
    flex: 1 1 auto;
  }

  .library-search-control .n-input {
    width: auto;
    min-width: 160px;
    flex: 1 1 200px;
  }

  .library-summary__item {
    border-bottom: 1px solid var(--color-border-soft);
  }

  .library-summary__item:nth-child(3n) {
    border-right: 0;
  }
}

@media (max-width: 640px) {
  .library-topbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    margin-bottom: 18px;
    padding: 0;
  }

  .library-title-block,
  .library-toolbar {
    width: 100%;
  }

  .library-title-block h1 {
    font-size: 24px;
  }

  .library-toolbar {
    justify-content: flex-start;
  }

  .library-toolbar .n-space {
    width: 100%;
  }

  .paper-library-card .n-card__content {
    padding: 12px 12px 18px;
  }

  .library-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .library-summary__item:nth-child(3n) {
    border-right: 1px solid var(--color-border-soft);
  }

  .library-summary__item:nth-child(2n) {
    border-right: 0;
  }
}

.paper-figure-modal-shell {
  width: min(92vw, 860px);
}

.paper-figure-modal {
  max-height: min(82vh, 760px);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  box-shadow: 10px 10px 0 var(--color-border);
}

.paper-figure-modal__list {
  display: grid;
  max-height: calc(min(82vh, 760px) - 62px);
  gap: 12px;
  overflow: auto;
  padding: 14px 16px 18px;
}

.paper-figure-modal__item {
  display: grid;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 12px;
}

.paper-figure-modal__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.paper-figure-modal__head strong {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.4;
}

.paper-figure-modal__meta {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 600;
}

.paper-figure-modal__caption {
  margin: 0;
  color: var(--color-text);
  font-family: var(--font-reading);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.paper-figure-modal__caption--empty {
  color: var(--color-text-muted);
  font-style: italic;
}
</style>
