<script setup lang="tsx">
import { computed, ref } from 'vue';
import { NButton } from 'naive-ui';
import { enableStatusOptions } from '@/constants/common';
import SvgIcon from '@/components/custom/svg-icon.vue';
import UserSearch from './modules/user-search.vue';
import OrgTagSettingDialog from './modules/org-tag-setting-dialog.vue';
import TokenQuotaDialog from './modules/token-quota-dialog.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

function apiFn(params: Api.User.SearchParams) {
  return request<Api.User.List>({ url: '/admin/users/list', params });
}

const { columns, columnChecks, data, getData, loading, mobilePagination, searchParams, resetSearchParams } = useTable({
  apiFn,
  apiParams: {
    keyword: null,
    orgTag: null,
    status: null
  },
  showTotal: true,
  columns: () => [
    {
      key: 'index',
      title: '#',
      width: 52,
      render: row => <span class="user-index">#{row.index}</span>
    },
    {
      key: 'username',
      title: 'Researcher / 用户',
      width: 150,
      render: row => (
        <div class="user-identity">
          <div class="user-identity__name">{row.username}</div>
          <div class="user-identity__meta">UID {shortId(row.userId)}</div>
        </div>
      )
    },
    {
      key: 'orgTags',
      title: 'Scopes / 组织',
      width: 230,
      render: row =>
        row.orgTags?.length ? (
          <div class="user-scope-list">
            {row.orgTags.map(tag => (
              <span
                key={tag.tagId}
                class={['user-scope-chip', tag.tagId === row.primaryOrg ? 'user-scope-chip--primary' : '']}
              >
                {tag.name}
              </span>
            ))}
          </div>
        ) : (
          <span class="user-empty-text">未分配组织</span>
        )
    },
    {
      key: 'status',
      title: 'Access / 状态',
      width: 118,
      render: row => (
        <span class={['user-access', Number(row.status) ? 'user-access--enabled' : 'user-access--disabled']}>
          <span class="user-access__dot"></span>
          {Number(row.status) ? 'Active' : 'Paused'}
        </span>
      )
    },
    {
      key: 'chatUsage',
      title: 'Chat / 今日',
      width: 104,
      render: row => (
        <div class="user-metric-cell">
          <span class="user-metric-cell__value">{formatNumber(row.usage?.chatRequestCount)}</span>
          <span class="user-metric-cell__label">messages</span>
        </div>
      )
    },
    {
      key: 'createdAt',
      title: 'Joined / 创建',
      width: 118,
      render: row => (
        <div class="user-date-cell">
          <span>{dayjs(row.createdAt).format('YYYY-MM-DD')}</span>
          <span>{dayjs(row.createdAt).format('HH:mm:ss')}</span>
        </div>
      )
    },
    {
      key: 'llmUsage',
      title: 'Budgets / 额度',
      width: 220,
      render: row => renderBudgetCell(row)
    },
    {
      key: 'operate',
      title: 'Actions / 操作',
      width: 180,
      render: row => (
        <div class="user-action-group">
          <NButton type="primary" secondary size="small" onClick={() => handleOrgTag(row)}>
            {{
              icon: () => <SvgIcon icon="mdi:tag-multiple-outline" class="text-14px" />,
              default: () => '组织'
            }}
          </NButton>
          {authStore.isAdmin ? (
            <NButton type="warning" secondary size="small" onClick={() => handleTokenQuota(row)}>
              {{
                icon: () => <SvgIcon icon="mdi:database-plus-outline" class="text-14px" />,
                default: () => 'Token'
              }}
            </NButton>
          ) : null}
        </div>
      )
    }
  ]
});

const visible = ref(false);
const editingData = ref<Api.User.Item | null>(null);
const tokenVisible = ref(false);
const tokenEditingData = ref<Api.User.Item | null>(null);

const currentRows = computed(() => data.value || []);
const totalUserCount = computed(() => Number(mobilePagination.value.itemCount || currentRows.value.length));
const activeUserCount = computed(() => currentRows.value.filter(row => Number(row.status)).length);
const pausedUserCount = computed(() => currentRows.value.filter(row => !Number(row.status)).length);
const scopedUserCount = computed(() => currentRows.value.filter(row => row.orgTags?.length).length);
const budgetEnabledCount = computed(
  () => currentRows.value.filter(row => row.usage?.llm?.enabled || row.usage?.embedding?.enabled).length
);

const registryStats = computed(() => [
  {
    label: 'Total users',
    value: formatNumber(totalUserCount.value),
    detail: 'all pages'
  },
  {
    label: 'Active',
    value: formatNumber(activeUserCount.value),
    detail: 'current page'
  },
  {
    label: 'Paused',
    value: formatNumber(pausedUserCount.value),
    detail: 'current page'
  },
  {
    label: 'Scoped',
    value: formatNumber(scopedUserCount.value),
    detail: 'tagged rows'
  },
  {
    label: 'Budgeted',
    value: formatNumber(budgetEnabledCount.value),
    detail: 'LLM / Embedding'
  }
]);

function handleOrgTag(row: Api.User.Item) {
  editingData.value = row;
  visible.value = true;
}

function handleTokenQuota(row: Api.User.Item) {
  tokenEditingData.value = row;
  tokenVisible.value = true;
}

function formatNumber(value?: number | string | null) {
  return Number(value || 0).toLocaleString();
}

function shortId(value?: string) {
  if (!value) {
    return '-';
  }
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value;
}

function renderBudgetCell(row: Api.User.Item) {
  return (
    <div class="user-budget-cell">
      {renderBudgetLine('LLM', row.usage?.llm)}
      {renderBudgetLine('EMB', row.usage?.embedding)}
    </div>
  );
}

function renderBudgetLine(label: string, quota?: Api.User.UsageQuota) {
  if (!quota?.enabled) {
    return (
      <div class="user-budget-line user-budget-line--disabled">
        <span class="user-budget-line__label">{label}</span>
        <span class="user-empty-text">off</span>
      </div>
    );
  }

  const used = Number(quota.usedTokens || 0);
  const limit = Number(quota.limitTokens || 0);
  const percent = limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;

  return (
    <div class="user-budget-line">
      <div class="user-budget-line__top">
        <span class="user-budget-line__label">{label}</span>
        <span>
          {formatNumber(used)} / {formatNumber(limit)}
        </span>
      </div>
      <div class="user-quota-meter" aria-hidden="true">
        <span style={{ width: `${percent}%` }}></span>
      </div>
      <div class="user-quota-cell__meta">
        {formatNumber(quota.remainingTokens)} left · {formatNumber(quota.requestCount)} req
      </div>
    </div>
  );
}
</script>

<template>
  <div class="admin-console-page user-registry-page flex-col-stretch gap-16px overflow-auto">
    <Teleport v-if="!appStore.isMobile" defer to="#header-extra">
      <UserSearch v-model:model="searchParams" @reset="resetSearchParams" @search="getData" />
    </Teleport>

    <div v-else class="user-mobile-filter">
      <div class="user-mobile-filter__header">
        <div class="user-filter-strip__label">
          <icon-mdi-account-filter-outline />
          <span>User Filter</span>
        </div>
        <div class="user-mobile-filter__actions">
          <NButton size="small" secondary @click="resetSearchParams">
            <template #icon>
              <icon-mdi-filter-remove-outline />
            </template>
            重置
          </NButton>
          <NButton size="small" type="primary" secondary @click="getData">
            <template #icon>
              <icon-mdi-magnify />
            </template>
            搜索
          </NButton>
        </div>
      </div>

      <div class="user-mobile-filter__grid">
        <label>
          <span>关键词</span>
          <NInput v-model:value="searchParams.keyword" placeholder="用户 / UID" clearable size="small">
            <template #prefix>
              <icon-mdi-magnify />
            </template>
          </NInput>
        </label>
        <label>
          <span>组织标签</span>
          <OrgTagCascader v-model:value="searchParams.orgTag" clearable size="small" />
        </label>
        <label>
          <span>启用状态</span>
          <NSelect
            v-model:value="searchParams.status"
            placeholder="请选择启用状态"
            :options="enableStatusOptions"
            clearable
            size="small"
          />
        </label>
      </div>
    </div>

    <NCard
      title="User Registry / 用户"
      :bordered="false"
      size="small"
      class="admin-console-card user-registry-card card-wrapper"
    >
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>

      <div class="user-registry-summary">
        <div v-for="item in registryStats" :key="item.label" class="user-registry-summary__item">
          <span class="user-registry-summary__label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <span>{{ item.detail }}</span>
        </div>
      </div>

      <NDataTable
        :columns="columns"
        :data="data"
        size="small"
        :scroll-x="1172"
        :loading="loading"
        remote
        :row-key="row => row.userId"
        :pagination="mobilePagination"
        class="user-registry-table"
      />
    </NCard>

    <OrgTagSettingDialog v-model:visible="visible" :row-data="editingData!" @submitted="getData" />
    <TokenQuotaDialog v-model:visible="tokenVisible" :row-data="tokenEditingData!" @submitted="getData" />
  </div>
</template>

<style lang="scss">
.user-registry-page {
  padding-bottom: 8px;
  overflow-x: hidden;
}

.user-registry-card {
  --user-line: #c9c1b2;
}

.user-mobile-filter {
  width: 100%;
  border: 1px solid #c9c1b2;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfaf6, #ebe6da);
  box-shadow: 3px 3px 0 rgba(201, 193, 178, 0.38);
  padding: 12px;
}

.user-mobile-filter__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.user-mobile-filter__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-mobile-filter__actions .n-button {
  border-radius: 6px;
}

.user-mobile-filter__grid {
  display: grid;
  gap: 10px;
}

.user-mobile-filter__grid label {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  color: #7c6b55;
  font-size: 12px;
  font-weight: 600;
}

.user-mobile-filter__grid .n-input,
.user-mobile-filter__grid .n-base-selection {
  --n-border: 1px solid #c9c1b2 !important;
  --n-border-hover: 1px solid #7e3f46 !important;
  --n-border-focus: 1px solid #7e3f46 !important;
  --n-color: #e2dccc !important;
  --n-color-focus: #fbfaf6 !important;
  border-radius: 6px;
}

.user-registry-card .n-card__content {
  padding: 14px 16px 16px;
}

.user-registry-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-bottom: 14px;
  border: 1px solid var(--user-line);
  border-radius: 8px;
  background: linear-gradient(180deg, #e2dccc, #fbfaf6);
  overflow: hidden;
}

.user-registry-summary__item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid rgba(201, 193, 178, 0.72);
}

.user-registry-summary__item:last-child {
  border-right: 0;
}

.user-registry-summary__label,
.user-registry-summary__item > span:last-child {
  display: block;
  color: #5e6470;
  font-size: 11px;
  line-height: 1.3;
}

.user-registry-summary__label {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-weight: 700;
  text-transform: uppercase;
}

.user-registry-summary__item strong {
  display: block;
  margin: 4px 0 3px;
  color: #20242a;
  font-family: Georgia, 'Times New Roman', 'Noto Serif SC', serif;
  font-size: 22px;
  line-height: 1.05;
}

.user-registry-table .n-data-table-base-table {
  border-radius: 7px;
}

.user-registry-table .n-data-table-td {
  vertical-align: middle;
}

.user-index {
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.user-identity {
  min-width: 0;
}

.user-identity__name {
  color: #20242a;
  font-weight: 700;
  line-height: 1.45;
}

.user-identity__meta,
.user-date-cell span:last-child,
.user-metric-cell__label,
.user-quota-cell__meta,
.user-empty-text {
  color: #747a84;
  font-size: 11px;
  line-height: 1.45;
}

.user-identity__meta {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.user-scope-list,
.user-action-group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.user-scope-chip {
  max-width: 136px;
  padding: 2px 8px;
  border: 1px solid #c9c1b2;
  border-radius: 999px;
  background: #e2dccc;
  color: #5e6470;
  font-size: 12px;
  line-height: 1.55;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-scope-chip--primary {
  border-color: #7e3f46;
  background: #e2dccc;
  color: #7e3f46;
  font-weight: 700;
}

.user-access {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 9px;
  border: 1px solid;
  border-radius: 999px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
}

.user-access__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
}

.user-access--enabled {
  border-color: rgba(79, 125, 90, 0.35);
  background: rgba(79, 125, 90, 0.11);
  color: #3f6b4a;
}

.user-access--enabled .user-access__dot {
  background: #4f7d5a;
}

.user-access--disabled {
  border-color: rgba(155, 107, 46, 0.35);
  background: rgba(155, 107, 46, 0.1);
  color: #9a6428;
}

.user-access--disabled .user-access__dot {
  background: #9a6428;
}

.user-metric-cell,
.user-date-cell,
.user-quota-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-metric-cell__value,
.user-date-cell span:first-child {
  color: #20242a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.user-budget-cell {
  display: grid;
  gap: 8px;
  min-width: 190px;
}

.user-budget-line {
  display: grid;
  gap: 4px;
}

.user-budget-line--disabled {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 18px;
}

.user-budget-line__top {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  color: #20242a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 700;
}

.user-budget-line__top span:nth-child(2) {
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-budget-line__label {
  color: #7e3f46;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  font-weight: 800;
}

.user-quota-meter {
  height: 5px;
  border: 1px solid rgba(201, 193, 178, 0.9);
  border-radius: 999px;
  background: #ddd6c8;
  overflow: hidden;
}

.user-quota-meter span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #7e3f46, #7e3f46);
}

.user-action-group {
  flex-wrap: nowrap;
}

.user-action-group .n-button {
  border-radius: 6px;
}

@media (max-width: 1180px) {
  .user-registry-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .user-registry-summary__item {
    border-bottom: 1px solid rgba(201, 193, 178, 0.72);
  }

  .user-registry-summary__item:nth-child(3n) {
    border-right: 0;
  }
}

@media (max-width: 640px) {
  .user-registry-card .n-card__content {
    padding: 12px;
  }

  .user-registry-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .user-registry-summary__item:nth-child(3n) {
    border-right: 1px solid rgba(201, 193, 178, 0.72);
  }

  .user-registry-summary__item:nth-child(2n) {
    border-right: 0;
  }
}
</style>
