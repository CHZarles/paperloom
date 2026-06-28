<script setup lang="ts">
import { fetchPaperCollections } from '@/service/api';
import { request } from '@/service/request';

defineOptions({
  name: 'SessionScopePicker'
});

const props = withDefaults(
  defineProps<{
    show?: boolean;
    conversationId?: string;
    scope?: Api.Chat.ConversationScope | null;
  }>(),
  {
    show: false,
    conversationId: '',
    scope: null
  }
);

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  (e: 'updated'): void;
}>();

type PickerMode = 'AUTO_LIBRARY' | 'COLLECTION' | 'CUSTOM';

const PAPER_SEARCH_PAGE_SIZE = 20;
const chatStore = useChatStore();

const visible = computed({
  get: () => props.show,
  set: value => emit('update:show', value)
});

const mode = ref<PickerMode>('AUTO_LIBRARY');
const submitting = ref(false);
const collectionsLoading = ref(false);
const collections = ref<Api.PaperCollection.Item[]>([]);
const selectedCollectionId = ref<number | null>(null);

const paperQuery = ref('');
const paperPage = ref(1);
const paperTotal = ref(0);
const papersLoading = ref(false);
const paperCandidates = ref<Api.Paper.UploadTask[]>([]);
const selectedPaperIds = ref<string[]>([]);
let paperSearchSeq = 0;

const selectedCollection = computed(
  () => collections.value.find(item => item.id === selectedCollectionId.value) || null
);

const canSubmit = computed(() => {
  if (submitting.value) return false;
  if (mode.value === 'COLLECTION') return Boolean(selectedCollection.value?.searchablePaperCount);
  if (mode.value === 'CUSTOM') return selectedPaperIds.value.length > 0;
  return true;
});

watch(
  () => props.show,
  value => {
    if (!value) return;
    resetPicker();
    loadCollections();
    searchPapers(1);
  }
);

function resetPicker() {
  const currentScope = props.scope;
  const sourceRecipe = currentScope?.sourceRecipe || null;
  const collectionIds = Array.isArray(sourceRecipe?.collectionIds) ? sourceRecipe.collectionIds : [];
  const firstCollectionId = Number(collectionIds[0]);

  if (currentScope?.scopeMode !== 'SOURCE_SET_SNAPSHOT') {
    mode.value = 'AUTO_LIBRARY';
  } else if (Number.isFinite(firstCollectionId)) {
    mode.value = 'COLLECTION';
  } else {
    mode.value = 'CUSTOM';
  }
  selectedCollectionId.value = Number.isFinite(firstCollectionId) ? firstCollectionId : null;
  paperQuery.value = '';
  paperPage.value = 1;
  paperTotal.value = 0;
  paperCandidates.value = [];
  selectedPaperIds.value = currentScope?.scopeMode === 'SOURCE_SET_SNAPSHOT' ? [...(currentScope.paperIds || [])] : [];
}

function close() {
  if (submitting.value) return;
  paperSearchSeq += 1;
  visible.value = false;
}

function normalizePaperPage(payload?: Api.Paper.List | null) {
  const rows = payload?.data || payload?.content || [];
  return {
    rows: rows.map(paper => ({
      ...paper,
      paperTitle: paper.paperTitle || paper.originalFilename,
      originalFilename: paper.originalFilename || paper.paperTitle
    })),
    total: payload?.totalElements ?? rows.length,
    page: payload?.number || paperPage.value
  };
}

async function loadCollections() {
  collectionsLoading.value = true;
  const { error, data } = await fetchPaperCollections();
  collectionsLoading.value = false;
  if (error) return;
  collections.value = data || [];
}

async function searchPapers(page = 1) {
  paperSearchSeq += 1;
  const requestSeq = paperSearchSeq;
  paperPage.value = page;
  papersLoading.value = true;
  const query = paperQuery.value.trim();
  const { error, data } = await request<Api.Paper.List>({
    url: '/papers?scope=accessible',
    params: { page, size: 20, query, readiness: 'searchable' }
  });

  if (requestSeq !== paperSearchSeq || !visible.value) return;

  papersLoading.value = false;
  if (error) {
    paperCandidates.value = [];
    paperTotal.value = 0;
    return;
  }

  const normalized = normalizePaperPage(data);
  paperCandidates.value = normalized.rows;
  paperTotal.value = normalized.total;
  paperPage.value = normalized.page;
}

async function applyScope() {
  if (!props.conversationId || !canSubmit.value) return;

  let payload: Api.Chat.UpdateConversationScopePayload;
  if (mode.value === 'COLLECTION') {
    const collection = selectedCollection.value;
    if (!collection) return;
    payload = {
      scopeMode: 'SOURCE_SET_SNAPSHOT',
      collectionIds: [collection.id],
      sourceLabel: collection.name,
      sourceRecipe: { type: 'collection', collectionIds: [collection.id] }
    };
  } else if (mode.value === 'CUSTOM') {
    const unchangedExistingSnapshot =
      props.scope?.scopeMode === 'SOURCE_SET_SNAPSHOT' &&
      sameStringSet(selectedPaperIds.value, props.scope.paperIds || []);
    payload = {
      scopeMode: 'SOURCE_SET_SNAPSHOT',
      paperIds: [...selectedPaperIds.value],
      sourceLabel:
        unchangedExistingSnapshot && props.scope?.sourceLabel
          ? props.scope.sourceLabel
          : `${selectedPaperIds.value.length} selected papers`,
      sourceRecipe: { type: 'manual_paper_search', query: paperQuery.value.trim() }
    };
  } else {
    payload = { scopeMode: 'AUTO_LIBRARY' };
  }

  submitting.value = true;
  const ok = await chatStore.updateConversationScope(props.conversationId, payload);
  submitting.value = false;
  if (!ok) return;

  await chatStore.loadSessions();
  emit('updated');
  visible.value = false;
}

function sameStringSet(left: string[], right: string[]) {
  if (left.length !== right.length) return false;
  const rightSet = new Set(right);
  return left.every(item => rightSet.has(item));
}
</script>

<template>
  <NModal v-model:show="visible" preset="dialog" title="Session Scope" :show-icon="false" class="w-900px!">
    <div class="session-scope-picker">
      <NSegmented
        v-model:value="mode"
        :options="[
          { label: 'All', value: 'AUTO_LIBRARY' },
          { label: 'Collection', value: 'COLLECTION' },
          { label: 'Custom', value: 'CUSTOM' }
        ]"
      />

      <section v-if="mode === 'AUTO_LIBRARY'" class="scope-picker-pane">
        <div class="scope-picker-state">
          <icon-lucide:library />
          <strong>All searchable papers</strong>
          <span>AUTO_LIBRARY</span>
        </div>
      </section>

      <section v-else-if="mode === 'COLLECTION'" class="scope-picker-pane">
        <NSpin :show="collectionsLoading">
          <NEmpty v-if="!collections.length" description="No collections" class="scope-picker-empty" />
          <NRadioGroup v-else v-model:value="selectedCollectionId">
            <div class="scope-picker-list">
              <label v-for="collection in collections" :key="collection.id" class="scope-picker-row">
                <NRadio :value="collection.id" :disabled="!collection.searchablePaperCount" />
                <span class="scope-picker-row__body">
                  <strong>{{ collection.name }}</strong>
                  <span>{{ collection.description || 'No description' }}</span>
                  <span class="scope-picker-row__meta">
                    <span>{{ collection.paperCount }} papers</span>
                    <span>{{ collection.searchablePaperCount }} searchable</span>
                    <span>{{ collection.visibility }}</span>
                  </span>
                </span>
              </label>
            </div>
          </NRadioGroup>
        </NSpin>
      </section>

      <section v-else class="scope-picker-pane">
        <div class="scope-picker-search">
          <NInput
            v-model:value="paperQuery"
            clearable
            placeholder="Search searchable papers"
            @keyup.enter="searchPapers(1)"
          />
          <NButton secondary type="primary" :loading="papersLoading" @click="searchPapers(1)">
            <template #icon>
              <icon-lucide:search />
            </template>
            Search
          </NButton>
        </div>

        <NSpin :show="papersLoading">
          <NEmpty v-if="!paperCandidates.length" description="No searchable papers" class="scope-picker-empty" />
          <NCheckboxGroup v-else v-model:value="selectedPaperIds">
            <div class="scope-picker-list">
              <label v-for="paper in paperCandidates" :key="paper.paperId" class="scope-picker-row">
                <NCheckbox :value="paper.paperId" />
                <span class="scope-picker-row__body">
                  <strong>{{ paper.paperTitle || paper.originalFilename }}</strong>
                  <span>{{ paper.originalFilename }}</span>
                  <span class="scope-picker-row__meta">
                    <span>{{ paper.authors || 'Unknown authors' }}</span>
                    <span>{{ paper.venue || 'Unknown venue' }}</span>
                    <span>{{ paper.publicationYear || 'N/A' }}</span>
                  </span>
                </span>
              </label>
            </div>
          </NCheckboxGroup>
        </NSpin>

        <div class="scope-picker-pagination">
          <span>{{ selectedPaperIds.length }} selected</span>
          <NPagination
            v-model:page="paperPage"
            :page-size="PAPER_SEARCH_PAGE_SIZE"
            :item-count="paperTotal"
            size="small"
            @update:page="searchPapers"
          />
        </div>
      </section>
    </div>

    <template #action>
      <NSpace :size="12">
        <NButton :disabled="submitting" @click="close">Cancel</NButton>
        <NButton type="primary" :loading="submitting" :disabled="!canSubmit" @click="applyScope">Apply</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.session-scope-picker {
  display: grid;
  gap: 14px;
}

.scope-picker-pane {
  min-height: 260px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
  padding: 12px;
}

.scope-picker-state {
  display: grid;
  min-height: 220px;
  place-items: center;
  align-content: center;
  gap: 7px;
  color: var(--color-text-muted);
}

.scope-picker-state svg {
  color: var(--color-primary);
  font-size: 28px;
}

.scope-picker-state strong {
  color: var(--color-text);
  font-size: 15px;
}

.scope-picker-search,
.scope-picker-pagination {
  display: flex;
  align-items: center;
  gap: 10px;
}

.scope-picker-pagination {
  justify-content: space-between;
  margin-top: 12px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.scope-picker-list {
  display: grid;
  max-height: min(52vh, 440px);
  gap: 8px;
  overflow: auto;
}

.scope-picker-row {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  min-width: 0;
  border: 1px solid var(--color-border-soft);
  border-radius: 7px;
  background: var(--color-card-band);
  padding: 9px 10px;
  cursor: pointer;
}

.scope-picker-row__body {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.scope-picker-row__body strong,
.scope-picker-row__body > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-picker-row__body strong {
  color: var(--color-text);
  font-size: 13px;
}

.scope-picker-row__body > span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.scope-picker-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.scope-picker-row__meta span {
  min-height: 20px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  padding: 1px 7px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 700;
}

.scope-picker-empty {
  padding: 72px 0;
}
</style>
