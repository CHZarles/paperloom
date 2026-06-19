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
      <icon-mdi-account-filter-outline />
      <span>User Filter</span>
    </div>

    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" class="user-filter-form">
      <NFormItem label="关键词" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="用户 / UID" clearable size="small" class="user-filter-input">
          <template #prefix>
            <icon-mdi-magnify />
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
          <icon-mdi-filter-remove-outline />
        </template>
        重置
      </NButton>
      <NButton size="small" type="primary" secondary @click="search">
        <template #icon>
          <icon-mdi-magnify />
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
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfaf6, #ebe6da);
  box-shadow: 3px 3px 0 rgba(201, 193, 178, 0.38);
}

.user-filter-strip__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  color: #26364a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
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
  color: #7c6b55;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.user-filter-form :deep(.n-input),
.user-filter-form :deep(.n-base-selection) {
  --n-border: 1px solid #c9c1b2 !important;
  --n-border-hover: 1px solid #7e3f46 !important;
  --n-border-focus: 1px solid #7e3f46 !important;
  --n-color: #e2dccc !important;
  --n-color-focus: #fbfaf6 !important;
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
