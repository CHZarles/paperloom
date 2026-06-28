<script setup lang="ts">
import {
  addPapersToCollection,
  createPaperCollection,
  deletePaperCollection,
  fetchPaperCollection,
  fetchPaperCollections,
  removePaperFromCollection,
  updatePaperCollection
} from '@/service/api';
import { request } from '@/service/request';

defineOptions({
  name: 'CollectionsPanel'
});

const PAPER_SEARCH_PAGE_SIZE = 20;
const DETAIL_PAGE_SIZE = 50;

interface CollectionFormModel {
  name: string;
  description: string;
  visibility: Api.PaperCollection.Visibility;
  orgTag: string | null;
}

const chatStore = useChatStore();
const authStore = useAuthStore();
const router = useRouter();

const collections = ref<Api.PaperCollection.Item[]>([]);
const collectionsLoading = ref(false);
const selectedCollectionId = ref<number | null>(null);
const detail = ref<Api.PaperCollection.Detail | null>(null);
const detailLoading = ref(false);

const formVisible = ref(false);
const formLoading = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formModel = ref<CollectionFormModel>(createDefaultForm());
const editingCollectionId = ref<number | null>(null);

const addVisible = ref(false);
const addQuery = ref('');
const addPage = ref(1);
const addTotal = ref(0);
const addSearchLoading = ref(false);
const addSubmitLoading = ref(false);
const addCandidates = ref<Api.Paper.UploadTask[]>([]);
const selectedPaperIds = ref<string[]>([]);
const detailPaperPage = ref(1);

let detailRequestSeq = 0;
let paperSearchRequestSeq = 0;

const selectedCollection = computed(() => {
  if (!selectedCollectionId.value) return null;
  return collections.value.find(item => item.id === selectedCollectionId.value) || null;
});

const selectedPaperIdSet = computed(() => new Set(detail.value?.paperIds || []));
const selectedAddPaperIds = computed(() =>
  selectedPaperIds.value.filter(paperId => !selectedPaperIdSet.value.has(paperId))
);
const pagedDetailPaperIds = computed(() => {
  const paperIds = detail.value?.paperIds || [];
  const start = (detailPaperPage.value - 1) * DETAIL_PAGE_SIZE;
  return paperIds.slice(start, start + DETAIL_PAGE_SIZE);
});
const formTitle = computed(() => (formMode.value === 'create' ? 'Create Collection' : 'Edit Collection'));
const canSubmitForm = computed(() => {
  if (!formModel.value.name.trim() || formLoading.value) return false;
  if (formModel.value.visibility === 'ORG') return Boolean(formModel.value.orgTag?.trim());
  return true;
});
const canAddPapers = computed(() => selectedAddPaperIds.value.length > 0 && !addSubmitLoading.value);

onMounted(async () => {
  await loadCollections();
});

function createDefaultForm(): CollectionFormModel {
  return {
    name: '',
    description: '',
    visibility: 'PRIVATE',
    orgTag: null
  };
}

function collectionDescription(collection: Api.PaperCollection.Item) {
  return collection.description?.trim() || 'No description';
}

function shortPaperId(paperId: string) {
  if (paperId.length <= 16) return paperId;
  return `${paperId.slice(0, 10)}...${paperId.slice(-6)}`;
}

function formatDateTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : 'N/A';
}

function normalizePaperPage(payload?: Api.Paper.List | null) {
  const rows = payload?.data || payload?.content || [];
  return {
    rows,
    total: payload?.totalElements ?? rows.length,
    page: payload?.number || addPage.value
  };
}

function clearDetailSelection() {
  detailRequestSeq += 1;
  selectedCollectionId.value = null;
  detail.value = null;
  detailLoading.value = false;
  detailPaperPage.value = 1;
}

function canEditCollection(collection?: Api.PaperCollection.Item | null) {
  if (!collection) return false;
  if (authStore.isAdmin) return true;
  if (collection.ownerUserId !== undefined && collection.ownerUserId !== null) {
    return String(collection.ownerUserId) === String(authStore.userInfo.id);
  }
  return collection.visibility === 'PRIVATE';
}

async function loadCollections() {
  collectionsLoading.value = true;
  const { error, data } = await fetchPaperCollections();
  collectionsLoading.value = false;

  if (error) return;

  collections.value = data || [];
  if (selectedCollectionId.value && !collections.value.some(item => item.id === selectedCollectionId.value)) {
    clearDetailSelection();
  }
  if (!selectedCollectionId.value && collections.value.length > 0) {
    await openDetail(collections.value[0]);
    return;
  }
  if (selectedCollectionId.value) {
    await loadDetail(selectedCollectionId.value);
  }
}

async function loadDetail(collectionId: number) {
  detailRequestSeq += 1;
  const requestSeq = detailRequestSeq;
  detailLoading.value = true;
  const { error, data } = await fetchPaperCollection(collectionId);

  if (requestSeq !== detailRequestSeq || selectedCollectionId.value !== collectionId) {
    return;
  }

  detailLoading.value = false;

  if (!error && data) {
    detail.value = data;
    detailPaperPage.value = 1;
  }
}

async function openDetail(collection: Api.PaperCollection.Item) {
  selectedCollectionId.value = collection.id;
  detail.value = null;
  detailPaperPage.value = 1;
  await loadDetail(collection.id);
}

function openCreateForm() {
  formMode.value = 'create';
  formModel.value = createDefaultForm();
  editingCollectionId.value = null;
  formVisible.value = true;
}

function openEditForm(collection: Api.PaperCollection.Item) {
  if (!canEditCollection(collection)) return;
  formMode.value = 'edit';
  formModel.value = {
    name: collection.name,
    description: collection.description || '',
    visibility: collection.visibility,
    orgTag: collection.orgTag || null
  };
  editingCollectionId.value = collection.id;
  formVisible.value = true;
}

function closeForm() {
  if (formLoading.value) return;
  formVisible.value = false;
}

async function submitForm() {
  if (!canSubmitForm.value) return;

  const payload: Api.PaperCollection.UpsertPayload = {
    name: formModel.value.name.trim(),
    description: formModel.value.description?.trim() || null,
    visibility: formModel.value.visibility,
    orgTag: formModel.value.visibility === 'ORG' ? formModel.value.orgTag?.trim() || null : null
  };

  formLoading.value = true;
  const requestResult =
    formMode.value === 'create'
      ? await createPaperCollection(payload)
      : await updatePaperCollection(editingCollectionId.value!, payload);
  formLoading.value = false;

  if (requestResult.error) return;

  window.$message?.success(formMode.value === 'create' ? 'Collection created' : 'Collection updated');
  formVisible.value = false;
  if (requestResult.data?.id) {
    selectedCollectionId.value = requestResult.data.id;
  }
  await loadCollections();
}

async function removeCollection(collection: Api.PaperCollection.Item) {
  if (!canEditCollection(collection)) return;

  const { error } = await deletePaperCollection(collection.id);
  if (error) return;

  window.$message?.success('Collection deleted');
  if (selectedCollectionId.value === collection.id) {
    clearDetailSelection();
  }
  await loadCollections();
}

async function startSession(collection: Api.PaperCollection.Item) {
  if (!collection.searchablePaperCount) {
    window.$message?.warning('No searchable papers in this collection');
    return;
  }

  const ok = await chatStore.createSessionFromScope({
    scopeMode: 'SOURCE_SET_SNAPSHOT',
    collectionIds: [collection.id],
    sourceLabel: collection.name,
    sourceRecipe: { type: 'collection', collectionIds: [collection.id] }
  });

  if (ok) {
    window.$message?.success('Scoped session created');
    await router.push({ name: 'chat' });
  } else {
    window.$message?.error('Failed to create scoped session');
  }
}

function openAddPapers() {
  if (!detail.value || !canEditCollection(detail.value)) return;

  addVisible.value = true;
  addQuery.value = '';
  addPage.value = 1;
  addTotal.value = 0;
  addCandidates.value = [];
  selectedPaperIds.value = [];
  searchCandidates(1);
}

function closeAddPapers() {
  if (addSubmitLoading.value) return;
  paperSearchRequestSeq += 1;
  addSearchLoading.value = false;
  addVisible.value = false;
}

async function searchCandidates(page = 1) {
  paperSearchRequestSeq += 1;
  const requestSeq = paperSearchRequestSeq;
  addPage.value = page;
  addSearchLoading.value = true;
  const query = addQuery.value.trim();
  const { error, data } = await request<Api.Paper.List>({
    url: '/papers?scope=accessible',
    params: { page, size: 20, query, readiness: 'searchable' }
  });

  if (requestSeq !== paperSearchRequestSeq || !addVisible.value) {
    return;
  }

  addSearchLoading.value = false;

  if (error) return;

  const normalized = normalizePaperPage(data);
  addCandidates.value = normalized.rows;
  addTotal.value = normalized.total;
  addPage.value = normalized.page;
}

async function submitAddPapers() {
  if (!detail.value || !canAddPapers.value) return;

  addSubmitLoading.value = true;
  const { error, data } = await addPapersToCollection(detail.value.id, selectedAddPaperIds.value);
  addSubmitLoading.value = false;

  if (error) return;

  window.$message?.success('Papers added');
  selectedPaperIds.value = [];
  if (data) detail.value = data;
  await loadCollections();
  await searchCandidates(addPage.value);
}

async function removePaper(paperId: string) {
  if (!detail.value || !canEditCollection(detail.value)) return;

  const { error } = await removePaperFromCollection(detail.value.id, paperId);
  if (error) return;

  window.$message?.success('Paper removed');
  await loadDetail(detail.value.id);
  await loadCollections();
}

function isPaperInCollection(paperId: string) {
  return selectedPaperIdSet.value.has(paperId);
}

function copyPaperId(paperId: string) {
  navigator.clipboard.writeText(paperId);
  window.$message?.success('Paper ID copied');
}

watch(
  () => formModel.value.visibility,
  visibility => {
    if (visibility === 'PRIVATE') {
      formModel.value.orgTag = null;
    }
  }
);

watch(
  () => detail.value?.paperIds.length || 0,
  paperCount => {
    const maxPage = Math.max(1, Math.ceil(paperCount / DETAIL_PAGE_SIZE));
    if (detailPaperPage.value > maxPage) {
      detailPaperPage.value = maxPage;
    }
  }
);
</script>

<template>
  <section class="collections-panel">
    <div class="collections-toolbar">
      <div class="collections-toolbar__summary">
        <strong>{{ collections.length }}</strong>
        <span>collections</span>
      </div>
      <div class="collections-toolbar__actions">
        <NButton size="small" secondary :loading="collectionsLoading" @click="loadCollections">
          <template #icon>
            <icon-lucide:refresh-cw class="text-icon" />
          </template>
          Refresh
        </NButton>
        <NButton size="small" type="primary" secondary @click="openCreateForm">
          <template #icon>
            <icon-lucide:plus class="text-icon" />
          </template>
          New Collection
        </NButton>
      </div>
    </div>

    <NSpin :show="collectionsLoading">
      <NEmpty v-if="!collections.length" description="No collections" class="collections-empty" />

      <div v-else class="collections-layout">
        <div class="collections-list">
          <article
            v-for="collection in collections"
            :key="collection.id"
            class="collection-row"
            :class="{ 'collection-row--active': selectedCollectionId === collection.id }"
          >
            <button type="button" class="collection-row__main" @click="openDetail(collection)">
              <span class="collection-row__title">{{ collection.name }}</span>
              <span class="collection-row__description">{{ collectionDescription(collection) }}</span>
              <span class="collection-row__meta">
                <span>{{ collection.paperCount }} papers</span>
                <span>{{ collection.searchablePaperCount }} searchable</span>
                <span>{{ collection.visibility }}</span>
                <span v-if="collection.orgTag">{{ collection.orgTag }}</span>
              </span>
            </button>

            <div class="collection-row__actions">
              <NButton
                size="tiny"
                type="primary"
                secondary
                :disabled="!collection.searchablePaperCount"
                @click="startSession(collection)"
              >
                <template #icon>
                  <icon-lucide:message-square-plus class="text-icon" />
                </template>
                Start
              </NButton>
              <NButton v-if="canEditCollection(collection)" size="tiny" secondary @click="openEditForm(collection)">
                <template #icon>
                  <icon-lucide:pencil class="text-icon" />
                </template>
                Edit
              </NButton>
              <NPopconfirm v-if="canEditCollection(collection)" @positive-click="removeCollection(collection)">
                <template #trigger>
                  <NButton size="tiny" type="error" secondary>
                    <template #icon>
                      <icon-lucide:trash-2 class="text-icon" />
                    </template>
                    Delete
                  </NButton>
                </template>
                Delete this collection?
              </NPopconfirm>
            </div>
          </article>
        </div>

        <aside class="collection-detail">
          <NSpin :show="detailLoading">
            <NEmpty v-if="!selectedCollection || !detail" description="Select a collection" class="collections-empty" />

            <template v-else>
              <header class="collection-detail__header">
                <div>
                  <h3>{{ detail.name }}</h3>
                  <p>{{ collectionDescription(detail) }}</p>
                </div>
                <div class="collection-detail__actions">
                  <NButton
                    v-if="canEditCollection(detail)"
                    size="small"
                    type="primary"
                    secondary
                    @click="openAddPapers"
                  >
                    <template #icon>
                      <icon-lucide:file-plus-2 class="text-icon" />
                    </template>
                    Add Papers
                  </NButton>
                  <NButton
                    size="small"
                    type="primary"
                    secondary
                    :disabled="!detail.searchablePaperCount"
                    @click="startSession(detail)"
                  >
                    <template #icon>
                      <icon-lucide:message-square-plus class="text-icon" />
                    </template>
                    Start
                  </NButton>
                </div>
              </header>

              <div class="collection-detail__stats">
                <span>{{ detail.paperCount }} papers</span>
                <span>{{ detail.searchablePaperCount }} searchable</span>
                <span>{{ detail.visibility }}</span>
                <span v-if="detail.orgTag">{{ detail.orgTag }}</span>
                <span>Updated {{ formatDateTime(detail.updatedAt) }}</span>
              </div>

              <div v-if="!detail.paperIds.length" class="collection-detail__empty">
                <NEmpty description="No papers in this collection" />
              </div>

              <div v-else class="collection-paper-list">
                <div v-for="paperId in pagedDetailPaperIds" :key="paperId" class="collection-paper-row">
                  <button type="button" class="collection-paper-row__id" :title="paperId" @click="copyPaperId(paperId)">
                    {{ shortPaperId(paperId) }}
                  </button>
                  <NPopconfirm v-if="canEditCollection(detail)" @positive-click="removePaper(paperId)">
                    <template #trigger>
                      <NButton size="tiny" type="error" secondary>
                        <template #icon>
                          <icon-lucide:x class="text-icon" />
                        </template>
                        Remove
                      </NButton>
                    </template>
                    Remove this paper?
                  </NPopconfirm>
                </div>
                <div v-if="detail.paperIds.length > DETAIL_PAGE_SIZE" class="collection-detail__pagination">
                  <span>{{ detail.paperIds.length }} papers</span>
                  <NPagination
                    v-model:page="detailPaperPage"
                    :page-size="DETAIL_PAGE_SIZE"
                    :item-count="detail.paperIds.length"
                    size="small"
                  />
                </div>
              </div>
            </template>
          </NSpin>
        </aside>
      </div>
    </NSpin>

    <NModal v-model:show="formVisible" preset="dialog" :title="formTitle" :show-icon="false" class="w-560px!">
      <NForm :model="formModel" label-placement="top" :show-feedback="false">
        <NFormItem label="Name">
          <NInput v-model:value="formModel.name" maxlength="120" show-count placeholder="Collection name" />
        </NFormItem>
        <NFormItem label="Description">
          <NInput
            v-model:value="formModel.description"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="Description"
          />
        </NFormItem>
        <NFormItem label="Visibility">
          <NRadioGroup v-model:value="formModel.visibility" name="collection-visibility">
            <NSpace :size="16">
              <NRadio value="PRIVATE">Private</NRadio>
              <NRadio value="ORG">Org</NRadio>
            </NSpace>
          </NRadioGroup>
        </NFormItem>
        <NFormItem v-if="formModel.visibility === 'ORG'" label="Org Tag">
          <OrgTagCascader v-if="authStore.isAdmin" v-model:value="formModel.orgTag" />
          <TheSelect
            v-else
            v-model:value="formModel.orgTag"
            url="/users/org-tags"
            key-field="orgTagDetails"
            label-field="name"
            value-field="tagId"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace :size="12">
          <NButton :disabled="formLoading" @click="closeForm">Cancel</NButton>
          <NButton type="primary" :loading="formLoading" :disabled="!canSubmitForm" @click="submitForm">Save</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="addVisible" preset="dialog" title="Add Papers" :show-icon="false" class="w-920px!">
      <div class="collection-add-dialog">
        <div class="collection-add-search">
          <NInput
            v-model:value="addQuery"
            clearable
            placeholder="Search title, filename, author, venue, DOI, arXiv ID"
            @keyup.enter="searchCandidates(1)"
          />
          <NButton type="primary" secondary :loading="addSearchLoading" @click="searchCandidates(1)">
            <template #icon>
              <icon-lucide:search class="text-icon" />
            </template>
            Search
          </NButton>
        </div>

        <NSpin :show="addSearchLoading">
          <NEmpty v-if="!addCandidates.length" description="No searchable papers" class="collections-empty" />

          <NCheckboxGroup v-else v-model:value="selectedPaperIds">
            <div class="collection-candidate-list">
              <label v-for="paper in addCandidates" :key="paper.paperId" class="collection-candidate-row">
                <NCheckbox :value="paper.paperId" :disabled="isPaperInCollection(paper.paperId)" />
                <span class="collection-candidate-row__body">
                  <strong>{{ paper.paperTitle || paper.originalFilename }}</strong>
                  <span>{{ paper.originalFilename }}</span>
                  <span class="collection-candidate-row__meta">
                    <span>{{ paper.authors || 'Unknown authors' }}</span>
                    <span>{{ paper.venue || 'Unknown venue' }}</span>
                    <span>{{ paper.publicationYear || 'N/A' }}</span>
                    <span>{{ shortPaperId(paper.paperId) }}</span>
                    <span v-if="isPaperInCollection(paper.paperId)">Already added</span>
                  </span>
                </span>
              </label>
            </div>
          </NCheckboxGroup>
        </NSpin>

        <div class="collection-add-pagination">
          <span>{{ selectedAddPaperIds.length }} selected</span>
          <NPagination
            v-model:page="addPage"
            :page-size="PAPER_SEARCH_PAGE_SIZE"
            :item-count="addTotal"
            size="small"
            @update:page="searchCandidates"
          />
        </div>
      </div>

      <template #action>
        <NSpace :size="12">
          <NButton :disabled="addSubmitLoading" @click="closeAddPapers">Cancel</NButton>
          <NButton type="primary" :loading="addSubmitLoading" :disabled="!canAddPapers" @click="submitAddPapers">
            Add Selected
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </section>
</template>

<style scoped lang="scss">
.collections-panel {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.collections-toolbar,
.collections-toolbar__actions,
.collection-row__actions,
.collection-detail__actions,
.collection-detail__stats,
.collection-add-search,
.collection-add-pagination,
.collection-detail__pagination {
  display: flex;
  align-items: center;
  gap: 10px;
}

.collections-toolbar {
  justify-content: space-between;
  min-width: 0;
}

.collections-toolbar__summary {
  display: flex;
  align-items: baseline;
  gap: 7px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.collections-toolbar__summary strong {
  color: var(--color-text);
  font-size: 20px;
  line-height: 1;
}

.collections-layout {
  display: grid;
  grid-template-columns: minmax(360px, 0.95fr) minmax(420px, 1.25fr);
  gap: 14px;
  min-width: 0;
}

.collections-list,
.collection-detail {
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-bg);
}

.collections-list {
  display: grid;
  align-content: start;
  overflow: hidden;
}

.collection-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-width: 0;
  border-bottom: 1px solid var(--color-border-soft);
  padding: 12px;
}

.collection-row:last-child {
  border-bottom: 0;
}

.collection-row--active {
  background: var(--color-surface-alt);
}

.collection-row__main {
  display: grid;
  gap: 5px;
  min-width: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.collection-row__title,
.collection-candidate-row strong,
.collection-detail h3 {
  overflow: hidden;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 800;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-row__description,
.collection-detail p,
.collection-candidate-row__body > span,
.collection-add-pagination {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-row__meta,
.collection-candidate-row__meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}

.collection-row__meta span,
.collection-detail__stats span,
.collection-candidate-row__meta span {
  display: inline-flex;
  align-items: center;
  min-height: 21px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-card-band);
  padding: 1px 7px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

.collection-detail {
  min-height: 360px;
  padding: 14px;
}

.collection-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.collection-detail h3 {
  margin: 0;
  font-size: 17px;
}

.collection-detail p {
  max-width: 560px;
  margin: 5px 0 0;
  white-space: normal;
}

.collection-detail__stats {
  flex-wrap: wrap;
  margin-top: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border-soft);
}

.collection-detail__empty {
  padding: 72px 0;
}

.collection-paper-list,
.collection-candidate-list {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.collection-paper-row,
.collection-candidate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  border: 1px solid var(--color-border-soft);
  border-radius: 7px;
  background: var(--color-card-band);
  padding: 8px 10px;
}

.collection-paper-row__id {
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 800;
  overflow: hidden;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-add-dialog {
  display: grid;
  gap: 12px;
}

.collection-add-search {
  align-items: stretch;
}

.collection-candidate-list {
  max-height: min(52vh, 480px);
  overflow: auto;
  padding-right: 4px;
}

.collection-candidate-row {
  justify-content: flex-start;
  cursor: pointer;
}

.collection-candidate-row__body {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.collection-add-pagination {
  justify-content: space-between;
  min-height: 32px;
}

.collection-detail__pagination {
  justify-content: space-between;
  padding-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.collections-empty {
  padding: 72px 0;
}

@media (max-width: 1100px) {
  .collections-layout {
    grid-template-columns: 1fr;
  }

  .collection-detail {
    min-height: 280px;
  }
}

@media (max-width: 720px) {
  .collections-toolbar,
  .collection-row,
  .collection-detail__header,
  .collection-add-pagination {
    align-items: stretch;
    flex-direction: column;
  }

  .collection-row {
    grid-template-columns: 1fr;
  }

  .collection-row__actions,
  .collection-detail__actions,
  .collections-toolbar__actions,
  .collection-add-search {
    flex-wrap: wrap;
  }
}
</style>
