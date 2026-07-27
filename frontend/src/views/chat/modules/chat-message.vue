<script setup lang="ts">
import { defineAsyncComponent, nextTick } from 'vue';
import { router } from '@/router';
import { request } from '@/service/request';
import StreamingMarkdown from '@/components/custom/streaming-markdown.vue';
import ProductReadingPaperChoiceList from './product-reading-paper-choice-list.vue';

const VueMarkdownIt = defineAsyncComponent(() =>
  import('@/vendor/vue-markdown-shiki').then(module => module.VueMarkdownIt)
);
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message;
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
      pageScreenshotAvailable?: boolean | null;
      figureScreenshotAvailable?: boolean | null;
      assetWarnings?: string[] | null;
      visualRegions?: Api.Chat.EvidenceVisualRegion[] | null;
      paperTitle: string;
      originalFilename?: string | null;
      paperId?: string | null;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number;
      referenceNumber: number;
      sourceQuoteRef?: string | null;
    }
  ): void;
  (e: 'openProcess', message: Api.Chat.Message): void;
}>();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();
const retrying = ref(false);
const revisionModalVisible = ref(false);
const revisionsLoading = ref(false);
const answerRevisions = ref<Api.Chat.AnswerRevision[]>([]);
const canRetryAnswer = computed(
  () =>
    props.msg.role === 'assistant' &&
    props.msg.status === 'finished' &&
    Boolean(props.msg.generationId && props.msg.conversationRecordId)
);
const canOpenAnswerRevisions = computed(
  () => props.msg.role === 'assistant' && Boolean(props.msg.answerSlotId && (props.msg.answerRevision || 1) > 1)
);

async function handleRetry() {
  if (retrying.value || !canRetryAnswer.value) return;
  retrying.value = true;
  try {
    const started = await chatStore.retryGeneration(props.msg);
    if (!started) {
      window.$message?.error('重新生成失败，请稍后再试');
    }
  } finally {
    retrying.value = false;
  }
}

async function openAnswerRevisions() {
  if (!props.msg.answerSlotId || revisionsLoading.value) return;
  revisionModalVisible.value = true;
  revisionsLoading.value = true;
  answerRevisions.value = [];
  try {
    const { error, data } = await request<Api.Chat.AnswerRevision[]>({
      url: `chat/answer-slots/${props.msg.answerSlotId}/revisions`
    });
    if (error || !Array.isArray(data)) {
      window.$message?.error('版本历史加载失败');
      return;
    }
    answerRevisions.value = data;
  } finally {
    revisionsLoading.value = false;
  }
}

function revisionReferenceEntries(revision: Api.Chat.AnswerRevision) {
  const mappings = revision.referenceMappings || {};
  return Object.entries(mappings)
    .map(([key, detail]) => ({
      referenceNumber: Number(key),
      detail
    }))
    .filter(item => Number.isFinite(item.referenceNumber));
}

function openRevisionReference(
  revision: Api.Chat.AnswerRevision,
  referenceNumber: number,
  detail: Api.Chat.ReferenceEvidence
) {
  const paperTitle = detail.paperTitle || detail.originalFilename || detail.paperId || `Reference #${referenceNumber}`;
  revisionModalVisible.value = false;
  emit('openReference', {
    retrievalMode: detail.retrievalMode,
    retrievalLabel: detail.retrievalLabel,
    retrievalQuery: detail.retrievalQuery,
    evidenceSnippet: detail.evidenceSnippet,
    matchedChunkText: detail.matchedChunkText,
    score: detail.score,
    chunkId: detail.chunkId,
    elementType: detail.elementType,
    sectionTitle: detail.sectionTitle,
    sectionLevel: detail.sectionLevel,
    bboxJson: detail.bboxJson,
    parserName: detail.parserName,
    parserVersion: detail.parserVersion,
    sourceKind: detail.sourceKind,
    tableId: detail.tableId,
    figureId: detail.figureId,
    formulaId: detail.formulaId,
    evidenceRole: detail.evidenceRole,
    retrievalRoute: detail.retrievalRoute,
    intent: detail.intent,
    rankReason: detail.rankReason,
    tableText: detail.tableText,
    tableMarkdown: detail.tableMarkdown,
    tableScreenshotAvailable: detail.tableScreenshotAvailable,
    sourceType: detail.sourceType,
    evidenceAssetLevel: detail.evidenceAssetLevel,
    pdfEvidenceAvailable: detail.pdfEvidenceAvailable,
    pageScreenshotAvailable: detail.pageScreenshotAvailable,
    figureScreenshotAvailable: detail.figureScreenshotAvailable,
    assetWarnings: detail.assetWarnings,
    visualRegions: detail.visualRegions,
    paperTitle,
    originalFilename: detail.originalFilename,
    paperId: detail.paperId,
    pageNumber: detail.pageNumber,
    anchorText: detail.anchorText,
    conversationRecordId: revision.conversationRecordId,
    referenceNumber,
    sourceQuoteRef: detail.sourceQuoteRef
  });
}

type MessageSourceFile = {
  paperTitle: string;
  originalFilename?: string | null;
  id: string;
  referenceNumber: number;
  paperId?: string;
  pageNumber?: number;
  sourceQuoteRef?: string | null;
};

// Stored citation identities drive the inline citation controls.
const sourceFiles = ref<MessageSourceFile[]>([]);
const assistantContentRef = ref<HTMLElement | null>(null);
const activeReferenceNumber = ref<number | null>(null);
// eslint-disable-next-line no-useless-escape
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+/g;
const researchEvents = computed(() => props.msg.researchEvents || []);
const INLINE_PROGRESS_EVENT_LIMIT = 4;
const INLINE_PROGRESS_LOOKBACK_LIMIT = 80;
const assistantIsRunning = computed(
  () =>
    props.msg.role === 'assistant' &&
    (props.msg.status === 'pending' || (props.msg.status === 'loading' && !props.msg.content))
);
const assistantIsStreaming = computed(
  () => props.msg.role === 'assistant' && ['pending', 'loading'].includes(props.msg.status || '')
);
// Keep the thinking animation alive across session switches and unmounts.
// Once events have arrived for this message and answer_completed hasn't,
// the research is still effectively running from the user's perspective —
// even if upstream status transiently dropped to 'success'.
const researchFinished = computed(() => researchEvents.value.some(e => (e.eventType || e.type) === 'answer_completed'));
const assistantIsGenerating = computed(
  () =>
    props.msg.role === 'assistant' &&
    !researchFinished.value &&
    (['pending', 'loading'].includes(props.msg.status || '') || researchEvents.value.length > 0)
);
const showMessageActions = computed(() => !assistantIsRunning.value && Boolean((props.msg.content || '').trim()));
const hasReadingArtifacts = computed(() => {
  const artifacts = props.msg.readingArtifacts;
  if (!artifacts) return false;
  return Boolean(
    artifacts.goalCard?.interpretedGoal ||
      artifacts.goalCard?.scopeLabel ||
      typeof artifacts.goalCard?.readablePaperCount === 'number' ||
      artifacts.paperShortlist?.items?.length ||
      artifacts.readingPlan?.steps?.length ||
      artifacts.claimEvidencePanel?.rows?.length ||
      artifacts.missingEvidence?.explanation ||
      artifacts.uncertaintyNotes?.length
  );
});
const showResearchDetails = computed(
  () =>
    props.msg.role === 'assistant' &&
    (assistantIsGenerating.value ||
      researchEvents.value.length > 0 ||
      hasReadingArtifacts.value ||
      Boolean(props.msg.researchAuditTrail?.steps?.length || props.msg.researchAuditTrail?.evidence?.length))
);

type ProgressEventState = 'running' | 'completed' | 'failed';

interface InlineProgressItem {
  key: string;
  title: string;
  detail: string;
  state: ProgressEventState;
}

function progressEventTitle(event: Api.Chat.ResearchProgressEvent) {
  const eventType = event.eventType || event.type;
  if (eventType === 'job_started') return 'Research started';
  if (eventType === 'model_call_started')
    return event.attempt && event.attempt > 1 ? `Thinking · pass ${event.attempt}` : 'Thinking';
  if (eventType === 'model_call_completed') return `Completed model pass ${event.attempt || ''}`.trim();
  if (eventType === 'answer_completed') return 'Preparing the answer';
  if (eventType === 'job_failed') return 'Research failed';
  if (eventType === 'job_cancelled') return 'Research cancelled';
  if (eventType === 'tool_started') return toolActionLabel(event.tool, true);
  if (eventType === 'tool_completed') return toolActionLabel(event.tool, false);
  return 'Research progress';
}

function toolActionLabel(tool?: string, running = false) {
  const labels: Record<string, [string, string]> = {
    search_paper_candidates: ['Searching papers', 'Searched papers'],
    find_reading_locations: ['Finding relevant locations', 'Found relevant locations'],
    read_locations: ['Reading paper locations', 'Read paper locations'],
    get_citation_edges: ['Tracing citations', 'Traced citations'],
    get_research_skill: ['Loading research guidance', 'Loaded research guidance']
  };
  const pair = labels[tool || ''];
  if (pair) return running ? pair[0] : pair[1];
  return running ? `Running ${tool || 'tool'}` : `Completed ${tool || 'tool'}`;
}

// Runtime events are intentionally rendered by tool-specific deterministic branches.
// eslint-disable-next-line complexity
function progressEventDetail(event: Api.Chat.ResearchProgressEvent) {
  const input = event.input || {};
  const output = event.output || {};
  const eventType = event.eventType || event.type;
  if (eventType === 'model_call_completed') {
    const tokens = Number(event.usage?.totalTokens || 0);
    return [event.durationMs ? `${event.durationMs} ms` : '', tokens ? `${tokens.toLocaleString()} tokens` : '']
      .filter(Boolean)
      .join(' · ');
  }
  if (eventType === 'job_failed') {
    return event.errorType ? `${event.errorType}: ${event.message || 'Research failed'}` : event.message || '';
  }
  if (event.tool === 'search_paper_candidates') {
    const query = String(input.query || '').trim();
    const count = Number(output.resultCount || 0);
    return eventType === 'tool_completed'
      ? `${count} paper${count === 1 ? '' : 's'} returned${query ? ` for “${query}”` : ''}`
      : query || 'Searching the selected paper set';
  }
  if (event.tool === 'find_reading_locations') {
    const query = String(input.query || '').trim();
    const count = Number(output.resultCount || 0);
    return eventType === 'tool_completed'
      ? `${count} location${count === 1 ? '' : 's'} found`
      : query || 'Locating relevant sections and pages';
  }
  if (event.tool === 'read_locations') {
    const count = Number(output.readCount || input.locationCount || 0);
    const evidenceCount = Number(output.evidenceCount || 0);
    const pages = Array.isArray(output.pages) ? output.pages.join(', ') : '';
    if (eventType === 'tool_completed') {
      return `${count} location${count === 1 ? '' : 's'} read · ${evidenceCount} evidence passage${evidenceCount === 1 ? '' : 's'}${pages ? ` · pages ${pages}` : ''}`;
    }
    return `${count} location${count === 1 ? '' : 's'} selected`;
  }
  if (event.tool === 'get_citation_edges') return `${Number(output.edgeCount || 0)} citation edges`;
  if (event.tool === 'get_research_skill') return String(input.skillId || output.skillId || '');
  return '';
}

function progressEventState(event: Api.Chat.ResearchProgressEvent): ProgressEventState {
  const eventType = event.eventType || event.type;
  if (eventType === 'job_failed' || event.status === 'failed') return 'failed';
  if (eventType === 'tool_started' || eventType === 'model_call_started') return 'running';
  return 'completed';
}

function inlineProgressIdentity(event: Api.Chat.ResearchProgressEvent) {
  const eventType = event.eventType || event.type || 'progress';
  const input = event.input || {};
  const inputHandle = String(input.query || input.locationRef || input.skillId || input.locationCount || '').trim();
  if (event.tool) return ['tool', event.tool, inputHandle || event.attempt || ''].join(':');
  if (eventType.startsWith('model_call')) return ['model', event.attempt || 1].join(':');
  return eventType;
}

function buildInlineProgressItems(events: Api.Chat.ResearchProgressEvent[]): InlineProgressItem[] {
  const compacted: Api.Chat.ResearchProgressEvent[] = [];
  const identityIndexes = new Map<string, number>();

  events.slice(-INLINE_PROGRESS_LOOKBACK_LIMIT).forEach(event => {
    const identity = inlineProgressIdentity(event);
    const existingIndex = identityIndexes.get(identity);
    if (existingIndex === undefined) {
      identityIndexes.set(identity, compacted.length);
      compacted.push(event);
      return;
    }
    compacted[existingIndex] = event;
  });

  return compacted.slice(-INLINE_PROGRESS_EVENT_LIMIT).map((event, index) => {
    const sequence = Number(event.sequence || 0);
    const key = sequence > 0 ? String(sequence) : `${inlineProgressIdentity(event)}:${index}`;
    return {
      key,
      title: progressEventTitle(event),
      detail: progressEventDetail(event),
      state: progressEventState(event)
    };
  });
}

const inlineProgressItems = computed(() => buildInlineProgressItems(researchEvents.value));
const showInlineProgress = computed(
  () => props.msg.role === 'assistant' && assistantIsGenerating.value && inlineProgressItems.value.length > 0
);

const currentProgressText = computed(() => {
  const latest = researchEvents.value[researchEvents.value.length - 1];
  if (!latest) return 'Thinking';
  const detail = progressEventDetail(latest);
  return detail ? `${progressEventTitle(latest)} · ${detail}` : progressEventTitle(latest);
});

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

// Citation fallback priority mirrors the persisted reference contract.
// eslint-disable-next-line complexity
function createCompactCitationLink(sourceNum: string) {
  const persistedDetail = props.msg.referenceMappings?.[sourceNum];
  const sourceQuoteRef = persistedDetail?.sourceQuoteRef?.trim();
  if (!persistedDetail?.paperId && !sourceQuoteRef) {
    return `[${sourceNum}]`;
  }

  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = Number.parseInt(sourceNum, 10);
  const paperTitle =
    persistedDetail?.paperTitle ||
    persistedDetail?.originalFilename ||
    persistedDetail?.evidenceSnippet ||
    persistedDetail?.matchedChunkText ||
    'Evidence';
  sourceFiles.value.push({
    paperTitle,
    originalFilename: persistedDetail?.originalFilename,
    id: fileId,
    referenceNumber,
    paperId: persistedDetail?.paperId || undefined,
    pageNumber: persistedDetail?.pageNumber ?? undefined,
    sourceQuoteRef
  });

  if (!persistedDetail?.paperId && sourceQuoteRef) {
    const title = persistedDetail?.evidenceSnippet || persistedDetail?.matchedChunkText || 'Evidence';
    return `<span class="source-citation-chip source-quote-link" data-file-id="${fileId}" data-reference-number="${sourceNum}" tabindex="0" role="button" title="${escapeHtml(title)}">[${sourceNum}]</span>`;
  }

  const title = persistedDetail?.pageNumber ? `${paperTitle} · 第${persistedDetail.pageNumber}页` : paperTitle;

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
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processCompactCitationLinks(rawContent));
  }

  return rawContent;
});

watch(
  () => (assistantIsGenerating.value ? '' : content.value),
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
  pageScreenshotAvailable?: boolean | null;
  figureScreenshotAvailable?: boolean | null;
  assetWarnings?: string[] | null;
  visualRegions?: Api.Chat.EvidenceVisualRegion[] | null;
  paperTitle: string;
  originalFilename?: string | null;
  paperId?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  conversationRecordId?: number;
  referenceNumber: number;
  sourceQuoteRef?: string | null;
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
  const sourceTarget = target.closest<HTMLElement>('.source-file-link, .source-quote-link');

  // 检查点击的是否是文件链接
  if (sourceTarget) {
    event.preventDefault();
    event.stopPropagation();

    const fileId = sourceTarget.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        const contextAnchorText = extractContextAnchorText(sourceTarget);
        openSourceFile(file, contextAnchorText);
      }
    }
  }
}

function handleContentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' && event.key !== ' ') {
    return;
  }

  const target = event.target as HTMLElement;
  const sourceTarget = target.closest<HTMLElement>('.source-file-link, .source-quote-link');
  if (!sourceTarget) {
    return;
  }

  event.preventDefault();
  sourceTarget.click();
}

function syncActiveReferenceFromContent(target: EventTarget | null) {
  const sourceTarget = (target as HTMLElement | null)?.closest<HTMLElement>('.source-file-link, .source-quote-link');
  const referenceNumber = Number(sourceTarget?.dataset.referenceNumber || 0);
  if (referenceNumber > 0) activeReferenceNumber.value = referenceNumber;
}

function openSourceFile(file: MessageSourceFile, anchorText = '') {
  activeReferenceNumber.value = file.referenceNumber;
  if (!file.paperId && file.sourceQuoteRef && !props.msg.conversationRecordId) {
    openReferenceEvidencePage({
      paperTitle: file.paperTitle,
      originalFilename: file.originalFilename,
      pageNumber: file.pageNumber,
      evidenceSnippet: file.paperTitle,
      matchedChunkText: file.paperTitle,
      referenceNumber: file.referenceNumber,
      sourceQuoteRef: file.sourceQuoteRef
    });
    return;
  }

  handleSourceFileClick({
    paperTitle: file.paperTitle,
    originalFilename: file.originalFilename,
    referenceNumber: file.referenceNumber,
    paperId: file.paperId,
    pageNumber: file.pageNumber,
    sourceQuoteRef: file.sourceQuoteRef,
    anchorText
  });
}

// 处理来源证据点击事件
// Product Reading source quotes must be re-opened through the durable reference endpoint.
// eslint-disable-next-line complexity
async function handleSourceFileClick(fileInfo: {
  paperTitle: string;
  originalFilename?: string | null;
  referenceNumber: number;
  paperId?: string;
  pageNumber?: number;
  sourceQuoteRef?: string | null;
  anchorText?: string;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
}) {
  const {
    paperTitle,
    originalFilename,
    referenceNumber,
    paperId: extractedPaperId,
    pageNumber: extractedPageNumber,
    sourceQuoteRef: extractedSourceQuoteRef,
    anchorText: clickedAnchorText
  } = fileInfo;
  const persistedDetail =
    props.msg.referenceMappings?.[String(referenceNumber)] || props.msg.referenceMappings?.[referenceNumber];
  const conversationRecordId = props.msg.conversationRecordId;
  const sourceQuoteRefForDetail = persistedDetail?.sourceQuoteRef || extractedSourceQuoteRef || '';
  const requiresAuthoritativeSourceQuote = Boolean(sourceQuoteRefForDetail && conversationRecordId);

  if (sourceQuoteRefForDetail && !conversationRecordId) {
    openReferenceEvidencePage({
      paperTitle: persistedDetail?.paperTitle || paperTitle,
      originalFilename: persistedDetail?.originalFilename || originalFilename,
      paperId: persistedDetail?.paperId || extractedPaperId,
      pageNumber: persistedDetail?.pageNumber ?? extractedPageNumber,
      anchorText: persistedDetail?.anchorText || clickedAnchorText || '',
      evidenceSnippet: persistedDetail?.evidenceSnippet || fileInfo.evidenceSnippet,
      matchedChunkText: persistedDetail?.matchedChunkText || fileInfo.matchedChunkText,
      referenceNumber,
      sourceQuoteRef: sourceQuoteRefForDetail,
      visualRegions: persistedDetail?.visualRegions
    });
    return;
  }

  try {
    let detail: Api.Paper.ReferenceDetailResponse | null = null;
    const fallbackRetrievalQuery = props.retrievalQueryFallback || '';

    if (
      conversationRecordId &&
      (requiresAuthoritativeSourceQuote ||
        !persistedDetail?.retrievalQuery ||
        !persistedDetail?.matchedChunkText ||
        !persistedDetail?.evidenceSnippet)
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
        } else if (requiresAuthoritativeSourceQuote) {
          window.$message?.error('Citation detail is unavailable for the current reading model.');
          return;
        }
      } catch {
        if (requiresAuthoritativeSourceQuote) {
          window.$message?.error('Citation detail is unavailable for the current reading model.');
          return;
        }
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
        evidenceSnippet: persistedDetail.evidenceSnippet || fileInfo.evidenceSnippet,
        matchedChunkText: persistedDetail.matchedChunkText || fileInfo.matchedChunkText,
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
        pageScreenshotAvailable: persistedDetail.pageScreenshotAvailable,
        figureScreenshotAvailable: persistedDetail.figureScreenshotAvailable,
        assetWarnings: persistedDetail.assetWarnings,
        visualRegions: persistedDetail.visualRegions,
        conversationRecordId,
        referenceNumber,
        sourceQuoteRef: persistedDetail.sourceQuoteRef || extractedSourceQuoteRef
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
      evidenceSnippet: detail?.evidenceSnippet || fileInfo.evidenceSnippet,
      matchedChunkText: detail?.matchedChunkText || fileInfo.matchedChunkText,
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
      pageScreenshotAvailable: detail?.pageScreenshotAvailable,
      figureScreenshotAvailable: detail?.figureScreenshotAvailable,
      assetWarnings: detail?.assetWarnings,
      visualRegions: detail?.visualRegions,
      conversationRecordId,
      referenceNumber,
      sourceQuoteRef: detail?.sourceQuoteRef || persistedDetail?.sourceQuoteRef || extractedSourceQuoteRef
    });
  } catch {
    window.$message?.error(`引用证据打开失败: ${paperTitle}`);
  }
}
</script>

<template>
  <div class="message-block" :class="msg.role === 'user' ? 'message-block--user' : 'message-block--assistant'">
    <div class="message-row">
      <span v-if="msg.role === 'assistant'" class="message-avatar message-avatar--assistant" aria-hidden="true">
        <SystemLogo />
      </span>

      <div class="message-body">
        <button
          v-if="assistantIsGenerating && showResearchDetails"
          type="button"
          class="assistant-live-status"
          aria-label="Open research process"
          @click="emit('openProcess', msg)"
        >
          <span class="assistant-process__dot" aria-hidden="true" />
          <span>{{ currentProgressText }}</span>
          <icon-lucide:panel-right-open />
        </button>
        <div v-if="showInlineProgress" class="assistant-progress-strip" aria-live="polite">
          <div
            v-for="event in inlineProgressItems"
            :key="event.key"
            class="assistant-progress-row"
            :class="`assistant-progress-row--${event.state}`"
          >
            <span class="assistant-progress-row__dot" aria-hidden="true" />
            <div class="assistant-progress-row__body">
              <span class="assistant-progress-row__title">{{ event.title }}</span>
              <span v-if="event.detail" class="assistant-progress-row__detail">{{ event.detail }}</span>
            </div>
          </div>
        </div>
        <NText v-if="msg.status === 'error'" class="message-error">
          {{ msg.content || '服务器繁忙，请稍后再试' }}
        </NText>
        <div
          v-else-if="msg.role === 'assistant' && msg.content"
          ref="assistantContentRef"
          class="assistant-content message-content"
          @click="handleContentClick"
          @keydown="handleContentKeydown"
          @mouseover="syncActiveReferenceFromContent($event.target)"
          @focusin="syncActiveReferenceFromContent($event.target)"
        >
          <StreamingMarkdown v-if="assistantIsStreaming" :content="msg.content" />
          <VueMarkdownIt v-else :content="content" />
        </div>
        <NText v-else-if="msg.role === 'user'" class="message-content user-content">{{ content }}</NText>
        <ProductReadingPaperChoiceList
          v-if="msg.role === 'assistant' && msg.productStateItems?.length && !hasReadingArtifacts"
          :items="msg.productStateItems"
        />
        <div v-if="showMessageActions" class="message-actions">
          <NButton
            v-if="showResearchDetails"
            quaternary
            title="Research process"
            aria-label="Research process"
            @click="emit('openProcess', msg)"
          >
            <template #icon>
              <icon-lucide:list-tree />
            </template>
          </NButton>
          <NButton quaternary title="复制回答" aria-label="复制回答" @click="handleCopy(msg.content)">
            <template #icon>
              <icon-lucide:copy />
            </template>
          </NButton>
          <NButton
            v-if="canOpenAnswerRevisions"
            quaternary
            :loading="revisionsLoading"
            title="版本历史"
            aria-label="版本历史"
            @click="openAnswerRevisions"
          >
            <template #icon>
              <icon-lucide:history />
            </template>
          </NButton>
          <NButton
            v-if="canRetryAnswer"
            quaternary
            :loading="retrying"
            title="重新生成"
            aria-label="重新生成"
            @click="handleRetry"
          >
            <template #icon>
              <icon-lucide:refresh-cw />
            </template>
          </NButton>
        </div>
      </div>
    </div>
    <NModal v-model:show="revisionModalVisible" preset="card" title="版本历史" class="answer-revision-modal">
      <NSpin :show="revisionsLoading">
        <div class="answer-revisions">
          <section v-for="revision in answerRevisions" :key="revision.conversationRecordId" class="answer-revision">
            <div class="answer-revision__meta">
              <span>v{{ revision.answerRevision }}</span>
              <span v-if="revision.currentRevision">当前</span>
              <span v-if="revision.forkedFromConversationRecordId">
                forked from #{{ revision.forkedFromConversationRecordId }}
              </span>
              <span v-if="revision.timestamp">{{ revision.timestamp }}</span>
            </div>
            <div class="answer-revision__content">
              <VueMarkdownIt :content="revision.answer || ''" />
            </div>
            <div v-if="revisionReferenceEntries(revision).length" class="answer-revision__refs">
              <NButton
                v-for="entry in revisionReferenceEntries(revision)"
                :key="entry.referenceNumber"
                size="small"
                quaternary
                @click="openRevisionReference(revision, entry.referenceNumber, entry.detail)"
              >
                [{{ entry.referenceNumber }}]
              </NButton>
            </div>
          </section>
        </div>
      </NSpin>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link),
:deep(.source-quote-link) {
  cursor: pointer;
}

:deep(.source-citation-chip) {
  display: inline-flex;
  min-width: 24px;
  height: 22px;
  align-items: center;
  justify-content: center;
  margin: 0 2px;
  border: 1px solid color-mix(in srgb, var(--color-citation) 42%, var(--color-border));
  border-radius: 999px;
  background: var(--color-citation-soft-bg);
  color: var(--color-citation);
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  text-decoration: none;
  vertical-align: 0.08em;
  transition:
    background 0.16s ease,
    border-color 0.16s ease,
    color 0.16s ease;

  &:hover {
    border-color: color-mix(in srgb, var(--color-citation) 72%, var(--color-border));
    background: color-mix(in srgb, var(--color-citation) 18%, var(--color-citation-soft-bg));
    color: var(--color-citation-hover);
    text-decoration: none;
  }

  &:focus-visible {
    outline: 2px solid color-mix(in srgb, var(--color-citation) 58%, transparent);
    outline-offset: 2px;
  }
}

:deep(.source-citation-chip--active) {
  border-color: var(--color-citation);
  background: color-mix(in srgb, var(--color-citation) 24%, var(--color-citation-soft-bg));
  color: var(--color-citation-hover);
}

:deep(.source-citation-chip--muted) {
  cursor: default;
  border-color: var(--color-border);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
}

.message-block {
  display: flex;
  width: 100%;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--color-border-soft);
  padding-bottom: 20px;
}

.message-block--user {
  justify-content: flex-start;
  padding-left: 42px;
}

.message-block--assistant {
  justify-content: flex-start;
}

.message-row {
  display: flex;
  width: 100%;
  max-width: 100%;
  align-items: flex-start;
  gap: 10px;
}

.message-block--assistant .message-row {
  width: 100%;
  justify-content: flex-start;
}

.message-block--user .message-row {
  width: min(var(--reading-prose-width), calc(100% - 42px));
  max-width: min(760px, 92%);
  justify-content: flex-end;
}

.message-avatar {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-elevated);
  box-shadow:
    0 1px 0 color-mix(in srgb, var(--color-text) 8%, transparent) inset,
    var(--shadow-card);
  color: var(--color-text-muted);
}

.message-avatar :deep(svg) {
  width: 18px;
  height: 18px;
}

.message-avatar--assistant {
  background: var(--color-surface);
  color: var(--color-primary);
}

.message-body {
  display: flex;
  min-width: 0;
  max-width: calc(100% - 42px);
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.message-block--assistant .message-body {
  width: min(var(--reading-prose-width), calc(100% - 42px));
  align-items: flex-start;
}

.message-block--user .message-body {
  max-width: 100%;
  align-items: flex-end;
}

.message-content {
  max-width: 100%;
  font-size: 15px;
  line-height: 1.78;
  color: var(--color-text);
}

.message-error {
  max-width: 100%;
  color: var(--color-error);
  font-style: italic;
}

.user-content {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  white-space: pre-wrap;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-alt);
  padding: 10px 13px;
}

.assistant-content {
  width: 100%;
  border: 0;
  background: transparent;
  padding: 2px 0 0;
}

.assistant-content :deep(p) {
  margin: 0 0 12px;
}

.assistant-content :deep(.research-preformatted-block) {
  display: block;
  overflow-x: auto;
  border: 1px solid var(--color-border-soft);
  border-radius: 6px;
  background: var(--color-surface-alt);
  margin: 0 0 12px;
  padding: 12px 14px;
  color: var(--color-text);
  font-family: var(--font-utility);
  font-size: 14px;
  line-height: 1.5;
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  tab-size: 4;
}

.assistant-content :deep(.research-preformatted-block),
.assistant-content :deep(.research-preformatted-block *) {
  color: var(--color-text) !important;
}

.message-actions {
  display: flex;
  width: auto;
  justify-content: flex-end;
  gap: 8px;
  transition: opacity 0.16s ease;
}

.message-block--assistant .message-actions {
  justify-content: flex-start;
}

.answer-revision-modal {
  max-width: min(920px, calc(100vw - 32px));
}

.answer-revisions {
  display: grid;
  max-height: min(70vh, 720px);
  gap: 12px;
  overflow: auto;
}

.answer-revision {
  border: 1px solid var(--color-border-soft);
  border-radius: 8px;
  padding: 12px;
  background: var(--color-surface);
}

.answer-revision__meta,
.answer-revision__refs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.answer-revision__meta {
  margin-bottom: 8px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.answer-revision__content {
  color: var(--color-text);
}

.answer-revision__refs {
  margin-top: 8px;
}

@media (hover: hover) and (pointer: fine) {
  .message-actions {
    opacity: 0;
    pointer-events: none;
  }

  .message-block:hover .message-actions,
  .message-block:focus-within .message-actions {
    opacity: 1;
    pointer-events: auto;
  }
}

.dark .message-content {
  color: var(--color-text);
}

.dark .user-content {
  border-color: var(--color-border);
  background: var(--color-surface-alt);
}

.dark .message-avatar {
  border-color: var(--color-border);
  background: var(--color-surface-elevated);
  box-shadow: var(--shadow-card);
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
  --vp-c-mute-light: var(--color-text-muted);
  --vp-c-mute-dark: var(--color-surface-alt);
  --vp-c-mute-darker: var(--color-surface-alt);
  --vp-c-brand: var(--color-primary);
  --vp-c-brand-lighter: var(--color-primary-soft-bg);
  --vp-c-brand-dark: var(--color-primary-hover);
  --vp-c-brand-darker: var(--color-primary-hover);
  --vp-c-neutral: var(--color-text);
  --vp-header-bg: var(--color-surface-elevated);
  --vp-code-block-bg: var(--color-surface-alt);
  --vp-code-block-color: var(--color-text);
  --vp-code-line-number-color: var(--color-text-muted);
  --vp-code-line-highlight-color: var(--color-primary-soft-bg);
  background: transparent !important;
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
}

.assistant-content :deep(.vp-doc > body) {
  background: transparent;
}

.assistant-content :deep(.vp-header) {
  border: 1px solid var(--color-border-soft);
  border-bottom: 0;
}

.assistant-content :deep(.vp-doc div[class*='language-']),
.assistant-content :deep(.vp-doc pre) {
  border: 1px solid var(--color-border-soft);
  background: var(--color-surface-alt) !important;
}

.assistant-progress-strip {
  display: grid;
  width: 100%;
  gap: 4px;
  overflow: hidden;
  border-left: 1px solid color-mix(in srgb, var(--color-primary) 22%, var(--color-border));
  padding: 1px 0 1px 10px;
}

.assistant-progress-row {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  align-items: start;
  gap: 8px;
  min-height: 24px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.assistant-progress-row__dot {
  width: 6px;
  height: 6px;
  margin-top: 6px;
  border-radius: 999px;
  background: var(--color-border);
}

.assistant-progress-row--running .assistant-progress-row__dot {
  background: var(--color-primary);
  animation: runner-dot 1.2s ease-in-out infinite;
}

.assistant-progress-row--completed .assistant-progress-row__dot {
  background: var(--color-success);
}

.assistant-progress-row--failed .assistant-progress-row__dot {
  background: var(--color-error);
}

.assistant-progress-row__body {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 4px 8px;
}

.assistant-progress-row__title {
  color: var(--color-text);
  font-weight: 650;
}

.assistant-progress-row__detail {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-live-status {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 4px 0;
  font-size: 12px;
  text-align: left;
}

.assistant-live-status:hover {
  color: var(--color-primary);
}

.assistant-live-status .assistant-process__dot {
  flex: 0 0 auto;
  background: var(--color-primary);
  animation: runner-dot 1.2s ease-in-out infinite;
}

.assistant-live-status svg {
  width: 14px;
  height: 14px;
  flex: 0 0 auto;
}

.assistant-process__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--color-text-muted);
  opacity: 0.58;
}

@keyframes runner-dot {
  0%,
  100% {
    opacity: 0.42;
  }

  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .assistant-live-status .assistant-process__dot,
  .assistant-progress-row--running .assistant-progress-row__dot {
    animation: none;
  }
}

@media (max-width: 640px) {
  .message-block--user {
    padding-left: 0;
  }

  .message-row,
  .message-block--assistant .message-row {
    width: 100%;
    max-width: 100%;
  }

  .message-avatar {
    width: 28px;
    height: 28px;
  }

  .message-avatar :deep(svg) {
    width: 16px;
    height: 16px;
  }

  .message-body,
  .message-block--assistant .message-body {
    width: calc(100% - 38px);
    max-width: calc(100% - 38px);
  }

  .message-block--user .message-body {
    width: 100%;
    max-width: 100%;
  }

  .message-row {
    flex-wrap: wrap;
  }

  .message-content,
  .message-error,
  .user-content,
  .assistant-content,
  .message-actions {
    width: 100%;
    max-width: 100%;
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
