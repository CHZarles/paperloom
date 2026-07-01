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

type PickerMode = 'AUTO_LIBRARY' | 'COLLECTION' | 'TITLE_MATCH';

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

const titleMatchText = ref('');
const titleMatchRegexMode = ref(false);
const titleMatchPreview = ref<Api.Chat.TitleMatchScopePreview | null>(null);
const titleMatchPreviewLoading = ref(false);
let titleMatchPreviewSeq = 0;

const selectedCollection = computed(
  () => collections.value.find(item => item.id === selectedCollectionId.value) || null
);

const canSubmit = computed(() => {
  if (submitting.value) return false;
  if (mode.value === 'COLLECTION') return Boolean(selectedCollection.value?.searchablePaperCount);
  if (mode.value === 'TITLE_MATCH') {
    return Boolean(titleMatchText.value.trim() && titleMatchPreview.value?.paperCount);
  }
  return true;
});

watch(
  () => props.show,
  value => {
    if (!value) return;
    resetPicker();
    loadCollections();
  }
);

watch([titleMatchText, titleMatchRegexMode], () => {
  titleMatchPreviewSeq += 1;
  titleMatchPreview.value = null;
});

function collectionIdsFromRecipe(sourceRecipe: Record<string, any> | null | undefined) {
  return Array.isArray(sourceRecipe?.collectionIds) ? sourceRecipe.collectionIds : [];
}

function recipeString(sourceRecipe: Record<string, any> | null | undefined, key: string) {
  const value = sourceRecipe?.[key];
  return typeof value === 'string' ? value : '';
}

function pickerModeForScope(
  currentScope: Api.Chat.ConversationScope | null | undefined,
  firstCollectionId: number,
  recipeType: string
): PickerMode {
  if (currentScope?.scopeMode !== 'SOURCE_SET_SNAPSHOT') return 'AUTO_LIBRARY';
  if (Number.isFinite(firstCollectionId)) return 'COLLECTION';
  if (recipeType === 'title_match') return 'TITLE_MATCH';
  return 'AUTO_LIBRARY';
}

function previewFromCurrentScope(
  currentScope: Api.Chat.ConversationScope | null | undefined,
  sourceRecipe: Record<string, any> | null | undefined,
  recipeType: string
): Api.Chat.TitleMatchScopePreview | null {
  if (recipeType !== 'title_match' || currentScope?.scopeMode !== 'SOURCE_SET_SNAPSHOT') return null;
  return {
    paperCount: Number(currentScope.sourcePaperCount || currentScope.paperIds?.length || 0),
    paperIds: [...(currentScope.paperIds || [])],
    papers: [],
    sourceLabel: currentScope.sourceLabel || '',
    sourceRecipe: sourceRecipe || {}
  };
}

function resetPicker() {
  const currentScope = props.scope;
  const sourceRecipe = currentScope?.sourceRecipe || null;
  const collectionIds = collectionIdsFromRecipe(sourceRecipe);
  const firstCollectionId = Number(collectionIds[0]);
  const recipeType = recipeString(sourceRecipe, 'type');
  const titleRegex = recipeString(sourceRecipe, 'titleRegex');
  const titleQuery = recipeString(sourceRecipe, 'titleQuery');

  mode.value = pickerModeForScope(currentScope, firstCollectionId, recipeType);
  selectedCollectionId.value = Number.isFinite(firstCollectionId) ? firstCollectionId : null;
  titleMatchRegexMode.value = Boolean(titleRegex);
  titleMatchText.value = titleRegex || titleQuery || '';
  titleMatchPreviewLoading.value = false;
  titleMatchPreview.value = previewFromCurrentScope(currentScope, sourceRecipe, recipeType);
}

function close() {
  if (submitting.value) return;
  titleMatchPreviewSeq += 1;
  visible.value = false;
}

async function loadCollections() {
  collectionsLoading.value = true;
  const { error, data } = await fetchPaperCollections();
  collectionsLoading.value = false;
  if (error) return;
  collections.value = data || [];
}

function titleMatchPayload(): Api.Chat.UpdateConversationScopePayload | null {
  const matcher = titleMatchText.value.trim();
  if (!matcher) return null;
  return {
    scopeMode: 'SOURCE_SET_SNAPSHOT',
    titleQuery: titleMatchRegexMode.value ? undefined : matcher,
    titleRegex: titleMatchRegexMode.value ? matcher : undefined
  };
}

async function previewTitleMatchScope() {
  if (!props.conversationId) return;
  const payload = titleMatchPayload();
  if (!payload) return;

  titleMatchPreviewSeq += 1;
  const requestSeq = titleMatchPreviewSeq;
  titleMatchPreviewLoading.value = true;
  const { error, data } = await request<Api.Chat.TitleMatchScopePreview>({
    url: `users/conversations/${props.conversationId}/scope/title-match-preview`,
    method: 'POST',
    data: payload
  });

  if (requestSeq !== titleMatchPreviewSeq || !visible.value) return;

  titleMatchPreviewLoading.value = false;
  titleMatchPreview.value = !error && data ? data : null;
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
  } else if (mode.value === 'TITLE_MATCH') {
    const basePayload = titleMatchPayload();
    if (!basePayload || !titleMatchPreview.value?.paperCount) return;
    payload = {
      ...basePayload,
      sourceLabel: titleMatchPreview.value.sourceLabel,
      sourceRecipe: titleMatchPreview.value.sourceRecipe
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
</script>

<template>
  <NModal v-model:show="visible" preset="dialog" title="Session Scope" :show-icon="false" class="w-900px!">
    <div class="session-scope-picker">
      <NRadioGroup v-model:value="mode" name="session-scope-mode" size="small" class="scope-picker-mode">
        <NRadioButton value="AUTO_LIBRARY">All</NRadioButton>
        <NRadioButton value="COLLECTION">Collection</NRadioButton>
        <NRadioButton value="TITLE_MATCH">Title Match</NRadioButton>
      </NRadioGroup>

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
            v-model:value="titleMatchText"
            clearable
            placeholder="Match paper title or filename"
            @keyup.enter="previewTitleMatchScope"
          />
          <NCheckbox v-model:checked="titleMatchRegexMode" class="scope-title-regex">Regex</NCheckbox>
          <NButton
            secondary
            type="primary"
            :loading="titleMatchPreviewLoading"
            :disabled="!titleMatchText.trim()"
            @click="previewTitleMatchScope"
          >
            <template #icon>
              <icon-lucide:search />
            </template>
            Preview
          </NButton>
        </div>

        <NSpin :show="titleMatchPreviewLoading">
          <div v-if="titleMatchPreview" class="scope-title-preview">
            <div class="scope-title-preview__summary">
              <strong>{{ titleMatchPreview.paperCount.toLocaleString() }} papers</strong>
              <span>{{ titleMatchPreview.sourceLabel }}</span>
            </div>
            <NEmpty v-if="!titleMatchPreview.papers.length" description="No matches" class="scope-picker-empty" />
            <div class="scope-picker-list">
              <div v-for="paper in titleMatchPreview.papers" :key="paper.paperId" class="scope-picker-row">
                <icon-lucide:file-text class="scope-picker-row__icon" />
                <span class="scope-picker-row__body">
                  <strong>{{ paper.paperTitle || paper.originalFilename }}</strong>
                  <span>{{ paper.originalFilename }}</span>
                  <span class="scope-picker-row__meta">
                    <span>{{ paper.authors || 'Unknown authors' }}</span>
                    <span>{{ paper.venue || 'Unknown venue' }}</span>
                    <span>{{ paper.publicationYear || 'N/A' }}</span>
                  </span>
                </span>
              </div>
            </div>
          </div>
          <NEmpty v-else description="No preview" class="scope-picker-empty" />
        </NSpin>
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

.scope-picker-mode {
  width: fit-content;
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

.scope-picker-row__icon {
  flex: 0 0 auto;
  margin-top: 1px;
  color: var(--color-primary);
  font-size: 15px;
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

.scope-title-regex {
  flex: 0 0 auto;
}

.scope-title-preview {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.scope-title-preview__summary {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.scope-title-preview__summary strong {
  flex: 0 0 auto;
  color: var(--color-text);
}

.scope-title-preview__summary span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
