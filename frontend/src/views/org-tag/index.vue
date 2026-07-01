<script setup lang="tsx">
import { NButton, NPopconfirm } from 'naive-ui';
import { fetchGetOrgTagList } from '@/service/api/org-tag';
import SvgIcon from '@/components/custom/svg-icon.vue';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';

const { columns, columnChecks, data, loading, getData, mobilePagination } = useTable({
  apiFn: fetchGetOrgTagList,
  showTotal: true,
  columns: () => [
    {
      key: 'name',
      title: 'Taxonomy / 标签',
      width: 320,
      render: row => {
        const depth = getTagDepth(row.tagId);
        return (
          <div class="taxonomy-name-cell" style={{ paddingLeft: `${Math.min(depth, 4) * 18}px` }}>
            <span class={['taxonomy-level-chip', depth === 0 ? 'taxonomy-level-chip--root' : '']}>
              {depth === 0 ? 'ROOT' : `L${depth + 1}`}
            </span>
            <div class="taxonomy-name-cell__copy">
              <div class="taxonomy-name-cell__name">{row.name}</div>
              <div class="taxonomy-name-cell__id">TAG {shortTagId(row.tagId)}</div>
            </div>
          </div>
        );
      }
    },
    {
      key: 'description',
      title: 'Scope Note / 描述',
      minWidth: 260,
      render: row => <div class="taxonomy-description">{row.description || '暂无描述'}</div>
    },
    {
      key: 'uploadMaxSizeMb',
      title: 'Upload Limit / 上传上限',
      width: 190,
      render: row =>
        row.uploadMaxSizeMb ? (
          <span class="taxonomy-limit-chip taxonomy-limit-chip--limited">
            {Number(row.uploadMaxSizeMb).toLocaleString()} MB
          </span>
        ) : (
          <span class="taxonomy-limit-chip">Unlimited</span>
        )
    },
    {
      key: 'operate',
      title: 'Actions / 操作',
      width: 250,
      render: row => (
        <div class="taxonomy-action-group">
          <NButton type="success" secondary size="small" onClick={() => addChild(row)}>
            {{
              icon: () => <SvgIcon icon="lucide:git-branch" class="text-14px" />,
              default: () => '下级'
            }}
          </NButton>
          <NButton type="primary" secondary size="small" onClick={() => edit(row)}>
            {{
              icon: () => <SvgIcon icon="lucide:pencil" class="text-14px" />,
              default: () => '编辑'
            }}
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.tagId!)}>
            {{
              default: () => '确认删除当前标签吗？',
              trigger: () => (
                <NButton type="error" secondary size="small">
                  {{
                    icon: () => <SvgIcon icon="lucide:trash-2" class="text-14px" />,
                    default: () => '删除'
                  }}
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const flatTags = computed(() => flattenTags(data.value as Api.OrgTag.Item[]));
const tagDepthMap = computed(() => new Map(flatTags.value.map(item => [item.tag.tagId, item.depth])));
const rootTagCount = computed(() => flatTags.value.filter(item => item.depth === 0).length);
const childTagCount = computed(() => Math.max(0, flatTags.value.length - rootTagCount.value));
const limitedTagCount = computed(() => flatTags.value.filter(item => item.tag.uploadMaxSizeMb).length);
const taxonomyStats = computed(() => [
  {
    label: 'Total tags',
    value: formatNumber(flatTags.value.length),
    detail: 'taxonomy nodes'
  },
  {
    label: 'Root scopes',
    value: formatNumber(rootTagCount.value),
    detail: 'top level'
  },
  {
    label: 'Child scopes',
    value: formatNumber(childTagCount.value),
    detail: 'nested nodes'
  },
  {
    label: 'Upload caps',
    value: formatNumber(limitedTagCount.value),
    detail: 'limited groups'
  }
]);

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
}

function flattenTags(items: Api.OrgTag.Item[] = [], depth = 0): { tag: Api.OrgTag.Item; depth: number }[] {
  return items.flatMap(item => [{ tag: item, depth }, ...flattenTags(item.children || [], depth + 1)]);
}

function getTagDepth(tagId?: string) {
  return tagId ? tagDepthMap.value.get(tagId) || 0 : 0;
}

function formatNumber(value?: number | string | null) {
  return Number(value || 0).toLocaleString();
}

function shortTagId(value?: string) {
  if (!value) {
    return '-';
  }
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value;
}
</script>

<template>
  <div class="admin-console-page taxonomy-page flex-col-stretch gap-16px overflow-auto">
    <NCard
      title="Taxonomy Tags / 分类标签"
      :bordered="false"
      size="small"
      class="admin-console-card taxonomy-card card-wrapper"
    >
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData">
          <template #default>
            <NButton size="small" secondary type="primary" @click="handleAdd">
              <template #icon>
                <SvgIcon icon="lucide:tag" class="text-icon" />
              </template>
              新建标签
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>

      <div class="taxonomy-summary">
        <div v-for="item in taxonomyStats" :key="item.label" class="taxonomy-summary__item">
          <span class="taxonomy-summary__label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <span>{{ item.detail }}</span>
        </div>
      </div>

      <NDataTable
        remote
        :columns="columns"
        :data="data"
        size="small"
        :scroll-x="962"
        :loading="loading"
        :pagination="mobilePagination"
        :row-key="item => item.tagId"
        class="taxonomy-table"
      />
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>
  </div>
</template>

<style lang="scss">
.taxonomy-page {
  overflow-x: hidden;
  padding-bottom: 8px;
}

.taxonomy-card .n-card-header__extra {
  min-width: 0;
}

.taxonomy-card .n-card__content {
  padding: 14px 16px 16px;
}

.taxonomy-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 14px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  overflow: hidden;
}

.taxonomy-summary__item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid var(--color-border);
}

.taxonomy-summary__item:last-child {
  border-right: 0;
}

.taxonomy-summary__label,
.taxonomy-summary__item > span:last-child {
  display: block;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.3;
}

.taxonomy-summary__label {
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-weight: 700;
  text-transform: uppercase;
}

.taxonomy-summary__item strong {
  display: block;
  margin: 4px 0 3px;
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 22px;
  line-height: 1.05;
}

.taxonomy-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.taxonomy-level-chip,
.taxonomy-limit-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 22px;
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-card-band);
  color: var(--color-text-muted);
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
  white-space: nowrap;
}

.taxonomy-level-chip--root,
.taxonomy-limit-chip--limited {
  border-color: var(--color-accent);
  background: var(--color-card-band);
  color: var(--color-accent);
}

.taxonomy-name-cell__copy {
  min-width: 0;
}

.taxonomy-name-cell__name {
  color: var(--color-text);
  font-weight: 700;
  line-height: 1.45;
}

.taxonomy-name-cell__id {
  color: var(--color-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  line-height: 1.4;
}

.taxonomy-description {
  display: -webkit-box;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.taxonomy-action-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}

.taxonomy-action-group .n-button {
  border-radius: 6px;
}

.taxonomy-table .n-data-table-base-table {
  border-radius: 7px;
}

.taxonomy-table .n-data-table-td {
  vertical-align: middle;
}

@media (max-width: 720px) {
  .taxonomy-card > .n-card-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    padding: 18px 24px;
  }

  .taxonomy-card .n-card-header__main,
  .taxonomy-card .n-card-header__extra {
    width: 100%;
  }

  .taxonomy-card .n-card-header__extra .n-space {
    width: 100% !important;
    justify-content: flex-start !important;
  }

  .taxonomy-card .n-card__content {
    padding: 12px;
  }

  .taxonomy-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .taxonomy-summary__item:nth-child(2n) {
    border-right: 0;
  }
}
</style>
