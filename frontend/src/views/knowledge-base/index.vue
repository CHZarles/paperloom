<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NUpload } from 'naive-ui';
import type { FlatResponseData } from '@sa/axios';
import { uploadAccept } from '@/constants/common';
import { getUploadFileValidationError } from '@/store/modules/knowledge-base/upload-validation';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import ConversationSidebar from '@/views/chat/modules/conversation-sidebar.vue';
import SettingsPanel from '@/views/personal-center/modules/settings-panel.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';
import CollectionsPanel from './modules/collections-panel.vue';

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const sidebarCollapsed = ref(false);
const settingsVisible = ref(false);
const settingsModalHostStyle = { width: 'min(1480px, calc(100vw - 32px))' };

function normalizeLibraryView(value: unknown): 'papers' | 'collections' {
  return value === 'collections' ? 'collections' : 'papers';
}

const libraryView = ref<'papers' | 'collections'>(normalizeLibraryView(route.query.view));
const libraryViewOptions = [
  { label: 'Papers', value: 'papers' },
  { label: 'Collections', value: 'collections' }
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

const assetWarningLabels: Record<string, string> = {
  parser_artifact_missing: 'Parser artifact missing',
  page_screenshots_missing: 'Page screenshots missing'
};

const activeProcessingStatuses = new Set<Api.Paper.UploadTask['processingStatus']>([
  'PENDING',
  'PROCESSING',
  'MINERU_RUNNING',
  'MINERU_ARTIFACT_SAVED',
  'MAPPING_STRUCTURED_CONTENT',
  'RENDERING_VISUAL_ASSETS',
  'CHUNKING',
  'EMBEDDING',
  'INDEXING'
]);

const processingStatusLabels: Partial<Record<NonNullable<Api.Paper.UploadTask['processingStatus']>, string>> = {
  PENDING: 'Queued',
  PROCESSING: 'Processing',
  MINERU_RUNNING: 'Parsing',
  MINERU_ARTIFACT_SAVED: 'Parsed',
  MAPPING_STRUCTURED_CONTENT: 'Mapping',
  RENDERING_VISUAL_ASSETS: 'Rendering',
  CHUNKING: 'Chunking',
  EMBEDDING: 'Embedding',
  INDEXING: 'Indexing'
};

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

async function apiFn(params: Api.Common.CommonSearchParams = {}): Promise<FlatResponseData<Api.Paper.List>> {
  const page = params.page && params.page > 0 ? params.page : 1;
  const size = params.size && params.size > 0 ? params.size : 10;
  const requestParams = { ...params, page, size };
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
  return authStore.isAdmin || String(row.userId) === String(authStore.userInfo.id);
}

function renderIcon(originalFilename: string) {
  const ext = getFileExt(originalFilename);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="library-file-icon" />;
    return <SvgIcon localIcon="dflt" class="library-file-icon" />;
  }
  return null;
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

const { columns, columnChecks, data, getData, loading, mobilePagination } = useTable({
  apiFn,
  showTotal: true,
  immediate: false,
  columns: () => [
    {
      key: 'originalFilename',
      title: 'Paper / 文件',
      width: 320,
      render: row => (
        <div class="library-file-cell">
          <button
            class="library-file-cell__icon"
            type="button"
            title="预览 PDF"
            onClick={() => handleFilePreview(row.originalFilename, row.paperId)}
          >
            {renderIcon(row.originalFilename)}
          </button>
          <div class="library-file-cell__copy">
            <NEllipsis lineClamp={2} tooltip>
              <button
                type="button"
                class="library-file-cell__name"
                title="预览 PDF"
                onClick={() => handleFilePreview(row.originalFilename, row.paperId)}
              >
                {row.originalFilename}
              </button>
            </NEllipsis>
            <div class="library-file-cell__meta library-file-cell__meta--stacked">
              <button
                type="button"
                class="library-digest-chip"
                onClick={() => {
                  navigator.clipboard.writeText(row.paperId);
                  window.$message?.success('MD5已复制');
                }}
                title="点击复制MD5"
              >
                {row.paperId.substring(0, 8)}
              </button>
              <span>{dayjs(row.createdAt).format('YYYY-MM-DD')}</span>
            </div>
          </div>
        </div>
      )
    },
    {
      key: 'totalSize',
      title: 'Size',
      width: 82,
      render: row => <span class="library-size-cell">{formatFileSize(row.totalSize ?? row.sourceFileSizeBytes)}</span>
    },
    {
      key: 'estimatedEmbeddingTokens',
      title: 'Index Budget / 索引',
      width: 270,
      render: row => renderIndexUsage(row)
    },
    {
      key: 'status',
      title: 'Pipeline / 状态',
      width: 132,
      render: row => renderPipelineStatus(row)
    },
    {
      key: 'orgTagName',
      title: 'Scope / 权限',
      width: 160,
      render: row => (
        <div class="library-scope-stack">
          <span class="library-scope-chip">{row.orgTagName || '未分类'}</span>
          {row.isPublic ? (
            <span class="library-visibility library-visibility--public">Public</span>
          ) : (
            <span class="library-visibility library-visibility--private">Private</span>
          )}
        </div>
      )
    },
    {
      key: 'createdAt',
      title: 'Imported',
      width: 108,
      render: row => (
        <div class="library-date-cell">
          <span>{dayjs(row.createdAt).format('YYYY-MM-DD')}</span>
          <span>{dayjs(row.createdAt).format('HH:mm:ss')}</span>
        </div>
      )
    },
    {
      key: 'operate',
      title: 'Actions / 操作',
      width: 260,
      render: row => (
        <div class="library-action-group">
          {canManageFile(row) ? renderResumeUploadButton(row) : null}
          <NButton
            type="primary"
            secondary
            size="small"
            onClick={() => handleFilePreview(row.originalFilename, row.paperId)}
          >
            {{
              icon: () => <SvgIcon icon="lucide:eye" class="text-14px" />,
              default: () => '预览'
            }}
          </NButton>
          <NButton secondary size="small" disabled={!row.tableAsset?.tableCount} onClick={() => handleOpenTables(row)}>
            {{
              icon: () => <SvgIcon icon="lucide:table-2" class="text-14px" />,
              default: () => 'Tables'
            }}
          </NButton>
          {canManageFile(row) ? (
            <NButton
              secondary
              size="small"
              disabled={!row.parserArtifact?.available}
              onClick={() => handleOpenParserArtifact(row)}
            >
              {{
                icon: () => <SvgIcon icon="lucide:file-json" class="text-14px" />,
                default: () => 'Parser JSON'
              }}
            </NButton>
          ) : null}
          {canManageFile(row) ? (
            <NPopconfirm onPositiveClick={() => handleDelete(row.paperId)}>
              {{
                default: () => '确认删除当前文件吗？',
                trigger: () => (
                  <NButton type="error" secondary size="small">
                    {{
                      icon: () => <SvgIcon icon="lucide:trash-2" class="text-14px" />,
                      default: () => '删除'
                    }}
                  </NButton>
                )
              }}
            </NPopconfirm>
          ) : null}
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const tableTasks = computed(() => {
  const remoteRows = data.value.map(item => tasks.value.find(task => task.paperId === item.paperId) || item);
  const localRows = tasks.value.filter(task => task.file && !remoteRows.some(item => item.paperId === task.paperId));

  return [...localRows, ...remoteRows];
});

const libraryStats = computed(() => {
  const rows = tableTasks.value;
  const searchable = rows.filter(item => isPaperSearchable(item)).length;
  const processing = rows.filter(item => isPipelineActive(item)).length;
  const failed = rows.filter(item => isPipelineFailed(item)).length;
  const estimatedTokens = rows.reduce((sum, item) => sum + Number(item.estimatedEmbeddingTokens || 0), 0);

  return [
    {
      label: 'Documents',
      value: formatNumber(mobilePagination.value.itemCount || rows.length),
      detail: 'accessible'
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
      label: 'Est. tokens',
      value: compactNumber(estimatedTokens),
      detail: 'embedding'
    }
  ];
});

onMounted(async () => {
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
    orgTag: source.orgTag,
    orgTagName: source.orgTagName,
    isPublic: source.isPublic,
    createdAt: source.createdAt,
    mergedAt: source.mergedAt,
    estimatedEmbeddingTokens: source.estimatedEmbeddingTokens,
    estimatedChunkCount: source.estimatedChunkCount,
    actualEmbeddingTokens: source.actualEmbeddingTokens,
    actualChunkCount: source.actualChunkCount,
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

async function handleDelete(paperId: string) {
  const index = tasks.value.findIndex(task => task.paperId === paperId);
  const task = index !== -1 ? tasks.value[index] : null;

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
    window.$message?.success('删除成功');
    await getData();
  }
}

async function handleOpenParserArtifact(row: Api.Paper.UploadTask) {
  const { error, data: parserArtifact } = await request<Api.Paper.ParserArtifactResponse>({
    url: `/papers/${row.paperId}/parser-artifact`
  });

  if (error || !parserArtifact?.downloadUrl) {
    window.$message?.error(error?.message || 'Parser JSON 不存在');
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
    window.$message?.error(error.message || '表格列表加载失败');
    return;
  }
  tableModalRows.value = tables || [];
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

// #region 检索论文库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

function formatFileSize(value?: number | string | null) {
  const bytes = normalizeBytes(value);
  return bytes > 0 ? fileSize(bytes) : 'N/A';
}

function renderPipelineStatus(row: Api.Paper.UploadTask) {
  if (row.status === UploadStatus.Break) {
    return renderPipelineBadge('Interrupted', 'broken');
  }

  if (!isUploadCompleted(row)) {
    return renderUploadProgress(row.progress);
  }

  if (row.processingStatus === 'FAILED') {
    return renderPipelineBadge('Failed', 'broken', row.processingErrorMessage || 'Parse or index failed');
  }

  if (isPaperSearchable(row)) {
    return renderPipelineBadge('Searchable', 'ready');
  }

  if (isVectorizationProcessing(row)) {
    const label = processingStatusLabels[row.processingStatus || 'PROCESSING'] || 'Processing';
    return renderPipelineBadge(label, 'processing');
  }

  if (row.processingStatus === 'COMPLETED') {
    return renderPipelineBadge('Index missing', 'warning', 'Index usage is missing; retry vectorization if needed');
  }

  return renderPipelineBadge('Pending', 'warning');
}

function renderPipelineBadge(label: string, status: 'ready' | 'processing' | 'warning' | 'broken', title?: string) {
  return (
    <span class={`library-pipeline-status library-pipeline-status--${status}`} title={title}>
      <span class="library-pipeline-status__dot"></span>
      {label}
    </span>
  );
}

function renderUploadProgress(percentage: number) {
  return (
    <div class="library-progress-cell">
      <NProgress percentage={percentage || 0} processing showIndicator={false} />
      <span>{Number(percentage || 0)}%</span>
    </div>
  );
}

function renderIndexUsage(row: Api.Paper.UploadTask) {
  return (
    <div class="library-index-cell">
      {renderIndexLine('EST', row.estimatedEmbeddingTokens, row.estimatedChunkCount)}
      {renderActualIndexLine(row)}
      {renderAssetStatus(row)}
    </div>
  );
}

function renderAssetStatus(row: Api.Paper.UploadTask) {
  const parserSaved = Boolean(row.parserArtifact?.available);
  const tableCount = Number(row.tableAsset?.tableCount || 0);
  const figureCount = Number(row.figureAsset?.figureCount || 0);
  const formulaCount = Number(row.formulaAsset?.formulaCount || 0);
  const pageCount = Number(row.visualAsset?.pageScreenshotCount || 0);
  const warningText = formatAssetWarnings(row.assetWarnings);
  return (
    <div class="library-asset-strip">
      {renderEvidenceReadiness(row, warningText)}
      <span class={parserSaved ? 'library-asset-pill library-asset-pill--ok' : 'library-asset-pill'}>
        Parser: {parserSaved ? 'saved' : 'missing'}
      </span>
      <span class="library-asset-pill">Tables: {tableCount}</span>
      <span class="library-asset-pill">Figures: {figureCount}</span>
      <span class="library-asset-pill">Formulas: {formulaCount}</span>
      <span class="library-asset-pill">Pages: {pageCount}</span>
      {warningText ? (
        <span class="library-asset-pill library-asset-pill--warning" title={warningText}>
          Warnings: {row.assetWarnings?.length || 0}
        </span>
      ) : null}
    </div>
  );
}

function renderEvidenceReadiness(row: Api.Paper.UploadTask, warningText: string) {
  const title = warningText || 'Evidence assets ready';

  if (row.pdfEvidenceAvailable || row.evidenceAssetLevel === 'PDF_VISUAL') {
    return (
      <span class="library-asset-pill library-asset-pill--ok" title={title}>
        PDF evidence: ready
      </span>
    );
  }

  return (
    <span class="library-asset-pill library-asset-pill--warning" title={title}>
      PDF evidence: pending
    </span>
  );
}

function formatAssetWarnings(warnings?: string[]) {
  return (warnings || []).map(warning => assetWarningLabels[warning] || warning).join('; ');
}

function renderIndexLine(label: string, tokens?: number | null, chunks?: number | null) {
  if (!tokens) {
    return (
      <div class="library-index-line library-index-line--empty">
        <span class="library-index-line__label">{label}</span>
        <span>no estimate</span>
      </div>
    );
  }

  const tokenLabel = Number(tokens).toLocaleString();
  const chunkLabel = Number(chunks || 0).toLocaleString();
  return (
    <div class="library-index-line">
      <div class="library-index-line__top">
        <span class="library-index-line__label">{label}</span>
        <span>{tokenLabel} Tokens</span>
      </div>
      <div class="library-index-line__meta">{chunkLabel} chunks</div>
    </div>
  );
}

function isVectorizationProcessing(row: Api.Paper.UploadTask) {
  return activeProcessingStatuses.has(row.processingStatus || null);
}

function isUploadCompleted(row: Api.Paper.UploadTask) {
  return row.status === UploadStatus.Completed || row.uploadStatus === 'COMPLETED' || row.uploadStatus === 1;
}

function isPaperSearchable(row: Api.Paper.UploadTask) {
  return (
    isUploadCompleted(row) &&
    row.processingStatus === 'COMPLETED' &&
    hasActualVectorizationUsage(row) &&
    Number(row.actualChunkCount || 0) > 0
  );
}

function isPipelineActive(row: Api.Paper.UploadTask) {
  if (row.status === UploadStatus.Pending || row.status === UploadStatus.Uploading) return true;
  return isUploadCompleted(row) && isVectorizationProcessing(row);
}

function isPipelineFailed(row: Api.Paper.UploadTask) {
  return row.status === UploadStatus.Break || row.processingStatus === 'FAILED';
}

function hasActualVectorizationUsage(row: Api.Paper.UploadTask) {
  return row.actualEmbeddingTokens !== null && row.actualEmbeddingTokens !== undefined;
}

function canRetryVectorization(row: Api.Paper.UploadTask) {
  if (!canManageFile(row)) return false;
  if (row.processingStatus === 'FAILED') return true;
  if (row.processingStatus === 'COMPLETED' && !hasActualVectorizationUsage(row)) return true;
  if (!hasActualVectorizationUsage(row) && row.estimatedEmbeddingTokens) return true;
  return false;
}

async function handleRetryVectorization(row: Api.Paper.UploadTask) {
  const { error } = await request({
    url: `/papers/${row.paperId}/vectorization/retry`,
    method: 'POST'
  });

  if (error) return;

  row.processingStatus = 'PROCESSING';
  row.processingErrorMessage = null;
  row.actualEmbeddingTokens = undefined;
  row.actualChunkCount = undefined;
  window.$message?.success('已提交异步向量化重试任务');
  await getList();
}

function renderActualIndexLine(row: Api.Paper.UploadTask) {
  if (hasActualVectorizationUsage(row)) {
    return renderIndexLine('ACT', row.actualEmbeddingTokens, row.actualChunkCount);
  }

  if (isVectorizationProcessing(row)) {
    return (
      <div class="library-index-line library-index-line--processing">
        <div class="library-index-line__top">
          <span class="library-index-line__label">ACT</span>
          <span>indexing</span>
        </div>
        <div class="library-index-line__meta">waiting for token writeback</div>
      </div>
    );
  }

  if (row.processingStatus === 'COMPLETED') {
    return (
      <div class="library-index-line">
        <div class="library-index-line__top">
          <span class="library-index-line__label">ACT</span>
          <span>completed</span>
        </div>
        <div class="library-index-line__meta">tokens unavailable</div>
        {canRetryVectorization(row) ? (
          <div>
            <NButton size="tiny" secondary onClick={() => handleRetryVectorization(row)}>
              重试向量化
            </NButton>
          </div>
        ) : null}
      </div>
    );
  }

  if (row.processingStatus === 'FAILED') {
    return (
      <div class="library-index-line library-index-line--failed">
        <div class="library-index-line__top">
          <span class="library-index-line__label">ACT</span>
          <span>failed</span>
        </div>
        <NEllipsis tooltip lineClamp={2} style="color: var(--color-error)">
          {row.processingErrorMessage || '请检查 Embedding 额度或稍后重试'}
        </NEllipsis>
        {canRetryVectorization(row) ? (
          <div>
            <NButton size="tiny" type="error" secondary onClick={() => handleRetryVectorization(row)}>
              重试向量化
            </NButton>
          </div>
        ) : null}
      </div>
    );
  }

  if (canRetryVectorization(row)) {
    return (
      <div class="library-index-line library-index-line--pending">
        <div class="library-index-line__top">
          <span class="library-index-line__label">ACT</span>
          <span>missing</span>
        </div>
        <div class="library-index-line__meta">retry writeback</div>
        <div>
          <NButton size="tiny" secondary onClick={() => handleRetryVectorization(row)}>
            重试向量化
          </NButton>
        </div>
      </div>
    );
  }

  return (
    <div class="library-index-line library-index-line--empty">
      <span class="library-index-line__label">ACT</span>
      <span>not started</span>
    </div>
  );
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
      .map(item => `${item.paperId}:${item.processingStatus || ''}:${item.actualEmbeddingTokens ?? ''}`)
      .join('|'),
  () => {
    scheduleVectorizationPolling();
  },
  { immediate: true }
);

onUnmounted(() => {
  clearVectorizationPolling();
});

// #region 文件续传
function renderResumeUploadButton(row: Api.Paper.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" secondary onClick={() => resumeUpload(row)}>
          {{
            icon: () => <SvgIcon icon="lucide:upload" class="text-14px" />,
            default: () => '续传'
          }}
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" secondary>
          {{
            icon: () => <SvgIcon icon="lucide:upload" class="text-14px" />,
            default: () => '续传'
          }}
        </NButton>
      </NUpload>
    );
  }
  return null;
}

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
function resumeUpload(row: Api.Paper.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.Paper.UploadTask
) {
  const file = options.file.file ?? null;
  const validationError = getUploadFileValidationError(file);
  if (validationError || !file) {
    window.$message?.error(validationError);
    return false;
  }
  const md5 = await calculateMD5(file);
  if (md5 !== row.paperId) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.Paper.Progress>({
    url: '/papers/upload/status',
    params: { paperId: row.paperId }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="paper-library-shell">
    <ConversationSidebar
      v-model:collapsed="sidebarCollapsed"
      class="paper-library-sidebar"
      @open-settings="settingsVisible = true"
    />

    <main class="paper-library-page">
      <NCard :bordered="false" size="small" class="paper-library-card">
        <div class="library-topbar">
          <button
            v-show="sidebarCollapsed"
            type="button"
            class="library-sidebar-open"
            aria-label="展开对话列表"
            @click="sidebarCollapsed = false"
          >
            <icon-lucide:panel-left-open class="text-18px" />
          </button>
          <div class="library-title-block">
            <div>
              <h1>Library</h1>
              <p>Research PDFs, parser state, evidence assets, and reusable source sets.</p>
            </div>
          </div>
          <div class="library-toolbar">
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
            <TableHeaderOperation
              v-if="libraryView === 'papers'"
              v-model:columns="columnChecks"
              :addable="false"
              :loading="loading"
              @refresh="getList"
            >
              <template #prefix>
                <NButton size="small" secondary @click="handleSearch">
                  <template #icon>
                    <icon-lucide:search class="text-icon" />
                  </template>
                  检索文献库
                </NButton>
              </template>
              <template #default>
                <NButton size="small" type="primary" @click="handleUpload">
                  <template #icon>
                    <icon-lucide:upload class="text-icon" />
                  </template>
                  上传文献
                </NButton>
              </template>
            </TableHeaderOperation>
          </div>
        </div>

        <template v-if="libraryView === 'papers'">
          <div class="library-summary">
            <div v-for="item in libraryStats" :key="item.label" class="library-summary__item">
              <span class="library-summary__label">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <span>{{ item.detail }}</span>
            </div>
          </div>

          <NDataTable
            :columns="columns"
            :data="tableTasks"
            size="small"
            :scroll-x="1280"
            :loading="loading"
            remote
            :row-key="row => row.id"
            :pagination="mobilePagination"
            class="library-table"
          />
        </template>

        <CollectionsPanel v-else />
      </NCard>
    </main>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />

    <NModal
      v-model:show="settingsVisible"
      :auto-focus="false"
      class="settings-modal-host"
      :style="settingsModalHostStyle"
    >
      <SettingsPanel @close="settingsVisible = false" />
    </NModal>

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

    <!-- 文件预览弹窗 -->
    <NModal v-model:show="previewVisible" class="document-preview-modal" :auto-focus="false">
      <div class="document-preview-modal-shell">
        <FilePreview
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

.paper-library-sidebar {
  height: 100%;
}

.settings-modal-host {
  width: min(1480px, calc(100vw - 32px));
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

.paper-library-card {
  min-height: fit-content;
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: var(--color-surface);
  box-shadow: none;
}

.paper-library-card > .n-card__content {
  background: var(--color-bg);
  padding: 18px 24px 28px;
}

.library-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  margin-bottom: 34px;
  background: var(--color-bg);
  padding: 0;
}

.library-sidebar-open {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
}

.library-sidebar-open:hover {
  border-color: var(--color-primary);
  background: var(--color-accent-soft-bg);
  color: var(--color-primary);
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
  font-weight: 500;
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
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.library-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-bottom: 22px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  overflow: hidden;
}

.library-summary__item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid var(--color-border-soft);
}

.library-summary__item:last-child {
  border-right: 0;
}

.library-summary__label,
.library-summary__item > span:last-child {
  display: block;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.3;
}

.library-summary__label {
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-weight: 700;
  text-transform: uppercase;
}

.library-summary__item strong {
  display: block;
  margin: 3px 0 2px;
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 20px;
  line-height: 1.05;
}

.paper-library-card .n-data-table {
  --n-td-color: var(--color-bg) !important;
  --n-th-color: var(--color-card-band) !important;
  --n-border-color: var(--color-border) !important;
  --n-th-text-color: var(--color-primary) !important;
  --n-td-text-color: var(--color-text) !important;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  overflow: hidden;
}

.paper-library-card .n-data-table-th {
  background: var(--color-card-band-pressed);
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
  padding: 12px 12px;
}

.paper-library-card .n-data-table-td {
  background: var(--color-bg);
  vertical-align: middle;
  padding: 12px 12px;
  transition: background-color 120ms ease;
}

.paper-library-card .n-data-table-tr:hover .n-data-table-td {
  background: var(--color-surface-alt);
}

.library-file-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.library-file-cell__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 40px;
  height: 48px;
  border: 1px solid var(--color-border);
  border-radius: 7px;
  background: var(--color-card-band);
  color: var(--color-accent);
  cursor: pointer;
}

.library-file-cell__icon:hover {
  border-color: var(--color-accent);
  background: var(--color-card-band);
}

.library-file-cell__icon:disabled,
.library-file-cell__name:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.library-file-cell__icon:disabled:hover {
  border-color: var(--color-border);
}

.library-file-icon {
  font-size: 28px;
}

.library-file-cell__copy {
  min-width: 0;
}

.library-file-cell__name {
  display: inline;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  line-height: 1.45;
  text-align: left;
}

.library-file-cell__name:hover {
  color: var(--color-primary);
}

.library-file-cell__meta,
.library-date-cell span:last-child,
.library-index-line__meta {
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.45;
}

.library-file-cell__meta--stacked,
.library-scope-stack {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.library-digest-chip,
.library-scope-chip,
.library-visibility,
.library-pipeline-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 23px;
  padding: 2px 9px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-card-band);
  color: var(--color-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.library-digest-chip {
  cursor: pointer;
}

.library-digest-chip:hover {
  border-color: var(--color-accent);
  color: var(--color-accent);
}

.library-size-cell,
.library-date-cell span:first-child {
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
  font-weight: 700;
}

.library-index-cell {
  display: grid;
  gap: 6px;
  min-width: 220px;
}

.library-index-line {
  display: grid;
  gap: 3px;
}

.library-index-line__top {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 700;
}

.library-index-line__top span:nth-child(2) {
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-index-line__label {
  color: var(--color-accent);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.4px;
}

.library-index-line--processing .library-index-line__top,
.library-index-line--pending .library-index-line__top {
  color: var(--color-warning);
}

.library-index-line--failed .library-index-line__top {
  color: var(--color-error);
}

.library-index-line--empty {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.library-asset-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding-top: 2px;
}

.library-asset-pill {
  display: inline-flex;
  min-height: 20px;
  align-items: center;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-card-band);
  padding: 1px 6px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.library-asset-pill--ok {
  border-color: var(--color-success);
  color: var(--color-success);
}

.library-asset-pill--warning {
  border-color: var(--color-warning);
  color: var(--color-warning);
}

.library-asset-pill--muted {
  border-color: var(--color-border);
  color: var(--color-text-muted);
}

.library-pipeline-status {
  gap: 7px;
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
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 700;
}

.library-visibility--public {
  border-color: var(--color-success);
  background: var(--color-card-band);
  color: var(--color-success);
}

.library-visibility--private {
  border-color: var(--color-warning);
  background: var(--color-card-band);
  color: var(--color-warning);
}

.library-date-cell {
  display: grid;
  gap: 2px;
}

.library-action-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}

.library-action-group .n-button {
  border-radius: 6px;
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
  font-size: 11px;
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
  border-radius: 6px;
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
    padding: 14px;
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
</style>
