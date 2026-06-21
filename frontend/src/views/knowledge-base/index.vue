<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NUpload } from 'naive-ui';
import type { FlatResponseData } from '@sa/axios';
import { uploadAccept } from '@/constants/common';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';

const authStore = useAuthStore();

// 文件预览相关状态
const previewVisible = ref(false);
const previewPaperTitle = ref('');
const previewPaperId = ref('');

function mapUploadStatusToTaskStatus(uploadStatus?: Api.Paper.UploadTask['uploadStatus']) {
  if (uploadStatus === 'COMPLETED' || uploadStatus === 1) return UploadStatus.Completed;
  if (uploadStatus === 'MERGING' || uploadStatus === 2) return UploadStatus.Uploading;
  if (uploadStatus === 'UPLOADING' || uploadStatus === 0) return UploadStatus.Uploading;
  return UploadStatus.Break;
}

function normalizeRemotePaper(row: Api.Paper.UploadTask): Api.Paper.UploadTask {
  return {
    ...row,
    paperTitle: row.paperTitle || row.originalFilename,
    originalFilename: row.originalFilename || row.paperTitle,
    uploadedChunks: row.uploadedChunks || [],
    progress: row.progress ?? (row.uploadStatus === 'COMPLETED' ? 100 : 0),
    status: row.status ?? mapUploadStatusToTaskStatus(row.uploadStatus),
    chunk: row.chunk ?? null
  };
}

async function apiFn(params: Api.Common.CommonSearchParams = {}): Promise<FlatResponseData<Api.Paper.List>> {
  const response = await request<Api.Paper.UploadTask[] | Api.Paper.List>({
    url: '/papers?scope=accessible',
    params
  });
  if (response.error) return response as FlatResponseData<Api.Paper.List>;

  const payload = response.data;
  if (!Array.isArray(payload)) {
    if (payload?.data) {
      payload.data = payload.data.map(normalizeRemotePaper);
      payload.content = payload.content.map(normalizeRemotePaper);
    }
    return response as FlatResponseData<Api.Paper.List>;
  }

  const page = params.page && params.page > 0 ? params.page : 1;
  const size = params.size && params.size > 0 ? params.size : 10;
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
            onClick={() => handleFilePreview(row.originalFilename, row.paperId)}
          >
            {renderIcon(row.originalFilename)}
          </button>
          <div class="library-file-cell__copy">
            <NEllipsis lineClamp={2} tooltip>
              <button
                type="button"
                class="library-file-cell__name"
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
      render: row => <span class="library-size-cell">{fileSize(row.totalSize)}</span>
    },
    {
      key: 'estimatedEmbeddingTokens',
      title: 'Index Budget / 索引',
      width: 230,
      render: row => renderIndexUsage(row)
    },
    {
      key: 'status',
      title: 'Pipeline / 状态',
      width: 112,
      render: row => renderStatus(row.status, row.progress)
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
      width: 170,
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
              icon: () => <SvgIcon icon="mdi:file-eye-outline" class="text-14px" />,
              default: () => '预览'
            }}
          </NButton>
          {canManageFile(row) ? (
            <NPopconfirm onPositiveClick={() => handleDelete(row.paperId)}>
              {{
                default: () => '确认删除当前文件吗？',
                trigger: () => (
                  <NButton type="error" secondary size="small">
                    {{
                      icon: () => <SvgIcon icon="mdi:trash-can-outline" class="text-14px" />,
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
  const localRows = tasks.value.filter(
    task =>
      task.file && task.status !== UploadStatus.Completed && !remoteRows.some(item => item.paperId === task.paperId)
  );

  return [...localRows, ...remoteRows];
});

const libraryStats = computed(() => {
  const rows = tableTasks.value;
  const completed = rows.filter(item => item.status === UploadStatus.Completed).length;
  const indexing = rows.filter(item => isVectorizationProcessing(item)).length;
  const privateRows = rows.filter(item => !item.isPublic).length;
  const estimatedTokens = rows.reduce((sum, item) => sum + Number(item.estimatedEmbeddingTokens || 0), 0);

  return [
    {
      label: 'Documents',
      value: formatNumber(mobilePagination.value.itemCount || rows.length),
      detail: 'accessible'
    },
    {
      label: 'Ready',
      value: formatNumber(completed),
      detail: 'uploaded'
    },
    {
      label: 'Indexing',
      value: formatNumber(indexing),
      detail: 'vector jobs'
    },
    {
      label: 'Private',
      value: formatNumber(privateRows),
      detail: 'restricted'
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

function syncTaskFromServer(target: Api.Paper.UploadTask, source: Api.Paper.UploadTask) {
  Object.assign(target, {
    originalFilename: source.originalFilename,
    paperTitle: source.paperTitle,
    totalSize: source.totalSize,
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
    processingErrorMessage: source.processingErrorMessage
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

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/papers/${paperId}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// 渲染上传状态
function renderStatus(status: UploadStatus, percentage: number) {
  if (status === UploadStatus.Completed) {
    return (
      <span class="library-pipeline-status library-pipeline-status--ready">
        <span class="library-pipeline-status__dot"></span>
        Ready
      </span>
    );
  }

  if (status === UploadStatus.Break) {
    return (
      <span class="library-pipeline-status library-pipeline-status--broken">
        <span class="library-pipeline-status__dot"></span>
        Interrupted
      </span>
    );
  }

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
    </div>
  );
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
  return row.processingStatus === 'PENDING' || row.processingStatus === 'PROCESSING';
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
        <NEllipsis tooltip lineClamp={2} style="color: #8c4034">
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
            icon: () => <SvgIcon icon="mdi:upload-outline" class="text-14px" />,
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
            icon: () => <SvgIcon icon="mdi:upload-outline" class="text-14px" />,
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
  const md5 = await calculateMD5(options.file.file!);
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
  <div class="paper-library-page min-h-500px flex-col-stretch gap-16px overflow-auto">
    <NCard title="Paper Library / 文献库" :bordered="false" size="small" class="paper-library-card card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getList">
          <template #prefix>
            <NButton size="small" secondary type="primary" @click="handleSearch">
              <template #icon>
                <icon-mdi-file-search-outline class="text-icon" />
              </template>
              检索文献库
            </NButton>
          </template>
          <template #default>
            <NButton size="small" secondary type="primary" @click="handleUpload">
              <template #icon>
                <icon-mdi-upload-outline class="text-icon" />
              </template>
              上传文献
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>

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
        :scroll-x="1182"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="mobilePagination"
        class="library-table"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />

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

.paper-library-page {
  padding: 16px 20px;
  overflow-x: hidden;
}

.paper-library-card {
  overflow: hidden;
  border: 1px solid #c9c1b2;
  border-radius: 10px;
  background: #fbfaf6;
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.42);
}

.paper-library-card > .n-card-header {
  border-bottom: 1px solid #c9c1b2;
  background: #e2dccc;
  padding: 14px 20px;
}

.paper-library-card .n-card-header__main {
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.paper-library-card .n-card-header__extra {
  min-width: 0;
}

.paper-library-card .n-card__content {
  background: #fbfaf6;
  padding: 16px 20px;
}

.library-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-bottom: 14px;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: linear-gradient(180deg, #e2dccc, #fbfaf6);
  overflow: hidden;
}

.library-summary__item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid rgba(201, 193, 178, 0.72);
}

.library-summary__item:last-child {
  border-right: 0;
}

.library-summary__label,
.library-summary__item > span:last-child {
  display: block;
  color: #5e6470;
  font-size: 11px;
  line-height: 1.3;
}

.library-summary__label {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-weight: 700;
  text-transform: uppercase;
}

.library-summary__item strong {
  display: block;
  margin: 4px 0 3px;
  color: #20242a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  line-height: 1.05;
}

.paper-library-card .n-data-table {
  --n-td-color: #fbfaf6 !important;
  --n-th-color: #e2dccc !important;
  --n-border-color: #c9c1b2 !important;
  --n-th-text-color: #26364a !important;
  --n-td-text-color: #20242a !important;
}

.paper-library-card .n-data-table-th {
  background: #eeeae1;
  color: #394150;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  padding: 12px 12px;
}

.paper-library-card .n-data-table-td {
  background: #fbfaf6;
  vertical-align: middle;
  padding: 12px 12px;
  transition: background-color 120ms ease;
}

.paper-library-card .n-data-table-tr:hover .n-data-table-td {
  background: #f1ebd9;
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
  border: 1px solid #c9c1b2;
  border-radius: 7px;
  background: #e2dccc;
  color: #7e3f46;
  cursor: pointer;
}

.library-file-cell__icon:hover {
  border-color: #7e3f46;
  background: #e2dccc;
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
  color: #20242a;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
  line-height: 1.45;
  text-align: left;
}

.library-file-cell__name:hover {
  color: #26364a;
}

.library-file-cell__meta,
.library-date-cell span:last-child,
.library-index-line__meta {
  color: #747a84;
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
  border: 1px solid #c9c1b2;
  border-radius: 999px;
  background: #e2dccc;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.library-digest-chip {
  cursor: pointer;
}

.library-digest-chip:hover {
  border-color: #7e3f46;
  color: #7e3f46;
}

.library-size-cell,
.library-date-cell span:first-child {
  color: #20242a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
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
  color: #20242a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
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
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.4px;
}

.library-index-line--processing .library-index-line__top,
.library-index-line--pending .library-index-line__top {
  color: #8a5e22;
}

.library-index-line--failed .library-index-line__top {
  color: #9f4c3f;
}

.library-index-line--empty {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #747a84;
  font-size: 11px;
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
  border-color: rgba(79, 125, 90, 0.35);
  background: rgba(79, 125, 90, 0.11);
  color: #3f6b4a;
}

.library-pipeline-status--ready .library-pipeline-status__dot {
  background: #4f7d5a;
}

.library-pipeline-status--broken {
  border-color: rgba(159, 76, 63, 0.35);
  background: rgba(159, 76, 63, 0.1);
  color: #8c4034;
}

.library-pipeline-status--broken .library-pipeline-status__dot {
  background: #9f4c3f;
}

.library-progress-cell {
  display: grid;
  grid-template-columns: minmax(60px, 1fr) 38px;
  align-items: center;
  gap: 8px;
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
}

.library-visibility--public {
  border-color: rgba(79, 125, 90, 0.35);
  background: rgba(79, 125, 90, 0.11);
  color: #3f6b4a;
}

.library-visibility--private {
  border-color: rgba(155, 107, 46, 0.35);
  background: rgba(155, 107, 46, 0.1);
  color: #9a6428;
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
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
  box-shadow: 10px 10px 0 rgba(201, 193, 178, 0.6);
}

@media (max-width: 1180px) {
  .library-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .library-summary__item {
    border-bottom: 1px solid rgba(201, 193, 178, 0.72);
  }

  .library-summary__item:nth-child(3n) {
    border-right: 0;
  }
}

@media (max-width: 640px) {
  .paper-library-card > .n-card-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    padding: 18px 24px;
  }

  .paper-library-card .n-card-header__main,
  .paper-library-card .n-card-header__extra {
    width: 100%;
  }

  .paper-library-card .n-card-header__main {
    font-size: 26px;
    line-height: 1.22;
  }

  .paper-library-card .n-card-header__extra .n-space {
    width: 100% !important;
    justify-content: flex-start !important;
  }

  .paper-library-card .n-card__content {
    padding: 12px;
  }

  .library-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .library-summary__item:nth-child(3n) {
    border-right: 1px solid rgba(201, 193, 178, 0.72);
  }

  .library-summary__item:nth-child(2n) {
    border-right: 0;
  }
}
</style>
