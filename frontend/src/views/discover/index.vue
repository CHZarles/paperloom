<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NButton, NButtonGroup, NInput, NSelect, NSpin, NTag } from 'naive-ui';
import { addPapersToCollection, fetchArxivSearch, fetchPaperCollections, importArxivPaper } from '@/service/api';
import AgentChat from './modules/agent-chat.vue';

defineOptions({
  name: 'DiscoverPage'
});

const PAGE_SIZE = 20;

const router = useRouter();

/** 默认进智能探索（聊天助理），关键词检索保留为副入口。 */
const mode = ref<'keyword' | 'agent'>('agent');

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
  <div class="discover-shell">
    <!-- 独立页面自己的顶栏：左边切换模式，右边只保留两个出口 -->
    <header class="discover-topbar">
      <NButtonGroup size="small" class="discover-mode-switch">
        <NButton :type="mode === 'agent' ? 'primary' : 'default'" secondary @click="mode = 'agent'">
          <template #icon>
            <icon-lucide:sparkles />
          </template>
          聊天助理
        </NButton>
        <NButton :type="mode === 'keyword' ? 'primary' : 'default'" secondary @click="mode = 'keyword'">
          关键词检索
        </NButton>
      </NButtonGroup>
      <NButton quaternary size="small" @click="router.push('/chat')">
        <template #icon>
          <icon-lucide:message-circle />
        </template>
        返回 Chat
      </NButton>
      <NButton quaternary size="small" @click="router.push('/knowledge-base')">
        <template #icon>
          <icon-lucide:library />
        </template>
        去文献库
      </NButton>
    </header>

    <!-- 进入页面先是一块舒展的居中搜索区，点击检索后才展开成工作台 -->
    <section v-if="mode === 'keyword' && !searched" class="discover-hero">
      <span class="hero-badge">
        <icon-lucide:compass class="text-24px" />
      </span>
      <h1 class="hero-title">探索 arXiv 论文</h1>
      <p class="hero-sub">
        检索元数据，点选后按 PDF 入库；入库后走和手动上传相同的解析与索引，可在文献库里对全文提问。
      </p>
      <div class="hero-search">
        <NInput
          v-model:value="keyword"
          size="large"
          placeholder="例如 retrieval augmented generation"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <icon-lucide:search class="text-icon" />
          </template>
        </NInput>
        <NButton type="primary" size="large" :loading="loading" @click="handleSearch">检索</NButton>
      </div>
      <div class="hero-options">
        <NSelect v-model:value="sort" size="small" class="sort-select" :options="sortOptions" />
        <div class="collection-picker">
          <span class="collection-label">入库到</span>
          <NSelect
            v-model:value="targetCollectionId"
            size="small"
            class="collection-select"
            :options="collectionOptions"
            :consistent-menu-width="false"
          />
        </div>
      </div>
    </section>

    <div v-else-if="mode === 'keyword'" class="discover-workbench">
      <section class="discover-toolbar discover-col">
        <NInput
          v-model:value="keyword"
          class="discover-keyword"
          placeholder="检索 arXiv，例如 retrieval augmented generation"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <icon-lucide:search class="text-icon" />
          </template>
        </NInput>
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

      <div class="discover-col status-row">
        <span>{{ statusText }}</span>
        <span v-if="importedCount > 0" class="imported-hint">本次已入库 {{ importedCount }} 篇，解析中</span>
      </div>

      <div class="discover-col discover-results">
        <NSpin :show="loading" class="results-spin">
          <div v-if="errorMessage" class="state-block">
            <p class="error-text">{{ errorMessage }}</p>
            <NButton size="small" secondary @click="handleSearch">重试</NButton>
          </div>

          <div v-else-if="items.length === 0" class="state-block">没有匹配的论文，换个检索词试试。</div>

          <ul v-if="hasResults" class="result-list">
            <li v-for="item in items" :key="item.arxivId" class="result-item">
              <div class="result-main">
                <a class="result-title" :href="item.absUrl" target="_blank" rel="noopener noreferrer">
                  {{ item.title }}
                </a>
                <p class="result-meta">
                  <span class="utility-text">arXiv:{{ item.arxivId }}</span>
                  <template v-if="item.publishedYear">· {{ item.publishedYear }}</template>
                  <template v-if="item.categories.length">· {{ item.categories.join(' / ') }}</template>
                </p>
                <p class="result-authors">{{ authorsText(item.authors) }}</p>
                <p class="result-summary-text">{{ item.summary }}</p>
              </div>

              <div class="result-side">
                <NTag v-if="item.libraryStatus !== 'NONE'" size="small" :type="STATUS_META[item.libraryStatus].tag">
                  {{ STATUS_META[item.libraryStatus].label }}
                </NTag>
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
                  <NButton
                    size="small"
                    quaternary
                    tag="a"
                    :href="item.pdfUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    PDF
                  </NButton>
                </div>
              </div>
            </li>
          </ul>
        </NSpin>
      </div>

      <footer class="discover-col pager">
        <NButton size="small" secondary :disabled="page === 0 || loading" @click="goPrev">上一页</NButton>
        <span class="pager-info">第 {{ page + 1 }} 页</span>
        <NButton size="small" secondary :disabled="!hasNext || loading" @click="goNext">下一页</NButton>
      </footer>
    </div>

    <!-- 智能探索：有记忆的单例聊天助理（发现论文/感知库内/帮入库；/clear、/沉淀、记忆面板） -->
    <AgentChat v-else />
  </div>
</template>

<style scoped lang="scss">
/* 独立全屏页（layout.blank）：整页就是产品画布，不再套一层卡片 */
.discover-shell {
  display: flex;
  width: 100%;
  height: 100vh;
  min-height: 0;
  flex-direction: column;
  padding: 0 16px 20px;
  overflow: auto;
  background: var(--color-bg);
  color: var(--color-text);
}

.discover-topbar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 4px;
}

.discover-mode-switch {
  margin-right: auto;
}

/* 阅读宽度令牌：与产品的 reading-width 对齐，栏内列表不会拉成超长行 */
.discover-col {
  width: 100%;
  max-width: var(--reading-width);
  margin: 0 auto;
}

.discover-workbench {
  display: flex;
  flex: none;
  min-height: 0;
  flex-direction: column;
  gap: 14px;
}

.discover-hero {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px 0 48px;
}

.hero-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  margin-bottom: 4px;
  border-radius: var(--radius-pill);
  background: var(--color-research-soft-bg);
  color: var(--color-research);
}

.hero-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--text-3xl);
  font-weight: 700;
  line-height: var(--leading-tight);
  text-align: center;
}

.hero-sub {
  max-width: 560px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
  text-align: center;
}

.hero-search {
  display: flex;
  width: min(760px, 100%);
  gap: 12px;
  margin-top: 8px;
}

.hero-search :deep(.n-input) {
  flex: 1;
}

.hero-options {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.discover-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.discover-keyword {
  flex: 1 1 260px;
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
  gap: 8px;
}

.collection-label {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
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
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.imported-hint {
  color: var(--color-success);
}

.discover-results {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overscroll-behavior: contain;
}

.results-spin {
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
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.error-text {
  margin: 0;
  color: var(--color-error);
}

.result-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0 0 12px;
  list-style: none;
}

.result-item {
  display: flex;
  gap: 20px;
  padding: 16px 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
}

.result-item:hover {
  border-color: var(--color-border-strong);
  box-shadow: var(--shadow-card);
}

.result-main {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 6px;
}

.result-side {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  padding-top: 2px;
}

.result-title {
  color: var(--color-research);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: var(--leading-snug);
  text-decoration: none;
}

.result-title:hover {
  color: var(--color-research-hover);
  text-decoration: underline;
}

.result-meta,
.result-authors {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-snug);
}

.result-summary-text {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
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
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

/* 桌面端：工作台撑满剩余视口，结果栏内滚动；移动端：整页滚动 */
@media (min-width: 640px) {
  .discover-shell {
    padding: 0 32px 24px;
    overflow: hidden;
  }

  .discover-topbar {
    padding: 12px 4px;
  }

  .discover-workbench {
    flex: 1 1 auto;
  }

  .discover-results {
    overflow-y: auto;
    /* 结果多少只影响内部滚动，不改变外框宽度 */
    scrollbar-gutter: stable;
    padding-right: 2px;
  }
}

@media (max-width: 720px) {
  .hero-search {
    flex-direction: column;
  }

  .discover-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .discover-keyword,
  .sort-select,
  .collection-picker,
  .collection-select {
    width: 100%;
  }

  .result-item {
    flex-direction: column;
    gap: 10px;
  }

  .result-side {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    padding-top: 0;
  }
}
</style>
