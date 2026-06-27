<script setup lang="ts">
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
  evidenceMode?: 'page' | 'drawer';
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
      elementType?: string | null;
      sectionTitle?: string | null;
      sectionLevel?: number | null;
      bboxJson?: string | null;
      parserName?: string | null;
      parserVersion?: string | null;
      sourceKind?: Api.Chat.ReferenceEvidence['sourceKind'];
      tableId?: string | null;
      figureId?: string | null;
      formulaId?: string | null;
      evidenceRole?: string | null;
      retrievalRoute?: string | null;
      intent?: string | null;
      rankReason?: string | null;
      tableText?: string | null;
      tableMarkdown?: string | null;
      tableScreenshotAvailable?: boolean | null;
      sourceType?: Api.Chat.ReferenceEvidence['sourceType'];
      evidenceAssetLevel?: Api.Chat.ReferenceEvidence['evidenceAssetLevel'];
      pdfEvidenceAvailable?: boolean | null;
      structuredImport?: boolean | null;
      evalImport?: boolean | null;
      pageScreenshotAvailable?: boolean | null;
      figureScreenshotAvailable?: boolean | null;
      assetWarnings?: string[] | null;
      paperTitle: string;
      originalFilename?: string | null;
      paperId?: string | null;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number;
      referenceNumber: number;
    }
  ): void;
  (e: 'selectSourceScope', payload: Api.Chat.Scope): void;
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
  Array<{
    paperTitle: string;
    originalFilename?: string | null;
    id: string;
    referenceNumber: number;
    paperId?: string;
    pageNumber?: number;
  }>
>([]);
const assistantContentRef = ref<HTMLElement | null>(null);
const activeReferenceNumber = ref<number | null>(null);
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

const toolEvents = computed(() => props.msg.toolEvents || []);
const thinkingTitle = computed(() => (props.msg.route === 'SMALLTALK' ? '正在回复' : '正在研读证据'));
const thinkingDescription = computed(() =>
  props.msg.route === 'SMALLTALK' ? '准备简短回应' : '检索片段、页码与引用来源'
);
const sourcesUsed = computed(() => {
  if (props.msg.role !== 'assistant' || !props.msg.referenceMappings) {
    return [];
  }
  const seen = new Set<string>();
  const sources: Array<{ paperId?: string; paperTitle: string; originalFilename?: string | null }> = [];
  Object.values(props.msg.referenceMappings).forEach(detail => {
    const title = (detail.paperTitle || detail.originalFilename || '').trim();
    if (!title) {
      return;
    }
    const key = detail.paperId || title;
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    sources.push({
      paperId: detail.paperId,
      paperTitle: title,
      originalFilename: detail.originalFilename
    });
  });
  return sources;
});

const sourceSetScope = computed<Api.Chat.Scope | null>(() => {
  const sources = sourcesUsed.value.filter(source => source.paperId);
  if (!sources.length) {
    return null;
  }
  return {
    paperIds: sources.map(source => source.paperId as string),
    paperTitles: sources.map(source => source.paperTitle)
  };
});

function getToolLabel(tool: string) {
  return toolNameLabels[tool] || tool;
}

function getToolStatusLabel(status: Api.Chat.AgentToolEvent['status']) {
  return toolStatusLabels[status] || status;
}

function getSourceLabel(source: { paperTitle: string; originalFilename?: string | null }) {
  return source.paperTitle || source.originalFilename || 'Untitled paper';
}

function selectSourceSet() {
  if (!sourceSetScope.value) {
    return;
  }
  emit('selectSourceScope', sourceSetScope.value);
  window.$message?.success('已将本轮来源设为提问范围');
}

function selectSingleSource(source: { paperId?: string; paperTitle: string; originalFilename?: string | null }) {
  if (!source.paperId) {
    return;
  }
  emit('selectSourceScope', {
    paperIds: [source.paperId],
    paperTitles: [source.paperTitle],
    paperId: source.paperId,
    paperTitle: source.paperTitle,
    originalFilename: source.originalFilename || undefined
  });
  window.$message?.success('已将该论文设为提问范围');
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

function createCompactCitationLink(sourceNum: string) {
  const persistedDetail = props.msg.referenceMappings?.[sourceNum];
  if (!persistedDetail?.paperId) {
    return `<span class="source-citation-chip source-citation-chip--muted" data-reference-number="${sourceNum}" title="Evidence mapping unavailable">[${sourceNum}]</span>`;
  }

  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);
  sourceFiles.value.push({
    paperTitle: persistedDetail.paperTitle,
    originalFilename: persistedDetail.originalFilename,
    id: fileId,
    referenceNumber,
    paperId: persistedDetail.paperId,
    pageNumber: persistedDetail.pageNumber ?? undefined
  });

  const title = persistedDetail.pageNumber
    ? `${persistedDetail.paperTitle} · 第${persistedDetail.pageNumber}页`
    : persistedDetail.paperTitle;

  return `<span class="source-citation-chip source-file-link" data-file-id="${fileId}" data-reference-number="${sourceNum}" tabindex="0" role="button" title="${escapeHtml(title)}">[${sourceNum}]</span>`;
}

function escapeHtml(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function processCompactCitationLinks(text: string): string {
  sourceFiles.value = [];
  return processOutsideCode(text, segment =>
    segment.replace(/(?<!!)\[(\d+)](?!\()/g, (_match, sourceNum) => {
      return createCompactCitationLink(sourceNum);
    })
  );
}

function processOutsideCode(text: string, transform: (segment: string) => string) {
  const lines = text.split(/(\n)/);
  let insideFence = false;
  return lines
    .map(line => {
      if (line === '\n') {
        return line;
      }
      if (/^\s*```/.test(line)) {
        insideFence = !insideFence;
        return line;
      }
      if (insideFence) {
        return line;
      }

      return line
        .split(/(`[^`]*`)/g)
        .map(part => (part.startsWith('`') && part.endsWith('`') ? part : transform(part)))
        .join('');
    })
    .join('');
}

function markEvidenceBlocks() {
  syncActiveEvidenceBlock();
}

function syncActiveEvidenceBlock() {
  const root = assistantContentRef.value;
  if (!root) return;

  root.querySelectorAll<HTMLElement>('.source-citation-chip--active').forEach(chip => {
    chip.classList.remove('source-citation-chip--active');
  });

  if (activeReferenceNumber.value === null) {
    return;
  }

  const activeNumber = String(activeReferenceNumber.value);
  root.querySelectorAll<HTMLElement>(`.source-citation-chip[data-reference-number="${activeNumber}"]`).forEach(chip => {
    chip.classList.add('source-citation-chip--active');
  });
}

async function refreshEvidenceBlocks() {
  await nextTick();
  markEvidenceBlocks();
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processCompactCitationLinks(rawContent));
  }

  return rawContent;
});

watch(
  () => content.value,
  () => {
    refreshEvidenceBlocks();
  },
  { immediate: true }
);

watch(activeReferenceNumber, () => {
  syncActiveEvidenceBlock();
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  if (!scope) return '';

  const clone = scope.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('.source-citation-chip').forEach(chip => chip.remove());
  const rawText = clone.textContent?.replace(/\s+/g, ' ').trim() || '';
  if (!rawText) return '';

  return rawText
    .replace(/^\s*\d+\.\s*/, '')
    .replace(/[（(]\s*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function openReferenceEvidencePage(payload: {
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  score?: number | null;
  chunkId?: number | null;
  elementType?: string | null;
  sectionTitle?: string | null;
  sectionLevel?: number | null;
  bboxJson?: string | null;
  parserName?: string | null;
  parserVersion?: string | null;
  sourceKind?: Api.Chat.ReferenceEvidence['sourceKind'];
  tableId?: string | null;
  figureId?: string | null;
  formulaId?: string | null;
  evidenceRole?: string | null;
  retrievalRoute?: string | null;
  intent?: string | null;
  rankReason?: string | null;
  tableText?: string | null;
  tableMarkdown?: string | null;
  tableScreenshotAvailable?: boolean | null;
  sourceType?: Api.Chat.ReferenceEvidence['sourceType'];
  evidenceAssetLevel?: Api.Chat.ReferenceEvidence['evidenceAssetLevel'];
  pdfEvidenceAvailable?: boolean | null;
  structuredImport?: boolean | null;
  evalImport?: boolean | null;
  pageScreenshotAvailable?: boolean | null;
  figureScreenshotAvailable?: boolean | null;
  assetWarnings?: string[] | null;
  paperTitle: string;
  originalFilename?: string | null;
  paperId?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  conversationRecordId?: number;
  referenceNumber: number;
}) {
  if (props.evidenceMode === 'drawer') {
    emit('openReference', payload);
    return;
  }

  const evidenceKey = `reference-evidence:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  localStorage.setItem(evidenceKey, JSON.stringify(payload));

  const routeLocation = router.resolve({
    path: '/chat',
    query: {
      evidence: 'reference',
      evidenceKey
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
        activeReferenceNumber.value = file.referenceNumber;
        const contextAnchorText = extractContextAnchorText(sourceTarget);
        handleSourceFileClick({
          paperTitle: file.paperTitle,
          originalFilename: file.originalFilename,
          referenceNumber: file.referenceNumber,
          paperId: file.paperId,
          pageNumber: file.pageNumber,
          anchorText: contextAnchorText
        });
      }
    }
  }
}

function handleContentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' && event.key !== ' ') {
    return;
  }

  const target = event.target as HTMLElement;
  const sourceTarget = target.closest<HTMLElement>('.source-file-link');
  if (!sourceTarget) {
    return;
  }

  event.preventDefault();
  sourceTarget.click();
}

// 处理来源证据点击事件
// 引用详情优先使用当前消息中的持久化映射，缺少证据文本时再从 MySQL 历史记录刷新。
// eslint-disable-next-line complexity
async function handleSourceFileClick(fileInfo: {
  paperTitle: string;
  originalFilename?: string | null;
  referenceNumber: number;
  paperId?: string;
  pageNumber?: number;
  anchorText?: string;
}) {
  const {
    paperTitle,
    originalFilename,
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
      openReferenceEvidencePage({
        paperTitle: persistedDetail.paperTitle || paperTitle,
        originalFilename: persistedDetail.originalFilename || originalFilename,
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
        elementType: persistedDetail.elementType,
        sectionTitle: persistedDetail.sectionTitle,
        sectionLevel: persistedDetail.sectionLevel,
        bboxJson: persistedDetail.bboxJson,
        parserName: persistedDetail.parserName,
        parserVersion: persistedDetail.parserVersion,
        sourceKind: persistedDetail.sourceKind,
        tableId: persistedDetail.tableId,
        figureId: persistedDetail.figureId,
        formulaId: persistedDetail.formulaId,
        evidenceRole: persistedDetail.evidenceRole,
        retrievalRoute: persistedDetail.retrievalRoute,
        intent: persistedDetail.intent,
        rankReason: persistedDetail.rankReason,
        tableText: persistedDetail.tableText,
        tableMarkdown: persistedDetail.tableMarkdown,
        tableScreenshotAvailable: persistedDetail.tableScreenshotAvailable,
        sourceType: persistedDetail.sourceType,
        evidenceAssetLevel: persistedDetail.evidenceAssetLevel,
        pdfEvidenceAvailable: persistedDetail.pdfEvidenceAvailable,
        structuredImport: persistedDetail.structuredImport,
        evalImport: persistedDetail.evalImport,
        pageScreenshotAvailable: persistedDetail.pageScreenshotAvailable,
        figureScreenshotAvailable: persistedDetail.figureScreenshotAvailable,
        assetWarnings: persistedDetail.assetWarnings,
        conversationRecordId,
        referenceNumber
      });
      return;
    }

    const targetPaperId = detail?.paperId || extractedPaperId || null;
    openReferenceEvidencePage({
      paperTitle: detail?.paperTitle || paperTitle,
      originalFilename: detail?.originalFilename || originalFilename,
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
      elementType: detail?.elementType,
      sectionTitle: detail?.sectionTitle,
      sectionLevel: detail?.sectionLevel,
      bboxJson: detail?.bboxJson,
      parserName: detail?.parserName,
      parserVersion: detail?.parserVersion,
      sourceKind: detail?.sourceKind,
      tableId: detail?.tableId,
      figureId: detail?.figureId,
      formulaId: detail?.formulaId,
      evidenceRole: detail?.evidenceRole,
      retrievalRoute: detail?.retrievalRoute,
      intent: detail?.intent,
      rankReason: detail?.rankReason,
      tableText: detail?.tableText,
      tableMarkdown: detail?.tableMarkdown,
      tableScreenshotAvailable: detail?.tableScreenshotAvailable,
      sourceType: detail?.sourceType,
      evidenceAssetLevel: detail?.evidenceAssetLevel,
      pdfEvidenceAvailable: detail?.pdfEvidenceAvailable,
      structuredImport: detail?.structuredImport,
      evalImport: detail?.evalImport,
      pageScreenshotAvailable: detail?.pageScreenshotAvailable,
      figureScreenshotAvailable: detail?.figureScreenshotAvailable,
      assetWarnings: detail?.assetWarnings,
      conversationRecordId,
      referenceNumber
    });
  } catch {
    window.$message?.error(`引用证据打开失败: ${paperTitle}`);
  }
}
</script>

<template>
  <div class="message-block" :class="msg.role === 'user' ? 'message-block--user' : 'message-block--assistant'">
    <div v-if="msg.role === 'user'" class="message-heading">
      <NAvatar class="user-avatar">
        <SvgIcon icon="lucide:user-round" class="text-icon-large color-white" />
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
        <icon-lucide:loader-circle v-if="event.status === 'executing'" class="text-4" />
        <icon-lucide:check-circle v-else-if="event.status === 'success'" class="text-4" />
        <icon-lucide:alert-circle v-else class="text-4" />
        <span class="tool-event__name">{{ getToolLabel(event.tool) }}</span>
        <span class="tool-event__status">{{ getToolStatusLabel(event.status) }}</span>
      </div>
    </div>
    <div
      v-if="msg.status === 'pending' || (msg.status === 'loading' && msg.role === 'assistant' && !msg.content)"
      class="assistant-thinking"
      aria-live="polite"
    >
      <div class="assistant-thinking__mark" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <div class="assistant-thinking__copy">
        <strong>{{ thinkingTitle }}</strong>
        <span>{{ thinkingDescription }}</span>
      </div>
    </div>
    <NText v-else-if="msg.status === 'error'" class="message-error">
      {{ msg.content || '服务器繁忙，请稍后再试' }}
    </NText>
    <div
      v-else-if="msg.role === 'assistant'"
      ref="assistantContentRef"
      class="assistant-content message-content"
      @click="handleContentClick"
      @keydown="handleContentKeydown"
    >
      <VueMarkdownIt :content="content" />
    </div>
    <div v-if="sourcesUsed.length" class="sources-used">
      <span>Sources used</span>
      <div>
        <button
          v-for="source in sourcesUsed"
          :key="source.paperId || source.paperTitle"
          type="button"
          :disabled="!source.paperId"
          :title="source.paperId ? 'Use this paper as scope' : 'Source id unavailable'"
          @click="selectSingleSource(source)"
        >
          {{ getSourceLabel(source) }}
        </button>
      </div>
      <button
        v-if="sourceSetScope"
        type="button"
        class="sources-used__set-action"
        title="Use all sources from this answer as the next question scope"
        @click="selectSourceSet"
      >
        <icon-lucide:quote class="text-13px" />
        Use sources
      </button>
    </div>
    <NText v-else-if="msg.role === 'user'" class="message-content user-content">{{ content }}</NText>
    <NDivider class="message-divider" />
    <div class="message-actions">
      <NButton quaternary title="复制回答" aria-label="复制回答" @click="handleCopy(msg.content)">
        <template #icon>
          <icon-lucide:copy />
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
          <icon-lucide:thumbs-up />
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
          <icon-lucide:thumbs-down />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  cursor: pointer;
}

:deep(.source-citation-chip) {
  display: inline-flex;
  min-width: 24px;
  height: 22px;
  align-items: center;
  justify-content: center;
  margin: 0 2px;
  border: 1px solid color-mix(in srgb, #b7791f 36%, var(--color-border));
  border-radius: 999px;
  background: color-mix(in srgb, #f6d58a 22%, var(--color-surface));
  box-shadow: 0 1px 0 rgb(146 91 10 / 8%);
  color: #8a5a12;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  text-decoration: none;
  vertical-align: 0.08em;
  transition:
    background 0.16s ease,
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    color 0.16s ease,
    transform 0.16s ease;

  &:hover {
    border-color: color-mix(in srgb, #b7791f 64%, var(--color-border));
    background: color-mix(in srgb, #f6d58a 34%, var(--color-surface));
    box-shadow: 0 2px 6px rgb(146 91 10 / 14%);
    color: #6f4304;
    text-decoration: none;
    transform: translateY(-1px);
  }

  &:focus-visible {
    outline: 2px solid color-mix(in srgb, #d6a84f 58%, transparent);
    outline-offset: 2px;
  }
}

:deep(.source-citation-chip--active) {
  border-color: #b7791f;
  background: color-mix(in srgb, #f2c464 46%, var(--color-surface));
  color: #613a05;
}

:deep(.source-citation-chip--muted) {
  cursor: default;
  border-color: var(--color-border);
  background: var(--color-surface-alt);
  box-shadow: none;
  color: var(--color-text-muted);
}

.dark :deep(.source-citation-chip) {
  border-color: rgb(246 213 138 / 34%);
  background: rgb(246 213 138 / 12%);
  color: #f1cf83;
}

.dark :deep(.source-citation-chip--active) {
  border-color: rgb(246 213 138 / 72%);
  background: rgb(246 213 138 / 20%);
  color: #ffe4a3;
}

.dark :deep(.source-citation-chip--muted) {
  border-color: var(--color-border);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
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

.message-error {
  margin-left: 52px;
  margin-top: 8px;
  color: var(--color-error);
  font-style: italic;
}

.sources-used {
  margin-left: 52px;
  display: flex;
  max-width: min(860px, calc(100% - 52px));
  align-items: flex-start;
  gap: 8px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 18px;
}

.sources-used > span {
  flex: 0 0 auto;
  font-weight: 650;
}

.sources-used > div {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
}

.sources-used > div > button,
.sources-used__set-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 280px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  padding: 1px 8px;
  color: var(--color-text-muted);
  cursor: pointer;
  font: inherit;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease,
    color 0.15s ease;
}

.sources-used > div > button:hover,
.sources-used__set-action:hover {
  border-color: color-mix(in srgb, var(--color-primary) 48%, var(--color-border));
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.sources-used > div > button:disabled {
  cursor: default;
  opacity: 0.62;
}

.sources-used > div > button:disabled:hover {
  border-color: var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
}

.sources-used__set-action {
  flex: 0 0 auto;
  max-width: none;
  color: var(--color-primary);
  font-weight: 650;
}

.message-block--assistant .message-author {
  color: var(--color-primary);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
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
  background: var(--color-surface-alt);
  padding: 10px 14px;
}

.assistant-content :deep(p) {
  margin: 0 0 12px;
}

.assistant-thinking {
  position: relative;
  display: inline-flex;
  width: fit-content;
  max-width: min(520px, calc(100% - 52px));
  align-items: center;
  gap: 12px;
  overflow: hidden;
  margin-left: 52px;
  margin-top: 4px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 10px 13px;
  box-shadow: 0 8px 24px rgb(15 23 42 / 6%);
}

.assistant-thinking::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 0%, rgb(255 255 255 / 10%) 42%, transparent 68%);
  content: '';
  pointer-events: none;
  transform: translateX(-120%);
  animation: evidence-sheen 2.4s ease-in-out infinite;
}

.assistant-thinking__mark {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(3, 4px);
  gap: 4px;
  align-items: end;
  justify-content: center;
  width: 28px;
  height: 24px;
  border-radius: 6px;
  background: var(--color-primary-soft-bg);
}

.assistant-thinking__mark span {
  display: block;
  width: 4px;
  border-radius: 999px;
  background: var(--color-primary);
  opacity: 0.5;
  animation: evidence-pulse 1.35s ease-in-out infinite;
}

.assistant-thinking__mark span:nth-child(1) {
  height: 10px;
}

.assistant-thinking__mark span:nth-child(2) {
  height: 17px;
  animation-delay: 0.15s;
}

.assistant-thinking__mark span:nth-child(3) {
  height: 13px;
  animation-delay: 0.3s;
}

.assistant-thinking__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.assistant-thinking__copy strong {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 750;
  line-height: 18px;
}

.assistant-thinking__copy span {
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 17px;
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
  background: var(--color-surface-alt);
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

.tool-event--executing .text-4 {
  animation: tool-breathe 1.2s ease-in-out infinite;
}

.tool-event--success {
  border-color: var(--color-success);
  color: var(--color-success);
}

.tool-event--failed {
  border-color: var(--color-error);
  color: var(--color-error);
}

/* Override VuePress-style markdown theme variables inside chat responses
   so the AI response area matches our modern flat token system. */
.assistant-content :deep(.vp-doc) {
  --vp-c-bg: transparent;
  --vp-c-bg-soft: var(--color-surface-alt);
  --vp-c-bg-soft-up: var(--color-bg);
  --vp-c-text-1: var(--color-text);
  --vp-c-text-2: var(--color-text-muted);
  --vp-c-text-3: var(--color-text-muted);
  --vp-c-text-code: var(--color-primary);
  --vp-c-divider: var(--color-border);
  --vp-c-border: var(--color-border);
  --vp-c-mute: var(--color-surface-alt);
  --vp-c-mute-light: var(--color-surface-alt);
  --vp-c-mute-dark: var(--color-surface-alt);
  --vp-c-mute-darker: var(--color-surface-alt);
  --vp-c-brand: var(--color-primary);
  --vp-c-brand-lighter: var(--color-primary-soft-bg);
  --vp-c-brand-dark: var(--color-primary-hover);
  --vp-c-brand-darker: var(--color-primary-hover);
  --vp-c-neutral: var(--color-text);
  --vp-header-bg: var(--color-primary);
  --vp-code-block-bg: var(--color-surface-alt);
  --vp-code-block-color: var(--color-text);
  --vp-code-line-number-color: var(--color-text-muted);
  --vp-code-line-highlight-color: var(--color-primary-soft-bg);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
}

@keyframes evidence-pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scaleY(0.72);
  }

  45% {
    opacity: 0.95;
    transform: scaleY(1);
  }
}

@keyframes evidence-sheen {
  0%,
  35% {
    transform: translateX(-120%);
  }

  75%,
  100% {
    transform: translateX(120%);
  }
}

@keyframes tool-breathe {
  0%,
  100% {
    opacity: 0.45;
    transform: scale(0.92);
  }

  50% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>
