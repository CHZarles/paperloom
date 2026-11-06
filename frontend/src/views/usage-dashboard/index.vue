<script setup lang="tsx">
import { computed, ref } from 'vue';
import { fetchUsageOverview } from '@/service/api';

const loading = ref(false);
const days = ref(7);
const overview = ref<Api.Admin.UsageOverview | null>(null);

const dayOptions = [7, 14, 30];

const todaySummary = computed(() => {
  const today = overview.value?.today;
  return [
    { label: 'Chat Requests', value: formatNumber(today?.chatRequestCount), detail: 'today' },
    {
      label: 'LLM Tokens',
      value: formatNumber(today?.llmUsedTokens),
      detail: `${formatNumber(today?.llmRequestCount)} calls today`
    },
    {
      label: 'Embedding Tokens',
      value: formatNumber(today?.embeddingUsedTokens),
      detail: `${formatNumber(today?.embeddingRequestCount)} calls today`
    }
  ];
});

const trendRows = computed(() => [...(overview.value?.trends || [])].reverse());

async function load() {
  try {
    loading.value = true;
    const res = await fetchUsageOverview(days.value);
    if (!res.error) {
      overview.value = res.data;
    }
  } finally {
    loading.value = false;
  }
}

function formatNumber(value?: number | string | null) {
  return Number(value || 0).toLocaleString();
}

function formatRatio(item: { usedTokens: number; limitTokens: number }) {
  if (!item.limitTokens) {
    return '-';
  }
  return `${((item.usedTokens / item.limitTokens) * 100).toFixed(1)}%`;
}

const rankingColumns = [
  {
    key: 'username',
    title: 'Researcher / 用户',
    minWidth: 120,
    render: (row: Api.Admin.UsageRankingItem) => <span class="usage-rank-user">{row.username || row.userId}</span>
  },
  {
    key: 'usedTokens',
    title: '已用 / 总量',
    minWidth: 170,
    render: (row: Api.Admin.UsageRankingItem) => (
      <div class="usage-quota-cell">
        <span>
          {formatNumber(row.usedTokens)} / {row.limitTokens ? formatNumber(row.limitTokens) : '∞'}
        </span>
        <span class="usage-quota-cell__meta">
          {formatRatio(row)} · {formatNumber(row.requestCount)} 次
        </span>
      </div>
    )
  },
  {
    key: 'remainingTokens',
    title: '剩余',
    width: 110,
    render: (row: Api.Admin.UsageRankingItem) => <span>{formatNumber(row.remainingTokens)}</span>
  }
];

const trendColumns = [
  { key: 'day', title: 'Date / 日期', width: 110 },
  {
    key: 'chatRequestCount',
    title: 'Chat',
    width: 90,
    render: (row: Api.Admin.UsageTrendPoint) => <span>{formatNumber(row.chatRequestCount)}</span>
  },
  {
    key: 'llm',
    title: 'LLM Tokens / Calls',
    minWidth: 170,
    render: (row: Api.Admin.UsageTrendPoint) => (
      <div class="usage-quota-cell">
        <span>{formatNumber(row.llmUsedTokens)}</span>
        <span class="usage-quota-cell__meta">{formatNumber(row.llmRequestCount)} calls</span>
      </div>
    )
  },
  {
    key: 'embedding',
    title: 'Embedding Tokens / Calls',
    minWidth: 190,
    render: (row: Api.Admin.UsageTrendPoint) => (
      <div class="usage-quota-cell">
        <span>{formatNumber(row.embeddingUsedTokens)}</span>
        <span class="usage-quota-cell__meta">{formatNumber(row.embeddingRequestCount)} calls</span>
      </div>
    )
  }
];

load();
</script>

<template>
  <div class="usage-dashboard-page">
    <NCard title="Token Usage / 用量" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <div class="usage-toolbar">
          <NRadioGroup v-model:value="days" size="small" @update:value="load">
            <NRadioButton v-for="option in dayOptions" :key="option" :value="option">{{ option }} 天</NRadioButton>
          </NRadioGroup>
          <NButton size="small" secondary :loading="loading" @click="load">
            <template #icon>
              <icon-lucide:refresh-cw />
            </template>
            刷新
          </NButton>
        </div>
      </template>

      <div class="usage-summary">
        <div v-for="item in todaySummary" :key="item.label" class="usage-summary__item">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.detail }}</small>
        </div>
      </div>

      <div v-if="overview?.alerts?.length" class="usage-alerts">
        <NTag
          v-for="(alert, index) in overview.alerts"
          :key="index"
          :type="alert.level === 'critical' ? 'error' : 'warning'"
          size="small"
        >
          {{ alert.username || alert.userId }} · {{ alert.scope }} · {{ alert.message }}
        </NTag>
      </div>

      <NDataTable
        size="small"
        :columns="trendColumns"
        :data="trendRows"
        :loading="loading"
        :row-key="row => row.day"
        :pagination="false"
        class="usage-trend-table"
      />
    </NCard>

    <div class="usage-ranking-grid">
      <NCard title="LLM Ranking / 排行" :bordered="false" size="small" class="card-wrapper">
        <NDataTable
          size="small"
          :columns="rankingColumns"
          :data="overview?.llmRankings || []"
          :loading="loading"
          :row-key="row => row.userId"
          :pagination="false"
        />
      </NCard>
      <NCard title="Embedding Ranking / 排行" :bordered="false" size="small" class="card-wrapper">
        <NDataTable
          size="small"
          :columns="rankingColumns"
          :data="overview?.embeddingRankings || []"
          :loading="loading"
          :row-key="row => row.userId"
          :pagination="false"
        />
      </NCard>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.usage-dashboard-page {
  display: grid;
  gap: 16px;
}

.usage-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.usage-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-bottom: 14px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  overflow: hidden;
}

.usage-summary__item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid var(--color-border);
}

.usage-summary__item:last-child {
  border-right: 0;
}

.usage-summary__item span,
.usage-summary__item small {
  display: block;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.3;
}

.usage-summary__item span {
  font-weight: 700;
  text-transform: uppercase;
}

.usage-summary__item strong {
  display: block;
  margin: 4px 0 3px;
  color: var(--color-text);
  font-size: 22px;
  line-height: 1.05;
}

.usage-alerts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.usage-trend-table {
  margin-bottom: 4px;
}

.usage-ranking-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.usage-quota-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.45;
  white-space: nowrap;
}

.usage-quota-cell__meta {
  color: var(--color-text-muted);
  font-size: 11px;
}

.usage-rank-user {
  font-weight: 600;
}

@media (max-width: 1120px) {
  .usage-ranking-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .usage-summary {
    grid-template-columns: 1fr;
  }

  .usage-summary__item {
    border-bottom: 1px solid var(--color-border);
    border-right: 0;
  }

  .usage-summary__item:last-child {
    border-bottom: 0;
  }
}
</style>
