<script setup lang="ts">
import { computed, defineAsyncComponent, h, onMounted, ref, watch } from 'vue';
import { NTag } from 'naive-ui';
import RechargePage from '@/views/recharge/index.vue';

const InviteCodePage = defineAsyncComponent(() => import('@/views/invite-code/index.vue'));
const ModelProviderPage = defineAsyncComponent(() => import('@/views/model-provider/index.vue'));
const OrgTagPage = defineAsyncComponent(() => import('@/views/org-tag/index.vue'));
const RechargeManagePage = defineAsyncComponent(() => import('@/views/recharge-manage/index.vue'));
const UsageMonitorPage = defineAsyncComponent(() => import('@/views/usage-monitor/index.vue'));
const UserPage = defineAsyncComponent(() => import('@/views/user/index.vue'));

const settingsPanelCacheKey = Symbol.for('paperloom.settingsPanelCache');

type SettingsPanelCache = {
  userId: string;
  tags: Api.OrgTag.Mine;
  usage: Api.User.UsageSnapshot;
  tokenRecords: Api.User.TokenRecord[];
  pagination: {
    page: number;
    pageSize: number;
    total: number;
    pageCount: number;
  };
  profileLoaded: boolean;
  ledgerLoaded: boolean;
};

type SettingsPanelGlobal = typeof globalThis & {
  [settingsPanelCacheKey]?: SettingsPanelCache | null;
};

type SettingsSection =
  | 'general'
  | 'usage'
  | 'organization'
  | 'ledger'
  | 'recharge'
  | 'userAdmin'
  | 'orgTagAdmin'
  | 'modelProvider'
  | 'inviteCode'
  | 'usageMonitor'
  | 'rechargeManage';

const adminSections = new Set<SettingsSection>([
  'userAdmin',
  'orgTagAdmin',
  'modelProvider',
  'inviteCode',
  'usageMonitor',
  'rechargeManage'
]);

const emit = defineEmits<{
  close: [];
}>();

const authStore = useAuthStore();
const { userInfo } = storeToRefs(authStore);

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const usage = ref<Api.User.UsageSnapshot>({
  day: '',
  chatRequestCount: 0,
  llm: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  },
  embedding: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  }
});

const profileLoading = ref(false);
const profileLoaded = ref(false);
const tokenRecords = ref<Api.User.TokenRecord[]>([]);
const tokenRecordLoading = ref(false);
const tokenRecordsLoaded = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
  pageCount: 0
});

const visible = ref(false);
const currentTagId = ref('');
const submitLoading = ref(false);
const activeSection = ref<SettingsSection>('general');

const userCacheId = computed(() => String(userInfo.value.id || userInfo.value.username || 'anonymous'));
const accountName = computed(() => userInfo.value.username || 'Folio User');
const accountRoleLabel = computed(() => (userInfo.value.role === 'ADMIN' ? 'Admin' : 'User'));
const avatarCells = computed(() => buildAvatarCells(accountName.value));
const avatarStyle = computed(() => ({
  '--account-avatar-fill': buildAvatarFill(accountName.value)
}));

const quotaCards = computed(() => [
  {
    key: 'llm',
    title: 'LLM Token',
    enabled: usage.value.llm.enabled,
    usedTokens: usage.value.llm.usedTokens,
    limitTokens: usage.value.llm.limitTokens,
    remainingTokens: usage.value.llm.remainingTokens,
    requestCount: usage.value.llm.requestCount
  },
  {
    key: 'embedding',
    title: 'Embedding Token',
    enabled: usage.value.embedding.enabled,
    usedTokens: usage.value.embedding.usedTokens,
    limitTokens: usage.value.embedding.limitTokens,
    remainingTokens: usage.value.embedding.remainingTokens,
    requestCount: usage.value.embedding.requestCount
  }
]);

const tokenRecordColumns = computed(() => [
  {
    title: '日期',
    key: 'recordDate',
    width: 100,
    render: (row: Api.User.TokenRecord) => row.recordDate
  },
  {
    title: 'Token 类型',
    key: 'tokenType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        LLM: { text: 'LLM', type: 'info' },
        EMBEDDING: { text: 'Embedding', type: 'success' }
      };
      const type = typeMap[row.tokenType] || { text: row.tokenType, type: 'default' };
      return h(NTag, { type: type.type }, () => type.text);
    }
  },
  {
    title: '变动类型',
    key: 'changeType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        INCREASE: { text: '充值', type: 'success' },
        CONSUME: { text: '消耗', type: 'warning' }
      };
      const type = typeMap[row.changeType] || { text: row.changeType, type: 'default' };
      return h(NTag, { type: type.type }, () => type.text);
    }
  },
  {
    title: '变动数量',
    key: 'amount',
    width: 120,
    render: (row: Api.User.TokenRecord) => {
      const sign = row.changeType === 'INCREASE' ? '+' : '-';
      return `${sign}${row.amount.toLocaleString()}`;
    }
  },
  {
    title: '变动前余额',
    key: 'balanceBefore',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceBefore?.toLocaleString() || '-'
  },
  {
    title: '变动后余额',
    key: 'balanceAfter',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceAfter?.toLocaleString() || '-'
  },
  {
    title: '原因',
    key: 'reason',
    minWidth: 100,
    ellipsis: { tooltip: true },
    render: (row: Api.User.TokenRecord) => row.reason || '-'
  },
  {
    title: '请求次数',
    key: 'requestCount',
    width: 80,
    render: (row: Api.User.TokenRecord) => row.requestCount?.toLocaleString() || '0'
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render: (row: Api.User.TokenRecord) => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);

onMounted(() => {
  restoreCachedSettings();
  window.requestAnimationFrame(() => {
    getPersonalData();
  });
});

watch(activeSection, section => {
  if (section === 'ledger' && !tokenRecordsLoaded.value && !tokenRecordLoading.value) {
    getTokenRecords();
  }
});

watch(
  () => authStore.isAdmin,
  isAdmin => {
    if (!isAdmin && isAdminSection(activeSection.value)) {
      activeSection.value = 'general';
    }
  }
);

function isAdminSection(section: SettingsSection) {
  return adminSections.has(section);
}

async function getPersonalData() {
  profileLoading.value = true;
  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({
      url: '/users/org-tags'
    }),
    request<Api.User.UsageSnapshot>({
      url: '/users/usage'
    })
  ]);

  if (!orgError) {
    tags.value = orgData;
  }

  if (!usageError) {
    usage.value = usageData;
  }

  profileLoaded.value = !orgError || !usageError;
  cacheSettingsPanelState();
  profileLoading.value = false;
}

async function getOrgTags() {
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
    profileLoaded.value = true;
    cacheSettingsPanelState();
  }
}

function showModal(tagId: string) {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
}

async function setPrimaryOrg() {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    await getOrgTags();
  }
  submitLoading.value = false;
}

async function getTokenRecords() {
  tokenRecordLoading.value = true;
  try {
    const { error, data } = await request({
      url: '/users/token-records',
      method: 'GET',
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.pageSize
      }
    });

    if (!error && data) {
      tokenRecords.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      pagination.value.pageCount = data.totalPages || 0;
      tokenRecordsLoaded.value = true;
      cacheSettingsPanelState();
    }
  } finally {
    tokenRecordLoading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  getTokenRecords();
}

function restoreCachedSettings() {
  const cachedSettings = readSettingsPanelCache();
  if (!cachedSettings || cachedSettings.userId !== userCacheId.value) {
    return;
  }

  tags.value = cachedSettings.tags;
  usage.value = cachedSettings.usage;
  tokenRecords.value = cachedSettings.tokenRecords;
  pagination.value = { ...cachedSettings.pagination };
  profileLoaded.value = cachedSettings.profileLoaded;
  tokenRecordsLoaded.value = cachedSettings.ledgerLoaded;
}

function cacheSettingsPanelState() {
  (globalThis as SettingsPanelGlobal)[settingsPanelCacheKey] = {
    userId: userCacheId.value,
    tags: tags.value,
    usage: usage.value,
    tokenRecords: tokenRecords.value,
    pagination: { ...pagination.value },
    profileLoaded: profileLoaded.value,
    ledgerLoaded: tokenRecordsLoaded.value
  };
}

function readSettingsPanelCache() {
  return (globalThis as SettingsPanelGlobal)[settingsPanelCacheKey] || null;
}

function formatToken(value?: number | null) {
  return Number(value || 0).toLocaleString();
}

function quotaPercent(card: (typeof quotaCards.value)[number]) {
  if (!card.enabled || !card.limitTokens) return 0;
  return Math.min(100, Math.round((card.usedTokens / card.limitTokens) * 100));
}

function buildAvatarCells(seed: string) {
  let hash = 0;
  const normalized = seed || 'Folio';

  for (let index = 0; index < normalized.length; index += 1) {
    hash = Math.imul(31, hash) + normalized.charCodeAt(index);
  }

  return Array.from({ length: 25 }, (_, index) => {
    const row = Math.floor(index / 5);
    const col = index % 5;
    const mirroredCol = col > 2 ? 4 - col : col;
    const cellSeed = Math.abs(hash + row * 131 + mirroredCol * 977);
    return cellSeed % 4 !== 0;
  });
}

function buildAvatarFill(seed: string) {
  let hash = 0;
  const normalized = seed || 'Folio';

  for (let index = 0; index < normalized.length; index += 1) {
    hash = Math.imul(33, hash) + normalized.charCodeAt(index);
  }

  const hue = Math.abs(hash) % 360;
  return `hsl(${hue} 54% 38%)`;
}
</script>

<template>
  <div class="settings-spin">
    <div class="settings-modal" data-testid="settings-modal" :data-profile-loading="profileLoading && !profileLoaded">
      <aside class="settings-nav">
        <button type="button" class="settings-account">
          <span class="account-avatar" :style="avatarStyle" aria-hidden="true">
            <span
              v-for="(filled, index) in avatarCells"
              :key="index"
              class="account-avatar__cell"
              :class="{ 'account-avatar__cell--filled': filled }"
            />
          </span>
          <span class="account-copy">
            <strong>{{ accountName }}</strong>
            <small>{{ accountRoleLabel }}</small>
          </span>
        </button>

        <div class="settings-nav__label">Account</div>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'general' }"
          @click="activeSection = 'general'"
        >
          <icon-lucide:settings />
          <span>General</span>
        </button>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'usage' }"
          @click="activeSection = 'usage'"
        >
          <icon-lucide:activity />
          <span>Usage</span>
        </button>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'organization' }"
          @click="activeSection = 'organization'"
        >
          <icon-lucide:tags />
          <span>Organization</span>
        </button>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'ledger' }"
          @click="activeSection = 'ledger'"
        >
          <icon-lucide:receipt-text />
          <span>Token Ledger</span>
        </button>

        <div class="settings-nav__label">Billing</div>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'recharge' }"
          @click="activeSection = 'recharge'"
        >
          <icon-lucide:credit-card />
          <span>Recharge</span>
        </button>

        <template v-if="authStore.isAdmin">
          <div class="settings-nav__label">Admin</div>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'userAdmin' }"
            @click="activeSection = 'userAdmin'"
          >
            <icon-lucide:users />
            <span>User Management</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'orgTagAdmin' }"
            @click="activeSection = 'orgTagAdmin'"
          >
            <icon-lucide:tags />
            <span>Org Tag Admin</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'modelProvider' }"
            @click="activeSection = 'modelProvider'"
          >
            <icon-lucide:flask-conical />
            <span>Embedding Model / 向量模型</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'inviteCode' }"
            @click="activeSection = 'inviteCode'"
          >
            <icon-lucide:ticket />
            <span>Invite Codes</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'usageMonitor' }"
            @click="activeSection = 'usageMonitor'"
          >
            <icon-lucide:chart-line />
            <span>Usage Monitor</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'rechargeManage' }"
            @click="activeSection = 'rechargeManage'"
          >
            <icon-lucide:receipt />
            <span>Recharge Management</span>
          </button>
        </template>
      </aside>

      <main class="settings-main">
        <button type="button" class="settings-close" aria-label="关闭设置" @click="emit('close')">
          <icon-lucide:x />
        </button>

        <section v-show="activeSection === 'general'" class="settings-section">
          <h1>General</h1>
          <div class="settings-rule" />
          <div class="settings-row">
            <label>Username</label>
            <strong>{{ accountName }}</strong>
          </div>
          <div class="settings-row">
            <label>Role</label>
            <strong>{{ accountRoleLabel }}</strong>
          </div>
          <div class="settings-row">
            <label>Primary organization</label>
            <strong>{{ tags.primaryOrg || 'N/A' }}</strong>
          </div>
          <div class="settings-row">
            <label>Quota day</label>
            <strong>{{ usage.day || 'N/A' }}</strong>
          </div>
        </section>

        <section v-show="activeSection === 'usage'" class="settings-section">
          <h1>Usage</h1>
          <div class="settings-rule" />
          <div class="quota-list">
            <article v-for="card in quotaCards" :key="card.key" class="quota-panel">
              <div class="quota-panel__head">
                <div>
                  <h2>{{ card.title }}</h2>
                  <p>{{ card.enabled ? `${formatToken(card.requestCount)} requests` : 'Quota disabled' }}</p>
                </div>
                <strong>{{ card.enabled ? `${quotaPercent(card)}%` : 'OFF' }}</strong>
              </div>
              <NProgress
                :percentage="quotaPercent(card)"
                :show-indicator="false"
                :status="card.enabled ? 'success' : 'default'"
              />
              <div class="quota-panel__metrics">
                <span>
                  <small>Used</small>
                  {{ formatToken(card.usedTokens) }}
                </span>
                <span>
                  <small>Limit</small>
                  {{ card.enabled ? formatToken(card.limitTokens) : 'OFF' }}
                </span>
                <span>
                  <small>Remaining</small>
                  {{ card.enabled ? formatToken(card.remainingTokens) : 'OFF' }}
                </span>
              </div>
            </article>
          </div>
        </section>

        <section v-show="activeSection === 'organization'" class="settings-section">
          <h1>Organization</h1>
          <div class="settings-rule" />
          <div class="section-count">{{ tags.orgTagDetails.length }} tags</div>
          <div v-if="tags.orgTagDetails.length" class="org-tag-grid">
            <button
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              type="button"
              class="org-tag-card"
              :class="{ 'org-tag-card--primary': tag.tagId === tags.primaryOrg }"
              :disabled="tag.tagId === tags.primaryOrg"
              @click="showModal(tag.tagId)"
            >
              <span class="org-tag-card__title">
                {{ tag.name }}
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small">
                  主标签
                  <template #icon>
                    <icon-lucide:check-circle class="text-icon" />
                  </template>
                </NTag>
              </span>
              <NEllipsis :line-clamp="2" class="org-tag-card__desc">
                {{ tag.description || tag.tagId }}
              </NEllipsis>
            </button>
          </div>
          <NEmpty v-else description="暂无组织标签" />
        </section>

        <section v-show="activeSection === 'ledger'" class="settings-section settings-section--ledger">
          <h1>Token Ledger</h1>
          <div class="settings-rule" />
          <div class="section-count">{{ pagination.total.toLocaleString() }} records</div>
          <NSpin :show="tokenRecordLoading">
            <NDataTable
              v-if="tokenRecords.length > 0"
              :columns="tokenRecordColumns"
              :data="tokenRecords"
              :loading="tokenRecordLoading"
              :pagination="{
                page: pagination.page,
                pageSize: pagination.pageSize,
                itemCount: pagination.total,
                onChange: handlePageChange
              }"
              :scroll-x="1200"
              size="small"
              class="token-record-table"
            />
            <NEmpty v-else description="暂无记录" />
          </NSpin>
        </section>

        <section
          v-if="authStore.isAdmin && activeSection === 'modelProvider'"
          class="settings-section settings-section--model-provider"
        >
          <ModelProviderPage />
        </section>
        <section v-if="activeSection === 'recharge'" class="settings-section settings-section--embedded">
          <RechargePage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'userAdmin'"
          class="settings-section settings-section--embedded"
        >
          <div id="header-extra" class="settings-embedded-header-extra" />
          <UserPage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'orgTagAdmin'"
          class="settings-section settings-section--embedded"
        >
          <OrgTagPage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'inviteCode'"
          class="settings-section settings-section--embedded"
        >
          <InviteCodePage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'usageMonitor'"
          class="settings-section settings-section--embedded"
        >
          <UsageMonitorPage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'rechargeManage'"
          class="settings-section settings-section--embedded"
        >
          <RechargeManagePage />
        </section>
      </main>
    </div>

    <NModal
      v-model:show="visible"
      :loading="submitLoading"
      preset="dialog"
      title="设置主标签"
      content="确定将当前标签设置为主标签吗？"
      positive-text="确认"
      negative-text="取消"
      @positive-click="setPrimaryOrg"
      @negative-click="visible = false"
    />
  </div>
</template>

<style scoped lang="scss">
.settings-spin {
  width: min(1480px, calc(100vw - 32px));
}

.settings-modal {
  display: grid;
  width: min(1480px, calc(100vw - 32px));
  height: min(830px, calc(100vh - 92px));
  grid-template-columns: 260px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  background: var(--color-surface);
  box-shadow: var(--shadow-card-soft);
}

.settings-nav {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-bg);
  padding: 16px 10px;
}

.settings-account,
.settings-nav__item {
  display: flex;
  width: 100%;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
}

.settings-account {
  min-width: 0;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 6px 8px;
}

.account-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  grid-template-columns: repeat(5, 1fr);
  grid-template-rows: repeat(5, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  border-radius: 8px;
  background: var(--color-surface-elevated);
  box-shadow:
    0 1px 0 color-mix(in srgb, var(--color-text) 8%, transparent) inset,
    var(--shadow-card);
  gap: 1px;
  padding: 5px;
}

.account-avatar__cell {
  border-radius: 1px;
}

.account-avatar__cell--filled {
  background: var(--account-avatar-fill, #57606a);
}

.account-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.account-copy strong,
.account-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy strong {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 680;
}

.account-copy small {
  color: var(--color-text-muted);
  font-size: 11px;
}

.settings-nav__label {
  margin: 18px 10px 7px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 680;
}

.settings-nav__item {
  align-items: center;
  gap: 9px;
  height: 34px;
  margin: 1px 0;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 620;
  transition:
    background-color 0.16s ease,
    color 0.16s ease;
}

.settings-nav__item svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  color: var(--color-text-muted);
}

.settings-nav__item span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-nav__item:hover,
.settings-nav__item--active {
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.settings-main {
  position: relative;
  min-width: 0;
  overflow-y: auto;
  padding: 42px 56px 48px;
}

.settings-close {
  position: absolute;
  top: 18px;
  right: 20px;
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.settings-close:hover {
  background: var(--color-surface-alt);
  color: var(--color-text);
}

.settings-section {
  min-width: 0;
  width: 100%;
}

.settings-embedded-header-extra {
  margin-bottom: 12px;
}

.settings-section h1 {
  margin: 0;
  color: var(--color-text);
  font-size: 22px;
  font-weight: 680;
  letter-spacing: 0;
  line-height: 1.2;
}

.settings-rule {
  height: 1px;
  margin: 23px 0 24px;
  background: var(--color-border);
}

.settings-row {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--color-border-soft);
}

.settings-row label {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 620;
}

.settings-row strong {
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 560;
  text-align: right;
}

.quota-list {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.quota-panel {
  min-width: 0;
  border-bottom: 1px solid var(--color-border-soft);
  padding: 0 0 18px;
}

.quota-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.quota-panel__head h2,
.section-count {
  margin: 0 0 3px;
  color: var(--color-text);
  font-size: 15px;
  font-weight: 760;
  letter-spacing: 0;
  line-height: 1.2;
}

.quota-panel__head strong {
  color: var(--color-text);
  font-size: 20px;
  line-height: 1;
}

.quota-panel__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.quota-panel__metrics span {
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-alt);
  color: var(--color-text);
  font-size: 13px;
  font-weight: 700;
  padding: 8px 9px;
}

.quota-panel__metrics small {
  display: block;
  margin-bottom: 2px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 650;
}

.section-count {
  margin-bottom: 12px;
  color: var(--color-text-muted);
  font-weight: 620;
}

.org-tag-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 8px;
}

.org-tag-card {
  display: block;
  width: 100%;
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-alt);
  color: var(--color-text);
  cursor: pointer;
  padding: 11px 12px;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background-color 0.16s ease;
}

.org-tag-card:hover {
  border-color: var(--color-primary);
  background: var(--color-surface-alt);
}

.org-tag-card:disabled {
  cursor: default;
}

.org-tag-card--primary,
.org-tag-card--primary:hover {
  border-color: color-mix(in srgb, var(--color-primary) 42%, var(--color-border));
  background: color-mix(in srgb, var(--color-surface) 82%, var(--color-primary) 8%);
}

.org-tag-card__title {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 720;
}

.org-tag-card__desc {
  margin-top: 8px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.settings-section :deep(.n-data-table) {
  --n-td-color: var(--color-surface) !important;
  --n-th-color: var(--color-card-band) !important;
  --n-border-color: var(--color-border) !important;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
}

.settings-section :deep(.n-data-table-wrapper) {
  min-width: 0;
}

.settings-section :deep(.n-data-table-th) {
  background: var(--color-card-band-pressed);
  color: var(--color-text);
  font-size: 12px;
  padding: 10px 10px;
}

.settings-section :deep(.n-data-table-td) {
  background: var(--color-surface);
  padding: 10px 10px;
  vertical-align: middle;
}

@media (max-width: 1120px) {
  .settings-modal {
    width: calc(100vw - 32px);
    height: calc(100vh - 48px);
  }
}

@media (max-width: 720px) {
  .settings-spin,
  .settings-modal {
    width: 100vw;
    height: 100vh;
  }

  .settings-modal {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
    border: 0;
    border-radius: 0;
  }

  .settings-nav {
    display: flex;
    gap: 4px;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
    padding: 8px 44px 8px 8px;
    scrollbar-width: none;
  }

  .settings-nav::-webkit-scrollbar {
    display: none;
  }

  .settings-account,
  .settings-nav__label {
    display: none;
  }

  .settings-nav__item {
    width: auto;
    flex: 0 0 auto;
    margin: 0;
    white-space: nowrap;
  }

  .settings-main {
    padding: 24px 18px;
  }

  .quota-panel__metrics {
    grid-template-columns: 1fr;
  }

  .org-tag-grid {
    grid-template-columns: 1fr;
  }

  .settings-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
    padding: 12px 0;
  }

  .settings-row strong {
    text-align: left;
  }
}
</style>
