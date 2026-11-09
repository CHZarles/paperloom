<script setup lang="tsx">
import { computed, ref, watch } from 'vue';
import { NButton, NInput, NInputNumber, NModal, NSwitch, NTag } from 'naive-ui';
import { fetchModelProviders, saveModelProvider } from '@/service/api';

type Scope = 'llm' | 'embedding';

const scope = ref<Scope>('llm');
const loading = ref(false);
const overview = ref<Api.Admin.ModelProviderOverview | null>(null);
const providers = computed(() => overview.value?.providers || []);

const modalVisible = ref(false);
const saving = ref(false);
const editingProvider = ref<string | null>(null);
const newProviderCode = ref('');

type FormModel = {
  displayName: string;
  apiBaseUrl: string;
  modelName: string;
  apiKey: string;
  enabled: boolean;
  active: boolean;
  dimension: number | null;
};

const model = ref<FormModel>(createDefaultModel());

function createDefaultModel(): FormModel {
  return {
    displayName: '',
    apiBaseUrl: '',
    modelName: '',
    apiKey: '',
    enabled: true,
    active: false,
    dimension: null
  };
}

async function load() {
  try {
    loading.value = true;
    const res = await fetchModelProviders(scope.value);
    if (!res.error) {
      overview.value = res.data;
    }
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editingProvider.value = null;
  newProviderCode.value = '';
  model.value = createDefaultModel();
  modalVisible.value = true;
}

function openEdit(row: Api.Admin.ModelProviderItem) {
  editingProvider.value = row.provider;
  newProviderCode.value = row.provider;
  model.value = {
    displayName: row.displayName,
    apiBaseUrl: row.apiBaseUrl,
    modelName: row.model,
    apiKey: '',
    enabled: row.enabled,
    active: row.active,
    dimension: row.dimension
  };
  modalVisible.value = true;
}

async function handleSave() {
  const providerCode = newProviderCode.value.trim().toLowerCase();
  if (!editingProvider.value && !/^[a-z0-9][a-z0-9-]{0,63}$/.test(providerCode)) {
    window.$message?.warning('Provider 标识只能包含小写字母、数字和中划线');
    return;
  }
  if (!model.value.apiBaseUrl.trim() || !model.value.modelName.trim()) {
    window.$message?.warning('Base URL 和模型名不能为空');
    return;
  }
  if (scope.value === 'embedding' && (model.value.dimension === null || model.value.dimension <= 0)) {
    window.$message?.warning('Embedding provider 必须配置正数维度');
    return;
  }
  if (model.value.active && !model.value.enabled) {
    window.$message?.warning('激活的 provider 必须同时启用');
    return;
  }

  try {
    saving.value = true;
    const res = await saveModelProvider(scope.value, providerCode, {
      displayName: model.value.displayName.trim() || null,
      apiStyle: null,
      apiBaseUrl: model.value.apiBaseUrl.trim(),
      modelName: model.value.modelName.trim(),
      apiKey: model.value.apiKey.trim() || null,
      enabled: model.value.enabled,
      active: model.value.active,
      dimension: scope.value === 'embedding' ? model.value.dimension : null
    });
    if (!res.error) {
      window.$message?.success('Provider 配置已保存');
      overview.value = res.data;
      modalVisible.value = false;
    }
  } finally {
    saving.value = false;
  }
}

watch(scope, () => {
  overview.value = null;
  load();
});

const columns = computed(() => [
  {
    key: 'provider',
    title: 'Provider',
    width: 130,
    render: (row: Api.Admin.ModelProviderItem) => (
      <div class="provider-id-cell">
        <span>{row.displayName || row.provider}</span>
        <small>{row.provider}</small>
      </div>
    )
  },
  {
    key: 'model',
    title: 'Model / 模型',
    minWidth: 140,
    render: (row: Api.Admin.ModelProviderItem) => (
      <div class="provider-id-cell">
        <span>{row.model}</span>
        {row.dimension ? <small>dim {row.dimension}</small> : null}
      </div>
    )
  },
  {
    key: 'apiBaseUrl',
    title: 'Base URL',
    minWidth: 220,
    render: (row: Api.Admin.ModelProviderItem) => <span class="provider-url">{row.apiBaseUrl}</span>
  },
  {
    key: 'hasApiKey',
    title: 'API Key',
    width: 100,
    render: (row: Api.Admin.ModelProviderItem) =>
      row.hasApiKey ? (
        <NTag type="success" size="small">
          已配置
        </NTag>
      ) : (
        <NTag type="warning" size="small">
          未配置
        </NTag>
      )
  },
  {
    key: 'status',
    title: 'Status / 状态',
    width: 130,
    render: (row: Api.Admin.ModelProviderItem) => (
      <div class="provider-tags">
        {row.active ? (
          <NTag type="primary" size="small">
            Active
          </NTag>
        ) : null}
        <NTag size="small" type={row.enabled ? 'default' : 'error'}>
          {row.enabled ? 'Enabled' : 'Disabled'}
        </NTag>
      </div>
    )
  },
  {
    key: 'operate',
    title: 'Actions / 操作',
    width: 90,
    render: (row: Api.Admin.ModelProviderItem) => (
      <NButton size="small" type="primary" secondary onClick={() => openEdit(row)}>
        编辑
      </NButton>
    )
  }
]);

load();
</script>

<template>
  <div class="model-provider-page">
    <NCard title="AI Providers / 模型配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="provider-toolbar">
          <NButton size="small" type="primary" secondary @click="openCreate">
            <template #icon>
              <icon-lucide:plus />
            </template>
            新增 Provider
          </NButton>
          <NButton size="small" secondary :loading="loading" @click="load">
            <template #icon>
              <icon-lucide:refresh-cw />
            </template>
            刷新
          </NButton>
        </div>
      </template>

      <NTabs v-model:value="scope" type="segment" size="small" class="provider-scope-tabs">
        <NTab name="llm" tab="LLM（研究 / 对话）" />
        <NTab name="embedding" tab="Embedding（向量化）" />
      </NTabs>

      <NDataTable
        size="small"
        :columns="columns"
        :data="providers"
        :loading="loading"
        :row-key="row => row.provider"
        :pagination="false"
      />
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="dialog"
      :title="editingProvider ? `编辑 ${editingProvider}` : '新增 Provider'"
      :show-icon="false"
      :mask-closable="false"
      class="w-560px!"
    >
      <NForm label-placement="left" :label-width="110" mt-10>
        <NFormItem label="Provider 标识">
          <NInput v-model:value="newProviderCode" :disabled="!!editingProvider" placeholder="如 openai、deepseek" />
        </NFormItem>
        <NFormItem label="显示名">
          <NInput v-model:value="model.displayName" placeholder="留空使用 Provider 标识" />
        </NFormItem>
        <NFormItem label="Base URL">
          <NInput v-model:value="model.apiBaseUrl" placeholder="https://api.example.com/v1" />
        </NFormItem>
        <NFormItem label="模型名">
          <NInput v-model:value="model.modelName" placeholder="如 MiniMax-M3" />
        </NFormItem>
        <NFormItem v-if="scope === 'embedding'" label="维度">
          <NInputNumber v-model:value="model.dimension" :min="1" :precision="0" class="w-full" placeholder="向量维度" />
        </NFormItem>
        <NFormItem label="API Key">
          <NInput
            v-model:value="model.apiKey"
            type="password"
            show-password-on="click"
            placeholder="留空保持现有 Key 不变"
          />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch v-model:value="model.enabled" />
        </NFormItem>
        <NFormItem label="设为当前使用">
          <NSwitch v-model:value="model.active" />
        </NFormItem>
      </NForm>
      <template #action>
        <div class="provider-modal-actions">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="handleSave">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style lang="scss" scoped>
.provider-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.provider-scope-tabs {
  margin-bottom: 12px;
}

.provider-id-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.provider-id-cell small {
  color: var(--color-text-muted);
  font-size: 11px;
}

.provider-url {
  font-size: 12px;
  word-break: break-all;
}

.provider-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.provider-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
