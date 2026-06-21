<script setup lang="tsx">
import { onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty } from 'naive-ui';

const modelProvidersLoading = ref(false);
const modelProvidersSaving = ref(false);
const modelProviders = ref<Api.Admin.ModelProviderSettings | null>(null);

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

function cloneModelProviderSettings(
  payload?: Api.Admin.ModelProviderSettings | null
): Api.Admin.ModelProviderSettings | null {
  if (!payload) {
    return null;
  }
  return {
    llm: cloneModelProviderScope(payload.llm),
    embedding: cloneModelProviderScope(payload.embedding)
  };
}

async function getModelProviders() {
  modelProvidersLoading.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderSettings>({
    url: '/admin/model-providers'
  });

  if (!error && data) {
    modelProviders.value = cloneModelProviderSettings(data);
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

async function submitModelProviders(scopeKey: 'llm' | 'embedding') {
  const scope = modelProviders.value?.[scopeKey];
  if (!scope) {
    return;
  }

  modelProvidersSaving.value = true;
  const { error, data } = await request<Api.Admin.ModelProviderScopeSettings>({
    url: `/admin/model-providers/${scopeKey}`,
    method: 'put',
    data: buildProviderPayload(scope)
  });

  if (!error && data && modelProviders.value) {
    modelProviders.value[scopeKey] = cloneModelProviderScope(data);
    window.$message?.success(scopeKey === 'llm' ? 'LLM 模型配置已更新' : 'Embedding 配置已更新');
  }
  modelProvidersSaving.value = false;
}

async function testModelProvider(scopeKey: 'llm' | 'embedding', provider: Api.Admin.ModelProviderItem) {
  const { error, data } = await request<Api.Admin.ConnectivityTestResult>({
    url: `/admin/model-providers/${scopeKey}/test`,
    method: 'post',
    data: {
      provider: provider.provider,
      apiBaseUrl: provider.apiBaseUrl,
      model: provider.model,
      apiKey: provider.apiKeyInput?.trim() || '',
      dimension: scopeKey === 'embedding' ? provider.dimension : null
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
    <NCard :bordered="false" size="small" class="admin-console-card card-wrapper model-provider-card">
      <template #header>Experiment Config / 模型配置</template>
      <template #header-extra>
        <div class="flex items-center gap-2">
          <span class="text-xs">LLM 保存后新请求立即生效，Embedding 暂不允许危险直切</span>
        </div>
      </template>

      <NSpin :show="modelProvidersLoading">
        <div class="admin-console-note mb-4 model-provider-note">
          这里管理平台代付的模型接入配置。API Key 输入为空时保留现有密钥，不会回显明文。Embedding 如果切换 active
          provider，后端会拦截需要重嵌入的危险变更。
        </div>

        <div v-if="modelProviders" class="grid gap-4">
          <div class="provider-scope">
            <div class="provider-scope-header">
              <div>
                <div class="provider-scope-title">LLM Provider</div>
                <div class="provider-scope-sub">聊天请求会按当前 active provider 路由</div>
              </div>
              <div class="flex items-center gap-3">
                <NSelect
                  v-model:value="modelProviders.llm.activeProvider"
                  :options="
                    modelProviders.llm.providers.map(item => ({
                      label: item.displayName,
                      value: item.provider,
                      disabled: !item.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton
                  type="primary"
                  size="small"
                  :loading="modelProvidersSaving"
                  @click="submitModelProviders('llm')"
                >
                  保存 LLM 配置
                </NButton>
              </div>
            </div>

            <div class="provider-grid">
              <div v-for="item in modelProviders.llm.providers" :key="`llm-${item.provider}`" class="provider-card">
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
                    <div class="limit-label">现有密钥</div>
                    <div class="provider-mask">{{ item.hasApiKey ? item.maskedApiKey : '未配置' }}</div>
                  </div>
                  <div>
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
                  <NButton size="small" secondary @click="testModelProvider('llm', item)">测试连接</NButton>
                </div>
              </div>
            </div>
          </div>

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
                  v-model:value="modelProviders.embedding.activeProvider"
                  :options="
                    modelProviders.embedding.providers.map(item => ({
                      label: item.displayName,
                      value: item.provider,
                      disabled: !item.enabled
                    }))
                  "
                  class="min-w-180px"
                />
                <NButton
                  type="primary"
                  size="small"
                  :loading="modelProvidersSaving"
                  @click="submitModelProviders('embedding')"
                >
                  保存 Embedding 配置
                </NButton>
              </div>
            </div>

            <div class="provider-grid">
              <div
                v-for="item in modelProviders.embedding.providers"
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
                  <NButton size="small" secondary @click="testModelProvider('embedding', item)">测试连接</NButton>
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
  box-shadow: 5px 5px 0 rgba(201, 193, 178, 0.42) !important;
}

.model-provider-card ::v-deep(.n-card-header) {
  border-bottom: 1px solid #c9c1b2;
  background: #e2dccc;
  padding: 14px 20px;
}

.model-provider-card ::v-deep(.n-card-header__main) {
  color: #26364a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.model-provider-card ::v-deep(.n-card-header__extra) {
  color: #5e6470;
}

.model-provider-card ::v-deep(.n-card__content) {
  background: #fbfaf6;
  padding: 16px 20px;
}

// 2. Note: dashed paper sticker
.model-provider-note {
  background: #fbfaf6 !important;
  border: 1px dashed #c9c1b2 !important;
  border-radius: 8px;
  font-size: 13px;
}

.provider-scope {
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: linear-gradient(180deg, #e2dccc, #fbfaf6);
  box-shadow: 3px 3px 0 rgba(201, 193, 178, 0.48);
  padding: 20px;
}

.provider-scope-header {
  @apply mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between;
}

.provider-scope-title {
  color: #26364a;
  font-size: 14px;
  font-weight: 700;
}

.provider-scope-sub {
  margin-top: 4px;
  color: #5e6470;
  font-size: 12px;
}

.provider-grid {
  @apply grid gap-4 xl:grid-cols-2;
}

.provider-card {
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: #fbfaf6;
  padding: 16px;
}

.provider-card-header {
  @apply mb-4 flex items-start justify-between gap-3;
}

.provider-name {
  color: #4b4032;
  font-size: 14px;
  font-weight: 700;
}

.provider-code {
  margin-top: 4px;
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0;
  text-transform: uppercase;
}

.provider-mask {
  border: 1px dashed #c9c1b2;
  border-radius: 6px;
  background: #e2dccc;
  color: #5e6470;
  padding: 8px 12px;
  font-size: 13px;
}

.limit-grid {
  @apply grid gap-3 sm:grid-cols-2;
}

.limit-label {
  margin-bottom: 8px;
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}
</style>
