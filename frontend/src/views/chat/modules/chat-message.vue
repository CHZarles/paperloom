<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { router } from '@/router';
import { request } from '@/service/request';
import { formatDate } from '@/utils/common';
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message;
  sessionId?: string;
  retrievalQueryFallback?: string;
  previewMode?: 'page' | 'drawer';
}>();

const emit = defineEmits<{
  (
    e: 'openReference',
    payload: {
      retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
      retrievalLabel?: string | null;
      retrievalQuery?: string | null;
      evidenceSnippet?: string | null;
      matchedChunkText?: string | null;
      score?: number | null;
      chunkId?: number | null;
      paperTitle: string;
      paperId?: string | null;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number;
      referenceNumber: number;
    }
  ): void;
}>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();
const feedbackSubmitting = ref<Record<string, boolean>>({});

function getMessageFeedbackKey(message: Api.Chat.Message) {
  return message.generationId || `${message.conversationId || 'unknown'}:${message.timestamp || ''}`;
}

async function handleFeedback(message: Api.Chat.Message, rating: 'good' | 'bad') {
  if (message.role !== 'assistant') {
    return;
  }

  const key = getMessageFeedbackKey(message);
  if (feedbackSubmitting.value[key]) {
    return;
  }

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: true
  };

  const { error } = await request({
    url: 'chat/feedback',
    method: 'POST',
    data: {
      rating,
      reason: rating === 'good' ? '用户点击点赞，表示认可本次回答' : '用户点击点踩，表示不满意本次回答',
      conversationId: message.conversationId || props.sessionId,
      generationId: message.generationId
    }
  });

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: false
  };

  if (error) {
    window.$message?.error('反馈记录失败');
    return;
  }

  message.feedbackRating = rating;
  window.$message?.success(rating === 'good' ? '已记录点赞反馈' : '已记录点踩反馈');
}

// 存储文件名和对应的事件处理
const sourceFiles = ref<
  Array<{ paperTitle: string; id: string; referenceNumber: number; paperId?: string; pageNumber?: number }>
>([]);
// eslint-disable-next-line no-useless-escape
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+/g;
const toolNameLabels: Record<string, string> = {
  search_knowledge: '检索文献库',
  generate_summary: '生成论文摘要',
  submit_feedback: '记录反馈',
  knowledge_stats: '读取文献统计'
};
const toolStatusLabels: Record<Api.Chat.AgentToolEvent['status'], string> = {
  executing: '执行中',
  success: '已完成',
  failed: '失败'
};
const sourceCitationPattern = /(?:来源|source)\s*#\s*(\d+)/gi;

const toolEvents = computed(() => props.msg.toolEvents || []);
const citedReferenceNumbers = computed(() => getCitedReferenceNumbers(props.msg.content || ''));
const referenceEntries = computed(() => {
  const mappings = props.msg.referenceMappings || {};
  return Object.entries(mappings)
    .map(([referenceNumber, detail]) => ({
      referenceNumber: Number.parseInt(referenceNumber, 10),
      detail
    }))
    .filter(
      item =>
        Number.isFinite(item.referenceNumber) &&
        item.detail?.paperId &&
        citedReferenceNumbers.value.has(item.referenceNumber)
    )
    .sort((left, right) => left.referenceNumber - right.referenceNumber);
});

function getToolLabel(tool: string) {
  return toolNameLabels[tool] || tool;
}

function getToolStatusLabel(status: Api.Chat.AgentToolEvent['status']) {
  return toolStatusLabels[status] || status;
}

function splitTrailingUrlPunctuation(rawUrl: string) {
  let url = rawUrl;
  let trailing = '';

  while (url) {
    const lastChar = url.at(-1);
    if (!lastChar) break;

    let shouldTrim = false;
    if (/[，。！？；：、,.!?;:]/.test(lastChar)) {
      shouldTrim = true;
    } else if (lastChar === ')' || lastChar === '）') {
      const openingChar = lastChar === ')' ? '(' : '（';
      const closingChar = lastChar;
      const openingCount = (url.match(new RegExp(`\\${openingChar}`, 'g')) || []).length;
      const closingCount = (url.match(new RegExp(`\\${closingChar}`, 'g')) || []).length;

      if (closingCount > openingCount) {
        shouldTrim = true;
      }
    }

    if (!shouldTrim) break;

    trailing = `${lastChar}${trailing}`;
    url = url.slice(0, -1);
  }

  return { url, trailing };
}

function normalizeBareUrls(text: string) {
  return text.replace(bareUrlPattern, (match, offset: number, source: string) => {
    const previousChar = source[offset - 1] || '';
    const previousTwoChars = source.slice(Math.max(0, offset - 2), offset);
    const previousTenChars = source.slice(Math.max(0, offset - 10), offset).toLowerCase();

    if (previousChar === '<' || previousTwoChars === '](' || /(?:href|src)=["']?$/.test(previousTenChars)) {
      return match;
    }

    const { url, trailing } = splitTrailingUrlPunctuation(match);
    return url ? `<${url}>${trailing}` : match;
  });
}

function getCitedReferenceNumbers(text: string) {
  const numbers = new Set<number>();
  sourceCitationPattern.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = sourceCitationPattern.exec(text)) !== null) {
    const referenceNumber = Number.parseInt(match[1], 10);
    if (Number.isFinite(referenceNumber)) {
      numbers.add(referenceNumber);
    }
  }
  return numbers;
}

function createSourceLink(
  sourceNum: string,
  paperTitle: string,
  fallback?: { paperId?: string; pageNumber?: number }
): string {
  const persistedDetail = props.msg.referenceMappings?.[sourceNum];
  const paperId = persistedDetail?.paperId || fallback?.paperId;
  if (!paperId) {
    return `来源#${sourceNum}: ${paperTitle.trim()}`;
  }

  const linkClass = 'source-file-link';
  const trimmedFileName = persistedDetail?.paperTitle || paperTitle.trim();
  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);
  const pageNumber = persistedDetail?.pageNumber ?? fallback?.pageNumber ?? undefined;
  const displayName = pageNumber ? `${trimmedFileName} (第${pageNumber}页)` : trimmedFileName;

  sourceFiles.value.push({
    paperTitle: trimmedFileName,
    id: fileId,
    referenceNumber,
    paperId,
    pageNumber
  });

  return `来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${displayName}</span>`;
}

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 重置来源文件列表，避免重复
  sourceFiles.value = [];

  // 支持单个来源，也支持一个括号里包含多个来源：
  // (来源#1: test.pdf | 第5页; 来源#2: other.pdf | 第8页)
  const entryBoundary = '(?=\\s*(?:[;；,，、。！？!?\\)）]|$))';
  const pagePattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*第(\\d+)页${entryBoundary}`,
    'g'
  );
  const md5Pattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*MD5:\\s*([a-fA-F0-9]+)${entryBoundary}`,
    'g'
  );
  const simplePattern = new RegExp(`来源#(\\d+):\\s*([^<>\\n\\r|;；,，、。！？!?]+?)${entryBoundary}`, 'g');

  let processedText = text.replace(pagePattern, (...matches) => {
    const [, sourceNum, paperTitle, pageNum] = matches;
    return createSourceLink(sourceNum, paperTitle, {
      pageNumber: Number.parseInt(pageNum, 10)
    });
  });

  processedText = processedText.replace(md5Pattern, (...matches) => {
    const [, sourceNum, paperTitle, paperId] = matches;
    return createSourceLink(sourceNum, paperTitle, {
      paperId: paperId.trim()
    });
  });

  processedText = processedText.replace(simplePattern, (_match, sourceNum, paperTitle) => {
    return createSourceLink(sourceNum, paperTitle);
  });

  return processedText;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processSourceLinks(rawContent));
  }

  return rawContent;
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  const rawText = scope?.textContent?.replace(/\s+/g, ' ').trim() || '';
  if (!rawText) return '';

  const beforeCitation = rawText.split(/(?:\(|（)?来源#\d+:/)[0] || rawText;
  return beforeCitation
    .replace(/^\s*\d+\.\s*/, '')
    .replace(/[（(]\s*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function openReferencePreviewPage(payload: {
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  score?: number | null;
  chunkId?: number | null;
  paperTitle: string;
  paperId?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  conversationRecordId?: number;
  referenceNumber: number;
}) {
  if (props.previewMode === 'drawer') {
    emit('openReference', payload);
    return;
  }

  const previewKey = `reference-preview:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  localStorage.setItem(previewKey, JSON.stringify(payload));

  const routeLocation = router.resolve({
    path: '/chat',
    query: {
      preview: 'reference',
      previewKey
    }
  });

  router.push(routeLocation);
}

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  const sourceTarget = target.closest<HTMLElement>('.source-file-link');

  // 检查点击的是否是文件链接
  if (sourceTarget) {
    event.preventDefault();
    event.stopPropagation();

    const fileId = sourceTarget.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        const contextAnchorText = extractContextAnchorText(sourceTarget);
        handleSourceFileClick({
          paperTitle: file.paperTitle,
          referenceNumber: file.referenceNumber,
          paperId: file.paperId,
          pageNumber: file.pageNumber,
          anchorText: contextAnchorText
        });
      }
    }
  }
}

// 处理来源文件点击事件
// Existing reference resolution has several fallback branches because older persisted messages may miss detail fields.
// eslint-disable-next-line complexity
async function handleSourceFileClick(fileInfo: {
  paperTitle: string;
  referenceNumber: number;
  paperId?: string;
  pageNumber?: number;
  anchorText?: string;
}) {
  const {
    paperTitle,
    referenceNumber,
    paperId: extractedPaperId,
    pageNumber: extractedPageNumber,
    anchorText: clickedAnchorText
  } = fileInfo;
  const persistedDetail =
    props.msg.referenceMappings?.[String(referenceNumber)] || props.msg.referenceMappings?.[referenceNumber];
  const conversationRecordId = props.msg.conversationRecordId;

  try {
    let detail: Api.Paper.ReferenceDetailResponse | null = null;
    const fallbackRetrievalQuery = props.retrievalQueryFallback || '';

    if (
      conversationRecordId &&
      (!persistedDetail?.retrievalQuery || !persistedDetail?.matchedChunkText || !persistedDetail?.evidenceSnippet)
    ) {
      try {
        const { error: detailError, data: detailData } = await request<Api.Paper.ReferenceDetailResponse>({
          url: 'papers/reference-detail',
          params: {
            conversationRecordId,
            referenceNumber: referenceNumber.toString()
          }
        });

        if (!detailError && detailData?.paperId) {
          detail = detailData;
        }
      } catch {
        // Continue with persisted or parsed citation data when the detail endpoint is unavailable.
      }
    }

    if (persistedDetail?.paperId && !detail) {
      openReferencePreviewPage({
        paperTitle: persistedDetail.paperTitle || paperTitle,
        paperId: persistedDetail.paperId,
        pageNumber: persistedDetail.pageNumber,
        anchorText: persistedDetail.anchorText || clickedAnchorText || '',
        retrievalMode: persistedDetail.retrievalMode,
        retrievalLabel: persistedDetail.retrievalLabel,
        retrievalQuery: persistedDetail.retrievalQuery || fallbackRetrievalQuery,
        evidenceSnippet: persistedDetail.evidenceSnippet,
        matchedChunkText: persistedDetail.matchedChunkText,
        score: persistedDetail.score,
        chunkId: persistedDetail.chunkId,
        conversationRecordId,
        referenceNumber
      });
      return;
    }

    const targetPaperId = detail?.paperId || extractedPaperId || null;
    openReferencePreviewPage({
      paperTitle: detail?.paperTitle || paperTitle,
      paperId: targetPaperId,
      pageNumber: detail?.pageNumber ?? extractedPageNumber,
      anchorText: detail?.anchorText || clickedAnchorText || '',
      retrievalMode: detail?.retrievalMode,
      retrievalLabel: detail?.retrievalLabel,
      retrievalQuery: detail?.retrievalQuery || fallbackRetrievalQuery,
      evidenceSnippet: detail?.evidenceSnippet,
      matchedChunkText: detail?.matchedChunkText,
      score: detail?.score,
      chunkId: detail?.chunkId,
      conversationRecordId,
      referenceNumber
    });
  } catch {
    window.$message?.error(`论文预览失败: ${paperTitle}`);
  }
}

function openMappedReference(referenceNumber: number, detail: Api.Chat.ReferenceEvidence) {
  openReferencePreviewPage({
    paperTitle: detail.paperTitle,
    paperId: detail.paperId,
    pageNumber: detail.pageNumber,
    anchorText: detail.anchorText || '',
    retrievalMode: detail.retrievalMode,
    retrievalLabel: detail.retrievalLabel,
    retrievalQuery: detail.retrievalQuery || props.retrievalQueryFallback || '',
    evidenceSnippet: detail.evidenceSnippet,
    matchedChunkText: detail.matchedChunkText,
    score: detail.score,
    chunkId: detail.chunkId,
    conversationRecordId: props.msg.conversationRecordId,
    referenceNumber
  });
}
</script>

<template>
  <div class="message-block" :class="msg.role === 'user' ? 'message-block--user' : 'message-block--assistant'">
    <div v-if="msg.role === 'user'" class="message-heading">
      <NAvatar class="user-avatar">
        <SvgIcon icon="material-symbols:account-circle-outline-sharp" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="message-author">{{ msg.username || authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="message-heading">
      <NAvatar class="bg-transparent">
        <SystemLogo class="text-36px" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="message-author">PaperLoom</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-if="msg.role === 'assistant' && toolEvents.length > 0" class="ml-12 mt-3 flex flex-col gap-2">
      <div
        v-for="event in toolEvents"
        :key="event.id || event.tool"
        class="tool-event"
        :class="`tool-event--${event.status}`"
      >
        <icon-eos-icons:three-dots-loading v-if="event.status === 'executing'" class="text-4" />
        <icon-material-symbols:check-circle-rounded v-else-if="event.status === 'success'" class="text-4" />
        <icon-material-symbols:error-rounded v-else class="text-4" />
        <span class="tool-event__name">{{ getToolLabel(event.tool) }}</span>
        <span class="tool-event__status">{{ getToolStatusLabel(event.status) }}</span>
      </div>
    </div>
    <NText v-if="msg.status === 'pending' || (msg.status === 'loading' && msg.role === 'assistant' && !msg.content)">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic" style="color: var(--color-error)">
      {{ msg.content || '服务器繁忙，请稍后再试' }}
    </NText>
    <div v-else-if="msg.role === 'assistant'" class="assistant-content message-content" @click="handleContentClick">
      <VueMarkdownIt :content="content" />
      <div v-if="referenceEntries.length > 0" class="reference-list" aria-label="参考来源">
        <div class="reference-list__title">References</div>
        <button
          v-for="entry in referenceEntries"
          :key="entry.referenceNumber"
          class="reference-list__item"
          type="button"
          @click.stop="openMappedReference(entry.referenceNumber, entry.detail)"
        >
          <span class="reference-list__badge">[{{ entry.referenceNumber }}]</span>
          <span class="reference-list__body">
            <span class="reference-list__file">
              {{ entry.detail.paperTitle }}
              <span v-if="entry.detail.pageNumber">· 第{{ entry.detail.pageNumber }}页</span>
            </span>
            <span class="reference-list__meta">
              {{ entry.detail.retrievalLabel || '文献库召回' }}
              <span v-if="entry.detail.score !== null && entry.detail.score !== undefined">
                · {{ Number(entry.detail.score).toFixed(3) }}
              </span>
            </span>
            <span v-if="entry.detail.evidenceSnippet" class="reference-list__snippet">
              {{ entry.detail.evidenceSnippet }}
            </span>
          </span>
        </button>
      </div>
    </div>
    <NText v-else-if="msg.role === 'user'" class="message-content user-content">{{ content }}</NText>
    <NDivider class="message-divider" />
    <div class="message-actions">
      <NButton quaternary title="复制回答" aria-label="复制回答" @click="handleCopy(msg.content)">
        <template #icon>
          <icon-material-symbols:content-copy-outline-rounded />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        title="点赞"
        aria-label="点赞"
        :type="msg.feedbackRating === 'good' ? 'primary' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'good')"
      >
        <template #icon>
          <icon-material-symbols:thumb-up-outline-rounded />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        title="点踩"
        aria-label="点踩"
        :type="msg.feedbackRating === 'bad' ? 'error' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'bad')"
      >
        <template #icon>
          <icon-material-symbols:thumb-down-outline-rounded />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: var(--color-primary);
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: var(--color-accent);
    text-decoration: none;
  }

  &:active {
    color: var(--color-accent-hover);
  }
}

.message-block {
  margin-bottom: 28px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-heading {
  display: flex;
  align-items: center;
  gap: 14px;
}

.message-author {
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text);
}

.message-content {
  margin-top: 2px;
  margin-left: 52px;
  max-width: min(860px, calc(100% - 52px));
  font-size: 15px;
  line-height: 1.78;
  color: var(--color-text);
}

.message-block--assistant .message-author {
  color: var(--color-primary);
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 17px;
}

.user-avatar {
  background: var(--color-accent);
}

.user-content {
  display: inline-flex;
  width: fit-content;
  max-width: min(760px, calc(100% - 52px));
  white-space: pre-wrap;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-card-band);
  padding: 10px 14px;
}

.assistant-content :deep(p) {
  margin: 0 0 12px;
}

.reference-list {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.reference-list__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.reference-list__item {
  display: flex;
  width: 100%;
  cursor: pointer;
  gap: 10px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  padding: 9px 10px;
  text-align: left;
  transition:
    border-color 0.2s,
    background 0.2s;
}

.reference-list__item:hover {
  border-color: var(--color-primary);
  background: var(--color-card-band);
}

.reference-list__badge {
  display: inline-flex;
  min-width: 34px;
  align-items: center;
  justify-content: center;
  border-right: 1px solid var(--color-border);
  color: var(--color-primary);
  padding-right: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.reference-list__body {
  min-width: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.reference-list__file {
  overflow: hidden;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-list__meta,
.reference-list__snippet {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-divider {
  margin-bottom: 0 !important;
  margin-left: 52px;
  margin-top: 4px !important;
  width: calc(100% - 52px);
  opacity: 0.55;
}

.message-actions {
  margin-left: 52px;
  display: flex;
  gap: 8px;
}

.dark .message-author,
.dark .message-content {
  color: var(--color-text);
}

.dark .user-content {
  border-color: var(--color-border);
  background: var(--color-card-band);
}

.dark .reference-list__title,
.dark .reference-list__file {
  color: var(--color-text);
}

.dark .reference-list__item {
  border-color: var(--color-border);
  background: var(--color-surface);
}

.dark .reference-list__item:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-soft-bg);
}

.dark .reference-list__meta,
.dark .reference-list__snippet {
  color: var(--color-text-muted);
}

.tool-event {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: var(--color-surface);
  padding: 5px 9px;
  font-size: 12px;
  line-height: 18px;
  color: var(--color-text-muted);
}

.tool-event__name {
  font-weight: 500;
  color: var(--color-text);
}

.tool-event__status {
  color: var(--color-text-muted);
}

.tool-event--success {
  border-color: var(--color-success);
  color: var(--color-success);
}

.tool-event--failed {
  border-color: var(--color-error);
  color: var(--color-error);
}
</style>
