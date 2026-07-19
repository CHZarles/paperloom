<script setup lang="ts">
import type { DropdownOption } from 'naive-ui';
import dayjs from 'dayjs';
import { fileSize } from '@/utils/common';
import { UploadStatus } from '@/enum';

const props = defineProps<{
  rows: Api.Paper.UploadTask[];
  canManage: (row: Api.Paper.UploadTask) => boolean;
  isAdmin: boolean;
}>();

const emit = defineEmits<{
  (event: 'preview', row: Api.Paper.UploadTask): void;
  (event: 'tables', row: Api.Paper.UploadTask): void;
  (event: 'parser', row: Api.Paper.UploadTask): void;
  (event: 'retry', row: Api.Paper.UploadTask): void;
  (event: 'publish', row: Api.Paper.UploadTask): void;
  (event: 'unpublish', row: Api.Paper.UploadTask): void;
  (event: 'delete', paperId: string): void;
}>();

type PipelineTone = 'ready' | 'processing' | 'warning' | 'broken';

function isUploadCompleted(row: Api.Paper.UploadTask) {
  return row.status === UploadStatus.Completed || row.uploadStatus === 'COMPLETED' || row.uploadStatus === 1;
}

function isSearchable(row: Api.Paper.UploadTask) {
  if (row.searchable !== undefined) return row.searchable;
  return (
    isUploadCompleted(row) &&
    row.processingStatus === 'COMPLETED' &&
    Number(row.retrievalIndexedLocationCount || 0) > 0
  );
}

function pipelineState(row: Api.Paper.UploadTask): { label: string; tone: PipelineTone; detail?: string } {
  if (row.status === UploadStatus.Break) return { label: 'Interrupted', tone: 'broken' };
  if (!isUploadCompleted(row)) {
    return { label: `Uploading ${Number(row.progress || 0)}%`, tone: 'processing' };
  }
  if (row.processingStatus === 'FAILED') {
    return { label: 'Failed', tone: 'broken', detail: row.processingErrorMessage || 'Parse or index failed' };
  }
  if (isSearchable(row)) return { label: 'Searchable', tone: 'ready' };
  if (row.processingStatus === 'COMPLETED') return { label: 'Index missing', tone: 'warning' };
  if (row.processingStatus) return { label: 'Processing', tone: 'processing' };
  return { label: 'Pending', tone: 'warning' };
}

function compactNumber(value?: number | string | null) {
  const number = Number(value || 0);
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 1_000) return `${(number / 1_000).toFixed(1)}K`;
  return number.toLocaleString();
}

function formatSize(value?: number | string | null) {
  const bytes = Number(value || 0);
  return Number.isFinite(bytes) && bytes > 0 ? fileSize(bytes) : 'N/A';
}

function assetCount(row: Api.Paper.UploadTask) {
  return (
    Number(row.tableAsset?.tableCount || 0) +
    Number(row.figureAsset?.figureCount || 0) +
    Number(row.formulaAsset?.formulaCount || 0) +
    Number(row.visualAsset?.pageScreenshotCount || 0)
  );
}

function canRetry(row: Api.Paper.UploadTask) {
  return props.canManage(row) && row.processingStatus === 'FAILED';
}

function isGlobal(row: Api.Paper.UploadTask) {
  return row.libraryScope === 'GLOBAL';
}

function rowOptions(row: Api.Paper.UploadTask): DropdownOption[] {
  return [
    {
      label: 'Extracted tables',
      key: 'tables',
      disabled: !Number(row.tableAsset?.tableCount || 0)
    },
    {
      label: 'Parser JSON',
      key: 'parser',
      disabled: !props.canManage(row) || !row.parserArtifact?.available
    },
    ...(canRetry(row) ? [{ label: 'Retry processing', key: 'retry' }] : []),
    ...(props.isAdmin
      ? [
          isGlobal(row)
            ? { label: 'Remove from global library', key: 'unpublish' }
            : { label: 'Publish to global library', key: 'publish', disabled: !isSearchable(row) }
        ]
      : []),
    ...(props.canManage(row)
      ? [{ label: isGlobal(row) ? 'Remove from personal library' : 'Delete paper', key: 'delete' }]
      : [])
  ];
}

function handleRowAction(key: string | number, row: Api.Paper.UploadTask) {
  if (key === 'tables') emit('tables', row);
  if (key === 'parser') emit('parser', row);
  if (key === 'retry') emit('retry', row);
  if (key === 'publish') emit('publish', row);
  if (key === 'unpublish') {
    window.$dialog?.warning({
      title: 'Remove from global library?',
      content: row.originalFilename,
      positiveText: 'Remove',
      negativeText: 'Cancel',
      onPositiveClick: () => emit('unpublish', row)
    });
  }
  if (key !== 'delete') return;

  window.$dialog?.warning({
    title: isGlobal(row) ? 'Remove from personal library?' : 'Delete paper?',
    content: row.originalFilename,
    positiveText: isGlobal(row) ? 'Remove' : 'Delete',
    negativeText: 'Cancel',
    onPositiveClick: () => emit('delete', row.paperId)
  });
}
</script>

<template>
  <div class="paper-mobile-list">
    <NEmpty v-if="!rows.length" description="No papers found" />

    <article v-for="row in rows" :key="row.paperId" class="paper-mobile-item">
      <header class="paper-mobile-item__header">
        <button type="button" class="paper-mobile-item__title" @click="emit('preview', row)">
          {{ row.originalFilename }}
        </button>
        <NDropdown :options="rowOptions(row)" trigger="click" @select="key => handleRowAction(key, row)">
          <NButton quaternary circle class="paper-mobile-item__menu" aria-label="Paper actions">
            <template #icon>
              <icon-lucide:ellipsis />
            </template>
          </NButton>
        </NDropdown>
      </header>

      <div class="paper-mobile-item__identity">
        <code>{{ row.paperId.slice(0, 8) }}</code>
        <span>{{ formatSize(row.totalSize ?? row.sourceFileSizeBytes) }}</span>
        <span>{{ dayjs(row.createdAt).format('YYYY-MM-DD') }}</span>
      </div>

      <div class="paper-mobile-item__state">
        <span
          class="paper-mobile-status"
          :class="`paper-mobile-status--${pipelineState(row).tone}`"
          :title="pipelineState(row).detail"
        >
          <span />
          {{ pipelineState(row).label }}
        </span>
        <span class="paper-mobile-visibility" :class="{ 'is-public': isGlobal(row) }">
          {{ isGlobal(row) ? 'Global' : 'Private' }}
        </span>
      </div>

      <div class="paper-mobile-item__metrics">
        <div>
          <span>Lexical index</span>
          <strong>
            {{ row.retrievalIndexedTokenCount == null ? 'Pending' : compactNumber(row.retrievalIndexedTokenCount) }}
          </strong>
          <small>{{ Number(row.retrievalIndexedLocationCount || 0).toLocaleString() }} locations</small>
        </div>
      </div>

      <div class="paper-mobile-item__assets">
        <span :class="{ 'is-ready': row.pdfEvidenceAvailable || row.evidenceAssetLevel === 'PDF_VISUAL' }">
          PDF evidence
        </span>
        <span :class="{ 'is-ready': row.parserArtifact?.available }">Parser</span>
        <span>Assets {{ assetCount(row).toLocaleString() }}</span>
      </div>

      <footer class="paper-mobile-item__footer">
        <span>{{ isGlobal(row) ? 'Global library' : 'Personal library' }}</span>
        <NButton secondary size="small" @click="emit('preview', row)">
          <template #icon>
            <icon-lucide:eye />
          </template>
          Preview
        </NButton>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.paper-mobile-list {
  display: grid;
  gap: 12px;
}

.paper-mobile-item {
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
  padding: 14px;
}

.paper-mobile-item__header,
.paper-mobile-item__state,
.paper-mobile-item__footer {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.paper-mobile-item__title {
  min-width: 0;
  overflow: hidden;
  border: 0;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  padding: 0;
  font-family: var(--font-reading);
  font-size: 16px;
  font-weight: 650;
  line-height: 1.35;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.paper-mobile-item__menu {
  flex: 0 0 auto;
}

.paper-mobile-item__identity {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 5px;
  color: var(--color-text-muted);
  font-size: 11px;
}

.paper-mobile-item__identity code {
  color: var(--color-text);
  font-family: var(--font-utility);
}

.paper-mobile-item__state {
  margin-top: 14px;
}

.paper-mobile-status,
.paper-mobile-visibility,
.paper-mobile-item__assets span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  padding: 4px 8px;
  font-size: 11px;
  font-weight: 650;
}

.paper-mobile-status > span {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
}

.paper-mobile-status--ready,
.paper-mobile-visibility.is-public,
.paper-mobile-item__assets span.is-ready {
  border-color: color-mix(in srgb, var(--color-research) 42%, var(--color-border));
  background: var(--color-research-soft-bg);
  color: var(--color-research);
}

.paper-mobile-status--processing {
  color: var(--color-research);
}

.paper-mobile-status--warning {
  color: var(--color-warning);
}

.paper-mobile-status--broken {
  color: var(--color-error);
}

.paper-mobile-item__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 12px;
  border-block: 1px solid var(--color-border-soft);
}

.paper-mobile-item__metrics > div {
  min-width: 0;
  padding: 12px 0;
}

.paper-mobile-item__metrics > div + div {
  border-left: 1px solid var(--color-border-soft);
  padding-left: 14px;
}

.paper-mobile-item__metrics span,
.paper-mobile-item__metrics small {
  display: block;
  color: var(--color-text-muted);
  font-size: 11px;
}

.paper-mobile-item__metrics strong {
  display: block;
  margin: 3px 0;
  color: var(--color-text);
  font-family: var(--font-utility);
  font-size: 15px;
}

.paper-mobile-item__assets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.paper-mobile-item__footer {
  margin-top: 14px;
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>
