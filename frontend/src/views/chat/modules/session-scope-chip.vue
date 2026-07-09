<script setup lang="ts">
defineOptions({
  name: 'SessionScopeChip'
});

const props = withDefaults(
  defineProps<{
    scope?: Api.Chat.ConversationScope | null;
    editable?: boolean;
    compact?: boolean;
  }>(),
  {
    scope: null,
    editable: false,
    compact: false
  }
);

const emit = defineEmits<{
  (e: 'edit'): void;
}>();

const scopeMode = computed(() => props.scope?.scopeMode || 'AUTO_LIBRARY');
const isSnapshot = computed(() => scopeMode.value === 'SOURCE_SET_SNAPSHOT');
const isLocked = computed(() => Boolean(props.scope?.scopeLocked));
const scopeStatus = computed(() => props.scope?.scopeStatus || 'READY');
const paperCount = computed(() => {
  if (typeof props.scope?.sourcePaperCount === 'number') return props.scope.sourcePaperCount;
  if (isSnapshot.value) return props.scope?.paperIds?.length ?? null;
  return null;
});

const label = computed(() => {
  if (scopeStatus.value === 'INVALID') return 'Invalid scope';
  if (!isSnapshot.value) return props.scope?.sourceLabel || 'All readable papers';
  const count = paperCount.value;
  return props.scope?.sourceLabel || (typeof count === 'number' ? `${count.toLocaleString()} papers` : 'Selected papers');
});

const statusClass = computed(() => {
  if (scopeStatus.value === 'INVALID') return 'session-scope-chip--invalid';
  if (scopeStatus.value === 'DEGRADED') return 'session-scope-chip--degraded';
  return 'session-scope-chip--ready';
});

const statusLabel = computed(() => {
  if (scopeStatus.value === 'INVALID') return 'Invalid scope';
  if (scopeStatus.value === 'DEGRADED') return 'Degraded';
  return isLocked.value ? 'Locked' : 'Unlocked';
});

const countLabel = computed(() => {
  if (typeof paperCount.value !== 'number') return '';
  return `${paperCount.value.toLocaleString()} papers`;
});

const showMeta = computed(() => {
  return true;
});
</script>

<template>
  <div class="session-scope-chip" :class="[statusClass, compact ? 'session-scope-chip--compact' : '']">
    <icon-lucide:lock v-if="isLocked" class="session-scope-chip__icon" />
    <icon-lucide:unlock v-else class="session-scope-chip__icon" />
    <div class="session-scope-chip__copy">
      <span class="session-scope-chip__label">{{ label }}</span>
      <span v-if="showMeta" class="session-scope-chip__meta">
        <span>{{ statusLabel }}</span>
        <span v-if="countLabel">{{ countLabel }}</span>
      </span>
    </div>
    <button v-if="editable && !isLocked" type="button" class="session-scope-chip__edit" @click="emit('edit')">
      <icon-lucide:sliders-horizontal />
    </button>
  </div>
</template>

<style scoped>
.session-scope-chip {
  display: inline-flex;
  max-width: 100%;
  min-height: 34px;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: 7px;
  background: var(--color-surface);
  padding: 5px 8px;
  color: var(--color-text);
}

.session-scope-chip--compact {
  min-height: 26px;
  padding: 3px 7px;
}

.session-scope-chip__icon {
  flex: 0 0 auto;
  color: var(--color-primary);
  font-size: 15px;
}

.session-scope-chip__copy {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.session-scope-chip__label {
  overflow: hidden;
  max-width: 260px;
  font-size: 12px;
  font-weight: 750;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-scope-chip__meta {
  display: flex;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: 10px;
  line-height: 1.2;
}

.session-scope-chip__edit {
  display: inline-flex;
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.session-scope-chip__edit:hover {
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.session-scope-chip--degraded {
  border-color: var(--color-warning);
}

.session-scope-chip--degraded .session-scope-chip__icon {
  color: var(--color-warning);
}

.session-scope-chip--invalid {
  border-color: var(--color-error);
}

.session-scope-chip--invalid .session-scope-chip__icon {
  color: var(--color-error);
}
</style>
