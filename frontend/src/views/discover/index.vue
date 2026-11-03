<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NButton, NInput, NSelect, NSpin, NTag } from 'naive-ui';
import { addPapersToCollection, fetchArxivSearch, fetchPaperCollections, importArxivPaper } from '@/service/api';

defineOptions({
  name: 'DiscoverPage'
});

const PAGE_SIZE = 20;

const router = useRouter();

const keyword = ref('');
const sort = ref<'relevance' | 'submitted'>('relevance');
const loading = ref(false);
const importing = ref<string | null>(null);
const items = ref<Api.Discovery.ArxivItem[]>([]);
const errorMessage = ref('');
const searched = ref(false);
const importedCount = ref(0);
const page = ref(0);
const total = ref(-1);
const hasNext = ref(false);

/** 0 表示不入集合：集合 ID 从 1 开始，用 0 比 null 更好处理下拉选项。 */
const NO_COLLECTION = 0;

const collections = ref<Api.PaperCollection.Item[]>([]);
const targetCollectionId = ref<number>(NO_COLLECTION);

const collectionOptions = computed(() => [
  { label: '不入集合', value: NO_COLLECTION },
  ...collections.value.map(item => ({ label: item.name, value: item.id }))
]);

const targetCollectionName = computed(
  () => collections.value.find(item => item.id === targetCollectionId.value)?.name ?? ''
);

const sortOptions = [
  { label: '相关度', value: 'relevance' as const },
  { label: '最新提交', value: 'submitted' as const }
];

async function loadCollections() {
  const { data, error } = await fetchPaperCollections();
  if (error || !data) {
    window.$message?.warning('论文集合没加载出来，入库时只能进文献库');
    return;
  }
  collections.value = data;
  if (targetCollectionId.value !== NO_COLLECTION && !data.some(item => item.id === targetCollectionId.value)) {
    targetCollectionId.value = NO_COLLECTION;
  }
}

onMounted(loadCollections);

const hasResults = computed(() => searched.value && items.value.length > 0);
const totalLabel = computed(() => (total.value < 0 ? '' : `约 ${total.value.toLocaleString()} 条`));

const statusText = computed(() => {
  if (loading.value) return '正在检索…';
  if (!searched.value) return '输入检索词开始检索，例如 speculative decoding';
  if (errorMessage.value) return '检索失败，可以重试';
  if (items.value.length === 0) return '没有匹配的论文';
  return `第 ${page.value + 1} 页${totalLabel.value ? ` · ${totalLabel.value}` : ''}`;
});

async function runSearch(targetPage: number) {
  const query = keyword.value.trim();
  if (!query) {
    window.$message?.warning('先输入检索词');
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  const { data, error } = await fetchArxivSearch({
    query,
    start: targetPage * PAGE_SIZE,
    max: PAGE_SIZE,
    sort: sort.value
  });
  loading.value = false;
  searched.value = true;
  if (error) {
    errorMessage.value = error.message || 'arXiv 检索失败';
    items.value = [];
    total.value = -1;
    hasNext.value = false;
    return;
  }
  items.value = data?.items ?? [];
  total.value = data?.total ?? -1;
  hasNext.value = data?.hasNext ?? false;
  page.value = targetPage;
}

function handleSearch() {
  runSearch(0);
}

function goPrev() {
  if (page.value === 0 || loading.value) return;
  runSearch(page.value - 1);
}

function goNext() {
  if (!hasNext.value || loading.value) return;
  runSearch(page.value + 1);
}

async function handleImport(item: Api.Discovery.ArxivItem) {
  importing.value = item.arxivId;
  const { data, error } = await importArxivPaper(item.arxivId);
  importing.value = null;

  if (error) {
    // 全局请求拦截已经提示过一次错误，这里只保留页面内的可见状态
    return;
  }
  if (!data) {
    return;
  }
  const nextStatus = data.status === 'READY' ? 'READY' : 'PROCESSING';
  items.value.forEach(candidate => {
    if (candidate.arxivId === item.arxivId) {
      candidate.libraryStatus = nextStatus;
    }
  });
  if (data.status === 'IMPORTED') {
    importedCount.value += 1;
  }

  const collectionName = targetCollectionName.value;
  let addedToCollection = false;
  if (targetCollectionId.value !== NO_COLLECTION && data.paperId) {
    const { error: addError } = await addPapersToCollection(targetCollectionId.value, [data.paperId]);
    if (addError) {
      window.$message?.warning(`已入库，但没能加入「${collectionName}」，可以去文献库手动添加`);
      return;
    }
    addedToCollection = true;
  }

  const messageByStatus: Record<Api.Discovery.ArxivImportResult['status'], string> = {
    IMPORTED: '已加入文献库，正在后台解析',
    PROCESSING: '这篇论文正在解析中',
    READY: '这篇论文已在文献库'
  };
  const suffix = addedToCollection ? `，已加入「${collectionName}」` : '';
  window.$message?.success((messageByStatus[data.status] || data.message) + suffix);
}

const STATUS_META: Record<
  Api.Discovery.ArxivItem['libraryStatus'],
  { label: string; tag: 'default' | 'success' | 'warning' | 'error'; action: string }
> = {
  NONE: { label: '', tag: 'default', action: '入库' },
  READY: { label: '已入库', tag: 'success', action: '已入库' },
  PROCESSING: { label: '解析中', tag: 'warning', action: '解析中' },
  FAILED: { label: '需重新入库', tag: 'error', action: '重新入库' }
};

function authorsText(authors: string[]) {
  if (!authors.length) return '作者信息缺失';
  const head = authors.slice(0, 6).join(', ');
  return authors.length > 6 ? `${head} 等` : head;
}
</script>

<template>
  <div class="discover-page">
    <header class="discover-header">
      <div>
        <h1>探索论文</h1>
        <p>检索 arXiv 元数据，点选后按 PDF 入库。入库后走和手动上传相同的解析与索引，可在文献库里对全文提问。</p>
      </div>
      <NButton quaternary size="small" @click="router.push('/knowledge-base')">去文献库</NButton>
    </header>

    <section class="search-bar">
      <NInput
        v-model:value="keyword"
        placeholder="检索 arXiv，例如 retrieval augmented generation"
        clearable
        @keyup.enter="handleSearch"
      />
      <NSelect v-model:value="sort" class="sort-select" :options="sortOptions" />
      <div class="collection-picker">
        <span class="collection-label">入库到</span>
        <NSelect
          v-model:value="targetCollectionId"
          class="collection-select"
          :options="collectionOptions"
          :consistent-menu-width="false"
        />
      </div>
      <NButton type="primary" :loading="loading" @click="handleSearch">检索</NButton>
    </section>

    <div class="status-row">
      <span>{{ statusText }}</span>
      <span v-if="importedCount > 0" class="imported-hint">本次已入库 {{ importedCount }} 篇，解析中</span>
    </div>

    <section class="results-panel">
      <NSpin :show="loading" class="panel-spin">
        <div class="state-block" :class="{ 'state-block--hidden': hasResults }">
          <template v-if="errorMessage">
            <p class="error-text">{{ errorMessage }}</p>
            <NButton size="small" @click="handleSearch">重试</NButton>
          </template>
          <p v-else-if="!searched">输入检索词，看看 arXiv 上有什么。</p>
          <p v-else-if="items.length === 0">没有匹配的论文，换个检索词试试。</p>
        </div>

        <ul v-if="hasResults" class="result-list">
          <li v-for="item in items" :key="item.arxivId" class="result-item">
            <div class="result-head">
              <a class="result-title" :href="item.absUrl" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
              <NTag v-if="item.libraryStatus !== 'NONE'" size="small" :type="STATUS_META[item.libraryStatus].tag">
                {{ STATUS_META[item.libraryStatus].label }}
              </NTag>
            </div>

            <p class="result-meta">
              arXiv:{{ item.arxivId }}
              <template v-if="item.publishedYear">· {{ item.publishedYear }}</template>
              <template v-if="item.categories.length">· {{ item.categories.join(' / ') }}</template>
            </p>
            <p class="result-authors">{{ authorsText(item.authors) }}</p>
            <p class="result-summary-text">{{ item.summary }}</p>

            <div class="result-actions">
              <NButton
                size="small"
                type="primary"
                :disabled="item.libraryStatus === 'READY' || item.libraryStatus === 'PROCESSING'"
                :loading="importing === item.arxivId"
                @click="handleImport(item)"
              >
                {{ STATUS_META[item.libraryStatus].action }}
              </NButton>
              <NButton size="small" quaternary tag="a" :href="item.pdfUrl" target="_blank" rel="noopener noreferrer">
                PDF
              </NButton>
            </div>
          </li>
        </ul>
      </NSpin>
    </section>

    <footer class="pager">
      <NButton size="small" :disabled="page === 0 || loading" @click="goPrev">上一页</NButton>
      <span class="pager-info">第 {{ page + 1 }} 页</span>
      <NButton size="small" :disabled="!hasNext || loading" @click="goNext">下一页</NButton>
    </footer>
  </div>
</template>

<style scoped>
.discover-page {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
  box-sizing: border-box;
  max-width: 1040px;
  margin: 0 auto;
  padding: 20px 20px 16px;
  overflow: hidden;
  color: var(--color-text);
}

.discover-header {
  display: flex;
  flex: none;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.discover-header h1 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.discover-header p {
  margin: 0;
  max-width: 680px;
  color: var(--color-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.6;
}

.search-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}

.search-bar :deep(.n-input) {
  min-width: 0;
}

.sort-select {
  width: 132px;
  flex: none;
}

.collection-picker {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
}

.collection-label {
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
  white-space: nowrap;
}

.collection-select {
  width: 168px;
}

.status-row {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  min-height: 18px;
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}

.imported-hint {
  color: var(--color-success, #18a058);
}

.results-panel {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  /* 结果多少只影响内部滚动，不改变外框宽度 */
  scrollbar-gutter: stable;
  padding-right: 4px;
}

.panel-spin {
  display: block;
  min-height: 100%;
}

.state-block {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 0;
  color: var(--color-text-secondary, #6b7280);
  font-size: 13px;
}

.state-block--hidden {
  display: none;
}

.error-text {
  margin: 0;
  color: var(--color-error, #d03050);
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0 0 8px;
  list-style: none;
}

.result-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: 10px;
  background: var(--color-bg-container, #ffffff);
}

.result-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.result-title {
  color: var(--color-text);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
  text-decoration: none;
}

.result-title:hover {
  text-decoration: underline;
}

.result-meta,
.result-authors {
  margin: 0;
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}

.result-summary-text {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--color-text-secondary, #4b5563);
  font-size: 13px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.result-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.pager {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 32px;
}

.pager-info {
  color: var(--color-text-secondary, #6b7280);
  font-size: 12px;
}
</style>
