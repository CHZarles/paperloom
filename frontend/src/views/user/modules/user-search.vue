<script setup lang="ts">
import { enableStatusOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
  reset: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });

watchEffect(() => {
  search();
});
async function search() {
  emit('search');
}

function reset() {
  emit('reset');
}
</script>

<template>
  <div class="user-filter-strip">
    <div class="user-filter-strip__label">
      <icon-lucide:list-filter />
      <span>User Filter</span>
    </div>

    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" class="user-filter-form">
      <NFormItem label="关键词" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="用户 / UID" clearable size="small" class="user-filter-input">
          <template #prefix>
            <icon-lucide:search />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" clearable size="small" class="user-filter-control" />
      </NFormItem>
      <NFormItem label="启用状态" path="status">
        <NSelect
          v-model:value="model.status"
          placeholder="请选择启用状态"
          :options="enableStatusOptions"
          clearable
          size="small"
          class="user-filter-control"
        />
      </NFormItem>
    </NForm>

    <div class="user-filter-strip__actions">
      <NButton size="small" secondary @click="reset">
        <template #icon>
          <icon-lucide:filter-x />
        </template>
        重置
      </NButton>
      <NButton size="small" type="primary" secondary @click="search">
        <template #icon>
          <icon-lucide:search />
        </template>
        搜索
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.user-filter-strip {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 920px;
  min-height: 42px;
  padding: 6px 10px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: linear-gradient(180deg, var(--color-surface), var(--color-border-soft));
  box-shadow: var(--shadow-card-soft);
}

.user-filter-strip__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  color: var(--color-primary);
  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  white-space: nowrap;
}

.user-filter-strip__label svg {
  font-size: 15px;
}

.user-filter-form {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px 10px;
  flex: 1 1 auto;
  min-width: 0;
}

.user-filter-form :deep(.n-form-item) {
  min-width: 0;
  margin-right: 0;
}

.user-filter-form :deep(.n-form-item-label) {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.user-filter-form :deep(.n-input),
.user-filter-form :deep(.n-base-selection) {
  --n-border: 1px solid var(--color-border) !important;
  --n-border-hover: 1px solid var(--color-accent) !important;
  --n-border-focus: 1px solid var(--color-accent) !important;
  --n-color: var(--color-card-band) !important;
  --n-color-focus: var(--color-surface) !important;
  border-radius: 6px;
}

.user-filter-input {
  width: 150px;
}

.user-filter-control {
  width: 160px;
}

.user-filter-strip__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.user-filter-strip__actions :deep(.n-button) {
  border-radius: 6px;
}
</style>
