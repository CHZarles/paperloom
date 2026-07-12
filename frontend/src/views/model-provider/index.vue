<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty } from 'naive-ui';

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const embeddingProviders = ref<Api.Admin.ModelProviderScopeSettings | null>(null);

function cloneProviderItem(item: Api.Admin.ModelProviderItem): Api.Admin.ModelProviderItem {
  return {
    provider: item.provider,
    displayName: item.displayName,
    apiStyle: item.apiStyle,
    apiBaseUrl: item.apiBaseUrl,
    model: item.model,
    dimension: item.dimension ?? null,
    enabled: Boolean(item.enabled),
    active: Boolean(item.active),
    hasApiKey: Boolean(item.hasApiKey),
    maskedApiKey: item.maskedApiKey || '',
    apiKeyInput: ''
  };
}

function cloneModelProviderScope(payload: Api.Admin.ModelProviderScopeSettings): Api.Admin.ModelProviderScopeSettings {
  return {
    scope: payload.scope,
    activeProvider: payload.activeProvider,
    providers: (payload.providers || []).map(cloneProviderItem)
  };
}

async function getModelProviders() {
  modelProvidersLoading.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderSettings>({
    url: '/admin/model-providers'
  });

  if (!error && data) {
    embeddingProviders.value = cloneModelProviderScope(data.embedding);
  }
  modelProvidersLoading.value = false;
}

function buildProviderPayload(scope: Api.Admin.ModelProviderScopeSettings) {
  return {
    activeProvider: scope.activeProvider,
    providers: scope.providers.map(item => ({
      provider: item.provider,
      apiBaseUrl: item.apiBaseUrl,
      model: item.model,
      apiKey: item.apiKeyInput?.trim() || '',
      dimension: scope.scope === 'embedding' ? item.dimension : null,
      enabled: item.enabled
    }))
  };
}

async function submitModelProviders() {
  const scope = embeddingProviders.value;
  if (!scope) {
    return;
  }

  modelProvidersSaving.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderScopeSettings>({
    url: '/admin/model-providers/embedding',
    method: 'put',
    data: buildProviderPayload(scope)
  });

  if (!error && data) {
    embeddingProviders.value = cloneModelProviderScope(data);
    window.$message?.success('Embedding 配置已更新');
  }
  modelProvidersSaving.value = false;
}

async function testModelProvider(provider: Api.Admin.ModelProviderItem) {
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: '/admin/model-providers/embedding/test',
    method: 'post',
    data: {
      provider: provider.provider,
      apiBaseUrl: provider.apiBaseUrl,
      model: provider.model,
      apiKey: provider.apiKeyInput?.trim() || '',
      dimension: provider.dimension
    }
  });

  if (!error && data) {
    if (data.success) {
      window.$message?.success(`${provider.displayName} 连接成功，耗时 ${data.latencyMs}ms`);
    } else {
      window.$message?.error(`${provider.displayName} 连接失败：${data.message}`);
    }
  }
}

onMounted(() => {
  getModelProviders();
});
</script>

<template>
  <div class="admin-console-page flex-col-stretch gap-16px overflow-auto">
    <NCard :bordered="false" size="small" class="admin-console-card model-provider-card card-wrapper">
      <template #header>Embedding Model / 向量模型</template>
      <template #header-extra>
        <div class="flex items-center gap-2">
          <span class="text-xs">切换模型或维度可能需要重新向量化现有论文</span>
        </div>
      </template>

      <NSpin :show="modelProvidersLoading">
        <div class="admin-console-note model-provider-note mb-4">
          这里仅管理论文检索使用的 Embedding 配置。研究 Harness 的 LLM 由部署环境管理，不在产品界面中切换。 API Key
          输入为空时保留现有密钥；需要重新嵌入的危险变更会被后端拦截。
        </div>

        <div v-if="embeddingProviders" class="grid gap-4">
          <div class="provider-scope">
            <div class="provider-scope-header">
              <div>
                <div class="provider-scope-title">Embedding Provider</div>
                <div class="provider-scope-sub">
                  当前版本只支持配置管理；切 active provider 若需要重嵌入会被后端拦截
                </div>
              </div>
              <div class="flex items-center gap-3">
                <NSelect
                  v-model:value="embeddingProviders.activeProvider"
                  :options="
                    embeddingProviders.providers.map(item => ({
                      label: item.displayName,
                      value: item.provider,
                      disabled: !item.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton type="primary" size="small" :loading="modelProvidersSaving" @click="submitModelProviders">
                  保存 Embedding 配置
                </NButton>
              </div>
            </div>

            <div class="provider-grid">
              <div
                v-for="item in embeddingProviders.providers"
                :key="`embedding-${item.provider}`"
                class="provider-card"
              >
                <div class="provider-card-header">
                  <div>
                    <div class="provider-name">{{ item.displayName }}</div>
                    <div class="provider-code">{{ item.provider }} · {{ item.apiStyle }}</div>
                  </div>
                  <NSwitch v-model:value="item.enabled" size="small" />
                </div>
                <div class="limit-grid">
                  <div>
                    <div class="limit-label">API 地址</div>
                    <NInput v-model:value="item.apiBaseUrl" />
                  </div>
                  <div>
                    <div class="limit-label">模型</div>
                    <NInput v-model:value="item.model" />
                  </div>
                  <div>
                    <div class="limit-label">维度</div>
                    <NInputNumber v-model:value="item.dimension" :min="1" class="w-full" />
                  </div>
                  <div>
                    <div class="limit-label">现有密钥</div>
                    <div class="provider-mask">{{ item.hasApiKey ? item.maskedApiKey : '未配置' }}</div>
                  </div>
                  <div class="sm:col-span-2">
                    <div class="limit-label">新 API Key</div>
                    <NInput
                      v-model:value="item.apiKeyInput"
                      type="password"
                      show-password-on="click"
                      placeholder="留空则保留现有值"
                    />
                  </div>
                </div>
                <div class="mt-3 flex justify-end">
                  <NButton size="small" secondary @click="testModelProvider(item)">测试连接</NButton>
                </div>
              </div>
            </div>
          </div>
        </div>
        <NEmpty v-else size="small" description="暂未加载到模型配置" />
      </NSpin>
    </NCard>
  </div>
</template>

<style scoped lang="scss">
// 1. Outer card: align with knowledge-base .paper-library-card
.model-provider-card {
  border-radius: 10px !important;
  box-shadow: var(--shadow-card) !important;
}

.model-provider-card ::v-deep(.n-card-header) {
  border-bottom: 1px solid var(--color-border);
  background: var(--color-card-band);
  padding: 14px 20px;
}

.model-provider-card ::v-deep(.n-card-header__main) {
  color: var(--color-primary);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.model-provider-card ::v-deep(.n-card-header__extra) {
  color: var(--color-text-muted);
}

.model-provider-card ::v-deep(.n-card__content) {
  background: var(--color-surface);
  padding: 16px 20px;
}

// 2. Note: dashed paper sticker
.model-provider-note {
  background: var(--color-surface) !important;
  border: 1px dashed var(--color-border) !important;
  border-radius: 8px;
  font-size: 13px;
}

.provider-scope {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: linear-gradient(180deg, var(--color-card-band), var(--color-surface));
  box-shadow: var(--shadow-card-soft);
  padding: 20px;
}

.provider-scope-header {
  @apply mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between;
}

.provider-scope-title {
  color: var(--color-primary);
  font-size: 14px;
  font-weight: 700;
}

.provider-scope-sub {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.provider-grid {
  @apply grid gap-4 xl:grid-cols-2;
}

.provider-card {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 16px;
}

.provider-card-header {
  @apply mb-4 flex items-start justify-between gap-3;
}

.provider-name {
  color: var(--color-text);
  font-size: 14px;
  font-weight: 700;
}

.provider-code {
  margin-top: 4px;
  color: var(--color-accent);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  letter-spacing: 0;
  text-transform: uppercase;
}

.provider-mask {
  border: 1px dashed var(--color-border);
  border-radius: 6px;
  background: var(--color-card-band);
  color: var(--color-text-muted);
  padding: 8px 12px;
  font-size: 13px;
}

.limit-grid {
  @apply grid gap-3 sm:grid-cols-2;
}

.limit-label {
  margin-bottom: 8px;
  color: var(--color-accent);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}
</style>
