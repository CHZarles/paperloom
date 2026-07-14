<script setup lang="ts">
import type { SelectOption } from 'naive-ui';
import { fetchPaperCollections } from '@/service/api';

defineOptions({
  name: 'SessionScopePicker'
});

const props = withDefaults(
  defineProps<{
    conversationId?: string;
    scope?: Api.Chat.ConversationScope | null;
    disabled?: boolean;
  }>(),
  {
    conversationId: '',
    scope: null,
    disabled: false
  }
);

const emit = defineEmits<{
  (e: 'updated'): void;
  (e: 'update:busy', value: boolean): void;
}>();

const chatStore = useChatStore();
const paperSetsLoading = ref(false);
const scopeUpdating = ref(false);
const paperSets = ref<Api.PaperCollection.Item[]>([]);

const selectedValue = computed(() => {
  if (props.scope?.scopeMode !== 'SOURCE_SET_SNAPSHOT') return 'all';
  const collectionIds = props.scope.sourceRecipe?.collectionIds;
  const paperSetId = Array.isArray(collectionIds) ? Number(collectionIds[0]) : Number.NaN;
  return Number.isFinite(paperSetId) ? `paper-set:${paperSetId}` : 'current-snapshot';
});

const allPapersLabel = computed(() => {
  const count = props.scope?.scopeMode === 'AUTO_LIBRARY' ? props.scope.sourcePaperCount : null;
  return typeof count === 'number' ? `All readable papers · ${count}` : 'All readable papers';
});

const options = computed<SelectOption[]>(() => {
  const currentSnapshot =
    selectedValue.value === 'current-snapshot'
      ? [
          {
            label: props.scope?.sourceLabel || 'Selected papers',
            value: 'current-snapshot',
            disabled: true
          }
        ]
      : [];

  return [
    ...currentSnapshot,
    {
      label: allPapersLabel.value,
      value: 'all'
    },
    ...paperSets.value.map(paperSet => ({
      label: `${paperSet.name} · ${paperSet.searchablePaperCount} searchable`,
      value: `paper-set:${paperSet.id}`,
      disabled: !paperSet.searchablePaperCount
    }))
  ];
});

onMounted(loadPaperSets);

async function loadPaperSets() {
  paperSetsLoading.value = true;
  const { error, data } = await fetchPaperCollections();
  paperSetsLoading.value = false;
  if (error) return;
  paperSets.value = data || [];
}

function scopePayload(value: string): Api.Chat.UpdateConversationScopePayload | null {
  if (value === 'all') return { scopeMode: 'AUTO_LIBRARY' };

  const paperSetId = Number(value.replace('paper-set:', ''));
  const paperSet = paperSets.value.find(item => item.id === paperSetId);
  if (!paperSet?.searchablePaperCount) return null;

  return {
    scopeMode: 'SOURCE_SET_SNAPSHOT',
    collectionIds: [paperSet.id],
    sourceLabel: paperSet.name,
    sourceRecipe: { type: 'collection', collectionIds: [paperSet.id] }
  };
}

async function updateScope(value: string | number | null) {
  if (typeof value !== 'string') return;
  if (props.disabled || scopeUpdating.value || value === selectedValue.value) return;
  const payload = scopePayload(value);
  if (!payload) return;

  scopeUpdating.value = true;
  emit('update:busy', true);
  let ok = false;
  try {
    ok = props.conversationId
      ? await chatStore.updateConversationScope(props.conversationId, payload)
      : await chatStore.createSessionFromScope(payload);
  } finally {
    scopeUpdating.value = false;
    emit('update:busy', false);
  }
  if (!ok) return;

  if (props.conversationId) {
    await chatStore.loadSessionIndex({ silent: true });
  }
  emit('updated');
}
</script>

<template>
  <div class="session-scope-picker">
    <span class="session-scope-picker__label">Sources</span>
    <NSelect
      :value="selectedValue"
      :options="options"
      :loading="paperSetsLoading || scopeUpdating"
      :disabled="disabled"
      class="session-scope-picker__select"
      size="small"
      @update:value="updateScope"
    />
  </div>
</template>

<style scoped>
.session-scope-picker {
  display: flex;
  width: min(360px, 100%);
  align-items: center;
  gap: 10px;
}

.session-scope-picker__label {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 700;
}

.session-scope-picker__select {
  min-width: 0;
  flex: 1 1 auto;
}
</style>
