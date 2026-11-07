<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { NButton, NCheckbox, NDrawer, NDrawerContent, NEmpty, NInput, NSpin, NTag } from 'naive-ui';
import {
  cancelDiscoveryAgentTurn,
  clearDiscoveryAgentChat,
  createDiscoveryAgentMemory,
  deleteDiscoveryAgentMemory,
  fetchDiscoveryAgentChat,
  fetchDiscoveryAgentMemory,
  importDiscoveryAgentCandidates,
  postDiscoveryAgentMessage,
  updateDiscoveryAgentMemory
} from '@/service/api';
import StreamingMarkdown from '@/components/custom/streaming-markdown.vue';

/** 智能探索聊天（PRD v3 + UX 重构）：对话主区 + 右侧候选栏。 聊天区只承载对话（模型做脉络点评，不甩清单）；候选论文全部聚合到候选栏， 入不入库由用户自决：勾选后一键入库，或单篇入库。运行中的检索实时流进候选栏。 */

defineOptions({ name: 'DiscoveryAgentChat' });
// shiki 渲染重 module（语法高亮），lazy load 减少 Discovery 页首包
const VueMarkdownIt = defineAsyncComponent(() => import('@/vendor/vue-markdown-shiki').then(m => m.VueMarkdownIt));

const POLL_INTERVAL = 2800;

const thread = ref<Api.Discovery.AgentChatThread | null>(null);
const inputText = ref('');
const sending = ref(false);
const scrollEl = ref<HTMLElement | null>(null);
const inputEl = ref<InstanceType<typeof NInput> | null>(null);
const stickBottom = ref(true);

/** 用户显式勾选的候选（正向集合：在集合中 = 已勾选） */
const checked = ref<Set<string>>(new Set());
const importingIds = ref<Set<string>>(new Set());
const candVisible = ref(true);

const memoryVisible = ref(false);
const statusVisible = ref(false);
const memoryItems = ref<Api.Discovery.AgentMemoryItem[]>([]);
const memoryLoading = ref(false);
const newMemoryKind = ref<Api.Discovery.AgentMemoryItem['kind']>('PREF');
const newMemoryContent = ref('');
const memorySaving = ref(false);

let pollTimer: ReturnType<typeof setTimeout> | null = null;
let polling = false;

const running = computed(() => Boolean(thread.value?.activeTurn));
const activeTurn = computed(() => thread.value?.activeTurn ?? null);
const messageList = computed(() => thread.value?.messages ?? []);

const windowPercent = computed(() => {
  const w = thread.value?.window;
  if (!w || !w.capChars) return 0;
  return Math.min(100, Math.round((w.chars / w.capChars) * 100));
});

const COMMANDS = [
  { command: '/clear', desc: '清除临时上下文' },
  { command: '/沉淀', desc: '把当前对话提炼进长期记忆' },
  { command: '/状态', desc: '窗口与记忆状态' },
  { command: '/记忆', desc: '打开长期记忆面板' }
];
const currentModel = computed(() => {
  const done = [...messageList.value].reverse().find(m => m.role === 'ASSISTANT' && m.model);
  return done?.model ?? '';
});

const commandHint = computed(() => (inputText.value.startsWith('/') ? inputText.value.trim().split(/\s+/)[0] : ''));
const showCommandHints = computed(() => !running.value && inputText.value.trim().startsWith('/'));

const EXAMPLES = [
  '我想看 speculative decoding 近两年的代表作',
  '调研 KV cache 量化，偏方法论',
  '帮我找 RAG 知识冲突的综述和代表作'
];

const runningStage = computed(() => {
  const turn = activeTurn.value;
  if (!turn) return '';
  if (turn.kind === 'DISTILL') return '正在沉淀记忆';
  if (turn.searchCount > 0) return `已检索 ${turn.searchCount} 次`;
  return '正在思考';
});

// 显示最近几条过程记录，最后一条代表“当前动作”
const recentSteps = computed(() => {
  const steps = activeTurn.value?.steps ?? [];
  return steps.slice(-4);
});
const isLatestStep = (index: number) => index === recentSteps.value.length - 1;

const FAILURE_TEXT: Record<string, string> = {
  hard_deadline_exceeded: '这条消息处理超过 30 分钟被系统终止。',
  runner_lost: '探索服务失去联系，这轮没有完成。',
  java_restart: '服务重启中断了这轮处理。',
  runner_unavailable: '探索服务暂时不可用，请稍后再试。',
  prompt_error: '这轮处理异常终止，可以重发一次。',
  model_output_empty: '模型没有返回内容（可能是供应商限流），可以重发一次。'
};
function failureText(reason: string | null | undefined) {
  return FAILURE_TEXT[reason ?? ''] ?? '这轮没能正常完成，请稍后重试。';
}

// ---------------- 候选栏（工作集聚合 + 入库态叠加） ----------------

interface SidebarCandidate extends Api.Discovery.AgentCard {
  importStatus: Api.Discovery.AgentImportResultItem['status'] | null;
  importMessage: string;
  paperId?: string;
  live: boolean;
}

const lastCutoffId = computed(() => {
  let cutoff: number | null = null;
  for (const m of messageList.value) {
    if (m.role === 'CUTOFF') {
      cutoff = m.id;
    }
  }
  return cutoff;
});

const effectiveMessages = computed(() =>
  messageList.value.filter(m => lastCutoffId.value === null || m.id > lastCutoffId.value)
);

const candidates = computed<SidebarCandidate[]>(() => {
  const overlay = new Map<string, Api.Discovery.AgentImportResultItem>();
  const cards = new Map<string, Api.Discovery.AgentCard>();
  for (const message of effectiveMessages.value) {
    for (const card of message.cards ?? []) {
      if (!cards.has(card.arxivId)) cards.set(card.arxivId, card);
    }
    for (const result of message.importResults ?? []) {
      overlay.set(result.arxivId, result);
    }
  }
  for (const card of activeTurn.value?.cards ?? []) {
    if (!cards.has(card.arxivId)) cards.set(card.arxivId, card);
  }
  const items: SidebarCandidate[] = [];
  for (const card of cards.values()) {
    const result = overlay.get(card.arxivId);
    items.push({
      ...card,
      importStatus: result?.status ?? null,
      importMessage: result?.status === 'FAILED' ? result.message : '',
      live: false
    });
  }
  // 运行中的实况候选排前面，标 live
  const liveIds = new Set((activeTurn.value?.cards ?? []).map(c => c.arxivId));
  for (const item of items) {
    if (liveIds.has(item.arxivId)) item.live = true;
  }
  items.sort((a, b) => Number(b.live) - Number(a.live));
  return items;
});

const importableCount = computed(() => candidates.value.filter(c => isImportable(c)).length);

function displayStatus(c: SidebarCandidate): {
  label: string;
  type: 'default' | 'success' | 'warning' | 'error';
} {
  const status = c.importStatus ?? (c.libraryStatus === 'NONE' ? null : c.libraryStatus);
  switch (status) {
    case 'READY':
      return { label: '已可检索', type: 'success' };
    case 'PROCESSING':
      return { label: '解析中', type: 'warning' };
    case 'IMPORTED':
      return { label: '已入库', type: 'success' };
    case 'FAILED':
      return { label: '失败', type: 'error' };
    default:
      return { label: '未入库', type: 'default' };
  }
}

function isImportable(c: SidebarCandidate) {
  if (c.importStatus && c.importStatus !== 'FAILED') return false;
  return c.libraryStatus === 'NONE' || c.importStatus === 'FAILED';
}

function isSelected(c: SidebarCandidate) {
  return isImportable(c) && checked.value.has(c.arxivId);
}

function toggleCandidate(c: SidebarCandidate, on: boolean) {
  if (!isImportable(c)) return;
  const next = new Set(checked.value);
  if (on) {
    next.add(c.arxivId);
  } else {
    next.delete(c.arxivId);
  }
  checked.value = next;
}

/** 单独入库：绕过勾选，直接把这一篇送入库 */
async function importOne(c: SidebarCandidate) {
  if (!isImportable(c) || importingIds.value.has(c.arxivId)) return;
  await runImport([c.arxivId]);
}

/** 作者行：前三位 + "等"；无名时返回空串 */
function authorsLine(c: SidebarCandidate) {
  const authors = c.authors ?? [];
  if (!authors.length) return '';
  const head = authors.slice(0, 3).join(' · ');
  return authors.length > 3 ? `${head} 等 ${authors.length} 位` : head;
}

const importableCandidates = computed(() => candidates.value.filter(c => isImportable(c)));
const importedCandidates = computed(() =>
  candidates.value.filter(c => !isImportable(c) && c.importStatus !== 'FAILED')
);

const selectedIds = computed(() =>
  candidates.value.filter(c => isSelected(c) && !importingIds.value.has(c.arxivId)).map(c => c.arxivId)
);
const selectedCount = computed(() => selectedIds.value.length);
const selectedAll = computed(
  () =>
    importableCandidates.value.length > 0 &&
    selectedCount.value === importableCandidates.value.filter(c => !importingIds.value.has(c.arxivId)).length
);

function selectAll() {
  checked.value = new Set(
    importableCandidates.value.filter(c => !importingIds.value.has(c.arxivId)).map(c => c.arxivId)
  );
}

function deselectAll() {
  checked.value = new Set();
}

async function batchImport() {
  const ids = selectedIds.value;
  if (!ids.length) return;
  await runImport(ids);
}

async function runImport(ids: string[]) {
  for (const id of ids) importingIds.value.add(id);
  const { data, error } = await importDiscoveryAgentCandidates(ids);
  for (const id of ids) importingIds.value.delete(id);
  if (error || !data) {
    return; // 全局拦截器已提示
  }
  const okCount = data.items.filter(item => item.status !== 'FAILED').length;
  const failedCount = data.items.length - okCount;
  if (failedCount) {
    window.$message?.warning(`入库完成 ${okCount} 篇，失败 ${failedCount} 篇`);
  } else {
    window.$message?.success(`已入库 ${okCount} 篇，正在后台解析`);
  }
  await fetchThread(false);
}

/** 「问它」：把这条候选变成一个对话回合的起点 */
// ---------------- 拉取与轮询 ----------------

async function fetchThread(reset = false) {
  const after = reset ? undefined : lastMessageId();
  const { data, error } = await fetchDiscoveryAgentChat(after);
  if (error || !data) return;
  if (reset || !thread.value) {
    thread.value = data;
  } else {
    const knownIds = new Set(thread.value.messages.map(m => m.id));
    const fresh = data.messages.filter(m => !knownIds.has(m.id));
    thread.value = { ...data, messages: [...thread.value.messages, ...fresh] };
    if (data.messages.length && fresh.length === 0) {
      const byId = new Map(data.messages.map(m => [m.id, m]));
      thread.value.messages = thread.value.messages.map(m => byId.get(m.id) ?? m);
    }
  }
}

function lastMessageId(): number {
  return messageList.value.length ? messageList.value[messageList.value.length - 1]!.id : 0;
}

async function pollLoop() {
  if (polling) return;
  polling = true;
  try {
    await fetchThread(false);
    if (thread.value?.activeTurn) {
      schedulePoll();
    } else {
      stopPolling();
    }
  } catch {
    schedulePoll();
  } finally {
    polling = false;
  }
}

function schedulePoll() {
  stopPolling();
  if (thread.value?.activeTurn) {
    pollTimer = setTimeout(pollLoop, POLL_INTERVAL);
  }
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function refreshNow() {
  await fetchThread(false);
  if (thread.value?.activeTurn) {
    schedulePoll();
  } else {
    stopPolling();
  }
}

// ---------------- 发送与指令 ----------------

async function handleSend() {
  const text = inputText.value.trim();
  if (!text || sending.value) return;
  if (running.value) {
    window.$message?.warning('助理还在处理上一条，稍等或点取消');
    return;
  }
  sending.value = true;
  const { data, error } = await postDiscoveryAgentMessage(text);
  sending.value = false;
  if (error || !data) return;
  inputText.value = '';
  if (data.type === 'MEMORY') {
    await refreshNow();
    openMemory();
    return;
  }
  stickBottom.value = true;
  await refreshNow();
  scrollToBottom(true);
  schedulePoll();
}

async function handleQuickCommand(command: string) {
  if (running.value) {
    window.$message?.warning('助理还在处理，稍等');
    return;
  }
  inputText.value = command;
  await handleSend();
}

function handleClearAll() {
  if (running.value) {
    window.$message?.warning('请先取消正在进行的对话');
    return;
  }
  if (!messageList.value.length && !candidates.value.length) return;
  window.$dialog?.warning({
    title: '清除对话与候选',
    content: '会清空全部对话消息与候选论文，长期记忆保留。确定吗？',
    positiveText: '清除',
    negativeText: '取消',
    onPositiveClick: () => clearAndReload()
  });
}

async function clearAndReload() {
  const { error } = await clearDiscoveryAgentChat();
  if (!error) {
    thread.value = null;
    checked.value = new Set();
    stopPolling();
    await fetchThread(true);
    window.$message?.success('已清除全部对话与候选');
  }
}

async function handleCancel() {
  const turn = activeTurn.value;
  if (!turn) return;
  const { error } = await cancelDiscoveryAgentTurn(turn.turnId);
  if (!error) {
    window.$message?.info('已取消');
    await refreshNow();
  }
}

function onInputKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    handleSend();
  }
}

// ---------------- 长期记忆面板 ----------------

async function openMemory() {
  memoryVisible.value = true;
  memoryLoading.value = true;
  const { data, error } = await fetchDiscoveryAgentMemory();
  memoryLoading.value = false;
  if (error || !data) return;
  memoryItems.value = data.items;
}

const kindMeta: Record<Api.Discovery.AgentMemoryItem['kind'], { label: string; tag: 'default' | 'info' | 'success' }> =
  {
    ROLE: { label: '角色定位', tag: 'info' },
    PREF: { label: '研究偏好', tag: 'default' },
    INSIGHT: { label: '沉淀洞察', tag: 'success' }
  };

function selectMemoryKind(kind: unknown) {
  newMemoryKind.value = (['ROLE', 'PREF', 'INSIGHT'] as const).includes(kind as Api.Discovery.AgentMemoryItem['kind'])
    ? (kind as Api.Discovery.AgentMemoryItem['kind'])
    : newMemoryKind.value;
}

async function saveNewMemory() {
  const content = newMemoryContent.value.trim();
  if (!content) {
    window.$message?.warning('记忆内容不能为空');
    return;
  }
  memorySaving.value = true;
  const { data, error } = await createDiscoveryAgentMemory(newMemoryKind.value, content);
  memorySaving.value = false;
  if (error || !data) return;
  memoryItems.value.push(data);
  newMemoryContent.value = '';
  window.$message?.success('已写入长期记忆，下一轮生效');
}

async function toggleMemory(item: Api.Discovery.AgentMemoryItem, active: boolean) {
  item.active = active;
  const { error } = await updateDiscoveryAgentMemory(item.id, { active });
  if (error) {
    item.active = !active;
  }
}

async function removeMemory(item: Api.Discovery.AgentMemoryItem) {
  const { error } = await deleteDiscoveryAgentMemory(item.id);
  if (!error) {
    memoryItems.value = memoryItems.value.filter(m => m.id !== item.id);
  }
}

// ---------------- 滚动 ----------------

function onScroll() {
  const el = scrollEl.value;
  if (!el) return;
  stickBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
}

async function scrollToBottom(force = false) {
  if (!force && !stickBottom.value) return;
  await nextTick();
  const el = scrollEl.value;
  if (el) {
    el.scrollTop = el.scrollHeight;
  }
}

watch(
  () => [messageList.value.length, activeTurn.value?.assistantText],
  () => scrollToBottom()
);

onMounted(async () => {
  candVisible.value = window.innerWidth >= 960;
  await fetchThread(true);
  scrollToBottom(true);
  if (thread.value?.activeTurn) {
    schedulePoll();
  }
});

onUnmounted(stopPolling);
</script>

<template>
  <div class="agent-chat">
    <!-- 顶栏 -->
    <header class="chat-topbar">
      <div class="topbar-title">
        <icon-lucide:sparkles class="topbar-icon" />
        <span>论文发现助理</span>
      </div>
      <div class="topbar-actions">
        <span class="topbar-meta">上下文 {{ windowPercent }}% · 记忆 {{ thread?.memoryCount ?? 0 }} 条</span>
        <NButton size="tiny" quaternary @click="handleQuickCommand('/clear')">/clear</NButton>
        <NButton size="tiny" quaternary @click="handleQuickCommand('/沉淀')">/沉淀</NButton>
        <NButton size="tiny" secondary @click="openMemory">
          <template #icon>
            <icon-lucide:brain />
          </template>
          记忆
        </NButton>
        <NButton size="tiny" secondary @click="statusVisible = true">
          <template #icon>
            <icon-lucide:activity />
          </template>
          情况
        </NButton>
        <NButton size="tiny" quaternary type="error" @click="handleClearAll">
          <template #icon>
            <icon-lucide:trash-2 />
          </template>
          清除
        </NButton>
      </div>
    </header>

    <div class="chat-body">
      <!-- 对话主区 -->
      <main class="chat-main">
        <div ref="scrollEl" class="chat-scroll" @scroll="onScroll">
          <div class="chat-column">
            <div v-if="!messageList.length" class="chat-empty">
              <span class="empty-badge">
                <icon-lucide:sparkles class="text-24px" />
              </span>
              <h2 class="empty-title">说说你想找什么论文</h2>
              <p class="empty-sub">
                像和科研助理聊天一样说你的目标；我检索、评点，候选会出现在右侧候选栏，入不入库你来定。
              </p>
              <div class="empty-examples">
                <button
                  v-for="example in EXAMPLES"
                  :key="example"
                  type="button"
                  class="example-chip"
                  @click="inputText = example"
                >
                  {{ example }}
                </button>
              </div>
            </div>

            <template v-for="message in messageList" :key="message.id">
              <div v-if="message.role === 'CUTOFF'" class="cutoff-divider">已清除临时上下文</div>

              <div v-else-if="message.role === 'SYSTEM'" class="system-line">
                <span v-if="message.kind === 'USER_IMPORT'" class="system-import">
                  <icon-lucide:inbox class="system-icon" />
                  {{ message.content }}
                </span>
                <template v-else>{{ message.content }}</template>
              </div>

              <div v-else-if="message.role === 'USER'" class="message-block message-block--user">
                <div class="user-content">{{ message.content }}</div>
              </div>

              <!-- 助理：只承载对话（候选在侧栏，不甩清单） -->
              <div v-else class="message-block message-block--assistant">
                <p v-if="message.kind === 'DISTILL'" class="answer-mode">[沉淀]</p>
                <div v-if="message.content" class="assistant-text assistant-markdown">
                  <VueMarkdownIt :content="message.content" />
                </div>
                <p v-if="message.turnStatus === 'FAILED'" class="message-error">
                  {{ failureText(message.reasonCode) }}
                </p>
                <p v-if="message.turnStatus === 'CANCELLED'" class="message-error">已取消。</p>
                <ul v-if="message.importResults?.length" class="import-list">
                  <li v-for="result in message.importResults" :key="result.arxivId" class="import-row">
                    <span class="import-id">arXiv:{{ result.arxivId }}</span>
                    <NTag
                      size="small"
                      :bordered="false"
                      :type="
                        result.status === 'READY' || result.status === 'IMPORTED'
                          ? 'success'
                          : result.status === 'PROCESSING'
                            ? 'warning'
                            : 'error'
                      "
                    >
                      {{
                        result.status === 'FAILED'
                          ? '失败'
                          : result.status === 'IMPORTED'
                            ? '已入库，解析中'
                            : result.status === 'PROCESSING'
                              ? '解析中'
                              : '已可检索'
                      }}
                    </NTag>
                    <span v-if="result.status === 'FAILED'" class="import-msg">{{ result.message }}</span>
                  </li>
                </ul>
              </div>
            </template>

            <!-- 运行中：live status + 流式正文（候选静默流进侧栏） -->
            <div v-if="activeTurn" class="message-block message-block--assistant">
              <div class="assistant-live-status">
                <NSpin size="small" />
                <span class="live-text">{{ runningStage }}</span>
                <span class="live-meta">
                  {{ activeTurn.usage.inputTokens.toLocaleString() }} in /
                  {{ activeTurn.usage.outputTokens.toLocaleString() }} out
                  <template v-if="activeTurn.contextPercent != null">
                    · 上下文 {{ Math.round(activeTurn.contextPercent) }}%
                  </template>
                </span>
                <NButton size="tiny" quaternary type="error" @click="handleCancel">取消</NButton>
              </div>
              <div v-if="recentSteps.length" class="assistant-steps">
                <div
                  v-for="(step, index) in recentSteps"
                  :key="`${index}-${step.text}`"
                  class="step-line"
                  :class="{ 'step-line--latest': isLatestStep(index) }"
                >
                  <span class="step-dot" />
                  <span class="step-text">{{ step.text }}</span>
                </div>
              </div>
              <div v-if="activeTurn.assistantText" class="assistant-text assistant-markdown">
                <StreamingMarkdown :content="activeTurn.assistantText" :streaming="true" />
              </div>
            </div>
          </div>
        </div>

        <!-- composer -->
        <footer class="composer-wrap">
          <div class="composer">
            <div v-if="showCommandHints" class="command-hints">
              <button
                v-for="cmd in COMMANDS.filter(c => c.command.startsWith(commandHint))"
                :key="cmd.command"
                type="button"
                class="hint-chip"
                @click="inputText = `${cmd.command} `"
              >
                <b>{{ cmd.command }}</b>
                {{ cmd.desc }}
              </button>
            </div>
            <div class="composer-row">
              <NInput
                ref="inputEl"
                v-model:value="inputText"
                type="textarea"
                class="composer-input"
                :autosize="{ minRows: 1, maxRows: 6 }"
                placeholder="说说你的研究目标，我会检索并给出点评；候选在右侧入库"
                :bordered="false"
                :disabled="running"
                @keydown="onInputKey"
              />
              <NButton
                circle
                type="primary"
                class="composer-send"
                :loading="sending"
                :disabled="running || !inputText.trim()"
                @click="handleSend"
              >
                <template #icon>
                  <icon-lucide:arrow-up />
                </template>
              </NButton>
            </div>
          </div>
        </footer>
      </main>

      <!-- 候选栏 -->
      <aside v-if="candVisible" class="cand-panel">
        <div class="cand-head">
          <div class="cand-title">
            <span class="cand-title-text">候选论文</span>
            <span v-if="candidates.length" class="cand-count">{{ candidates.length }}</span>
            <span v-if="activeTurn" class="cand-live-flag">
              <i />
              筛选中
            </span>
          </div>
          <button type="button" class="cand-close" @click="candVisible = false">
            <icon-lucide:x class="text-14px" />
          </button>
        </div>

        <div class="cand-scroll">
          <!-- 待入库区 -->
          <section v-if="importableCandidates.length" class="cand-group">
            <header class="cand-group-head">
              <span class="cand-group-name">待你决定</span>
              <span class="cand-group-count">勾选后一键入库，或单篇直接入</span>
            </header>
            <div class="cand-list">
              <article
                v-for="candidate in importableCandidates"
                :key="candidate.arxivId"
                class="cand-card"
                :class="{
                  'is-selected': isSelected(candidate),
                  'is-live': candidate.live,
                  'is-importing': importingIds.has(candidate.arxivId)
                }"
                @click="toggleCandidate(candidate, !isSelected(candidate))"
              >
                <div class="cand-card-main">
                  <div class="cand-card-head">
                    <NCheckbox
                      class="cand-card-check"
                      :checked="isSelected(candidate)"
                      :disabled="importingIds.has(candidate.arxivId)"
                      @click.stop
                      @update:checked="(on: boolean) => toggleCandidate(candidate, on)"
                    />
                    <a
                      class="cand-card-title"
                      :href="`https://arxiv.org/abs/${candidate.arxivId}`"
                      target="_blank"
                      rel="noopener noreferrer"
                      @click.stop
                    >
                      {{ candidate.title }}
                    </a>
                    <button
                      type="button"
                      class="cand-card-import"
                      :disabled="importingIds.has(candidate.arxivId)"
                      @click.stop="importOne(candidate)"
                    >
                      入库
                    </button>
                  </div>
                  <p v-if="candidate.summary" class="cand-card-summary">{{ candidate.summary }}</p>
                  <p v-if="authorsLine(candidate)" class="cand-card-authors">{{ authorsLine(candidate) }}</p>
                  <div class="cand-card-foot">
                    <a
                      class="cand-card-id"
                      :href="`https://arxiv.org/abs/${candidate.arxivId}`"
                      target="_blank"
                      rel="noopener noreferrer"
                      @click.stop
                    >
                      arXiv:{{ candidate.arxivId }}
                    </a>
                    <span v-if="candidate.publishedYear" class="cand-card-year">{{ candidate.publishedYear }}</span>
                    <span v-if="candidate.live" class="cand-card-live">
                      <i />
                      刚发现
                    </span>
                    <span v-if="candidate.importMessage" class="cand-card-err">{{ candidate.importMessage }}</span>
                    <span class="cand-card-cats">
                      <span v-for="cat in (candidate.categories ?? []).slice(0, 3)" :key="cat" class="cand-card-cat">
                        {{ cat }}
                      </span>
                    </span>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <!-- 已入库区 -->
          <section v-if="importedCandidates.length" class="cand-group cand-group--imported">
            <header class="cand-group-head">
              <span class="cand-group-name">已入库</span>
              <span class="cand-group-count">{{ importedCandidates.length }} 篇在库内处理中</span>
            </header>
            <div class="cand-list">
              <article
                v-for="candidate in importedCandidates"
                :key="candidate.arxivId"
                class="cand-card cand-card--imported"
              >
                <div class="cand-card-main">
                  <div class="cand-card-head">
                    <a
                      class="cand-card-title"
                      :href="
                        candidate.paperId
                          ? `/papers/preview?paperId=${candidate.paperId}`
                          : `https://arxiv.org/abs/${candidate.arxivId}`
                      "
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {{ candidate.title }}
                    </a>
                    <span class="cand-status" :data-tone="displayStatus(candidate).type">
                      {{ displayStatus(candidate).label }}
                    </span>
                  </div>
                  <p v-if="candidate.summary" class="cand-card-summary">{{ candidate.summary }}</p>
                  <div class="cand-card-foot">
                    <span class="cand-card-id">arXiv:{{ candidate.arxivId }}</span>
                    <span v-if="candidate.publishedYear" class="cand-card-year">{{ candidate.publishedYear }}</span>
                    <span class="cand-card-cats">
                      <span v-for="cat in (candidate.categories ?? []).slice(0, 3)" :key="cat" class="cand-card-cat">
                        {{ cat }}
                      </span>
                    </span>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <!-- 空态 -->
          <div v-if="!candidates.length" class="cand-empty">
            <icon-lucide:library class="cand-empty-icon" />
            <p class="cand-empty-title">还没有候选</p>
            <p class="cand-empty-text">跟助理聊聊研究方向，它会检索 arXiv 并把精选放进这里。</p>
          </div>
        </div>

        <!-- 底部固定操作条 -->
        <div v-if="importableCandidates.length" class="cand-action">
          <button type="button" class="cand-action-toggle" @click="selectedAll ? deselectAll() : selectAll()">
            {{ selectedAll ? '清空选择' : '全选' }}
          </button>
          <NButton
            size="small"
            type="primary"
            class="cand-action-btn"
            :loading="importingIds.size > 0"
            :disabled="!selectedCount"
            @click="batchImport"
          >
            <template #icon><icon-lucide:package-plus /></template>
            入库所选
            <span v-if="selectedCount" class="cand-action-num">{{ selectedCount }}</span>
          </NButton>
        </div>
      </aside>
    </div>

    <button v-if="!candVisible" type="button" class="cand-float" @click="candVisible = true">
      <icon-lucide:library />
      候选 {{ candidates.length }}{{ selectedCount ? ` (${selectedCount})` : '' }}
    </button>

    <!-- 长期记忆面板 -->
    <NDrawer v-model:show="memoryVisible" :width="420" placement="right">
      <NDrawerContent title="长期记忆（对助理生效）" closable>
        <div class="memory-new">
          <div class="memory-new-row">
            <NTag
              v-for="(meta, kind) in kindMeta"
              :key="kind"
              size="small"
              :type="newMemoryKind === kind ? meta.tag : 'default'"
              :bordered="newMemoryKind === kind"
              class="memory-kind-chip"
              @click="selectMemoryKind(kind)"
            >
              {{ meta.label }}
            </NTag>
          </div>
          <NInput
            v-model:value="newMemoryContent"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="例如：我在做 speculative decoding 方向的综述，偏好方法论论文"
          />
          <div>
            <NButton size="small" type="primary" ghost :loading="memorySaving" @click="saveNewMemory">写入</NButton>
          </div>
        </div>

        <div class="memory-tip">沉淀（/沉淀）的条目也在这里；停用后下轮不再注入。上限内自动保留最新的。</div>

        <NEmpty v-if="!memoryItems.length && !memoryLoading" description="还没有长期记忆" class="memory-empty" />
        <div v-else class="memory-list">
          <div v-for="item in memoryItems" :key="item.id" class="memory-item" :class="{ inactive: !item.active }">
            <div class="memory-head">
              <NTag size="tiny" :type="kindMeta[item.kind].tag" :bordered="false">{{ kindMeta[item.kind].label }}</NTag>
              <span class="memory-source">{{ item.source === 'DISTILLED' ? '沉淀' : '手写' }}</span>
              <button class="memory-toggle" type="button" @click="toggleMemory(item, !item.active)">
                {{ item.active ? '停用' : '启用' }}
              </button>
              <button class="memory-delete" type="button" @click="removeMemory(item)">删除</button>
            </div>
            <p class="memory-content">{{ item.content }}</p>
          </div>
        </div>
      </NDrawerContent>
    </NDrawer>

    <!-- 情况面板 -->
    <NDrawer v-model:show="statusVisible" :width="380" placement="right">
      <NDrawerContent title="探索情况" closable>
        <div class="status-section">
          <h4>当前状态</h4>
          <div class="status-row">
            <span class="status-label">模型</span>
            <span class="status-value">{{ currentModel || '未使用' }}</span>
          </div>
          <div class="status-row">
            <span class="status-label">运行中</span>
            <span class="status-value">
              <template v-if="running">
                <NSpin size="small" />
                {{ runningStage }}
              </template>
              <template v-else>空闲</template>
            </span>
          </div>
          <div class="status-row">
            <span class="status-label">上下文窗口</span>
            <span class="status-value">
              {{ windowPercent }}%（~{{ thread?.window?.chars ?? 0 }}/{{ thread?.window?.capChars ?? 24000 }} 字符）
            </span>
          </div>
        </div>
        <div class="status-section">
          <h4>候选论文</h4>
          <div class="status-row">
            <span class="status-label">当前候选</span>
            <span class="status-value">{{ candidates.length }} 篇（可选 {{ importableCount }}）</span>
          </div>
          <div class="status-row">
            <span class="status-label">已入库</span>
            <span class="status-value">
              {{
                candidates.filter(
                  c =>
                    (c.importStatus === null && c.libraryStatus !== 'NONE') ||
                    c.importStatus === 'IMPORTED' ||
                    c.importStatus === 'READY'
                ).length
              }}
              篇
            </span>
          </div>
        </div>
        <div class="status-section">
          <h4>长期记忆</h4>
          <div class="status-row">
            <span class="status-label">条目数</span>
            <span class="status-value">{{ thread?.memoryCount ?? 0 }} 条</span>
          </div>
          <NButton
            size="tiny"
            quaternary
            @click="
              statusVisible = false;
              openMemory();
            "
          >
            查看/编辑
          </NButton>
        </div>
        <div class="status-section">
          <h4>用量</h4>
          <div class="status-row">
            <span class="status-label">今日 turn</span>
            <span class="status-value">{{ messageList.filter(m => m.role === 'ASSISTANT').length }}</span>
          </div>
          <div v-if="activeTurn" class="status-row">
            <span class="status-label">本轮</span>
            <span class="status-value">
              输入 {{ activeTurn.usage.inputTokens.toLocaleString() }} / 输出
              {{ activeTurn.usage.outputTokens.toLocaleString() }}
            </span>
          </div>
        </div>
        <div class="status-section">
          <h4>快捷操作</h4>
          <div class="status-actions">
            <NButton size="small" ghost type="primary" @click="handleQuickCommand('/clear')">清除上下文</NButton>
            <NButton size="small" ghost type="primary" @click="handleQuickCommand('/沉淀')">沉淀记忆</NButton>
            <NButton v-if="running" size="small" ghost type="error" @click="handleCancel">取消当前</NButton>
          </div>
        </div>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped lang="scss">
.agent-chat {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

/* ---------- 顶栏 ---------- */
.chat-topbar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2-5) var(--space-1);
  border-bottom: 1px solid var(--color-border-soft);
}

.topbar-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-text);
  font-size: var(--text-md);
  font-weight: 600;
}

.topbar-icon {
  color: var(--color-research);
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
}

.topbar-meta {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

/* ---------- 主体：对话 + 候选栏 ---------- */
.chat-body {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
}

.chat-main {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.chat-scroll {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.chat-column {
  display: flex;
  width: min(var(--reading-prose-width, 760px), 100%);
  margin: 0 auto;
  flex-direction: column;
  gap: var(--space-6);
  padding: var(--space-6) var(--space-4) var(--space-8);
}

.chat-empty {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  padding: var(--space-8) var(--space-4);
  text-align: center;
}

.empty-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-pill);
  background: var(--color-research-soft-bg, rgba(0, 100, 200, 0.08));
  color: var(--color-research);
}

.empty-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--text-2xl);
  font-weight: 700;
  line-height: var(--leading-tight);
}

.empty-sub {
  max-width: 460px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
}

.empty-examples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.example-chip {
  padding: var(--space-1-5) var(--space-3);
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text);
  font-size: var(--text-sm);
  cursor: pointer;
}

.example-chip:hover {
  border-color: var(--color-border-strong);
}

.cutoff-divider,
.system-line {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  text-align: center;
}

.cutoff-divider {
  position: relative;
  padding: var(--space-1) 0;

  &::before,
  &::after {
    position: absolute;
    top: 50%;
    width: calc(50% - 90px);
    height: 1px;
    background: var(--color-border-soft);
    content: '';
  }

  &::before {
    left: 0;
  }

  &::after {
    right: 0;
  }
}

.system-line {
  line-height: var(--leading-relaxed);
}

.system-import {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-success);
  justify-content: center;
}

.system-icon {
  font-size: 14px;
}

.message-block {
  display: flex;
  width: 100%;
  flex-direction: column;
}

.message-block--user {
  align-items: flex-end;
}

.user-content {
  display: inline-flex;
  width: fit-content;
  max-width: min(680px, 92%);
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
  padding: var(--space-2-5) var(--space-4);
  font-size: var(--text-md);
  line-height: var(--leading-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.message-block--assistant {
  align-items: flex-start;
  gap: var(--space-2-5);
}

.answer-mode {
  margin: 0;
  color: var(--color-text-muted);
  font-family: var(--font-utility, var(--font-mono));
  font-size: 11px;
  line-height: 1;
}

.assistant-text {
  margin: 0;
  width: 100%;
  color: var(--color-text);
  font-size: var(--text-md);
  line-height: var(--leading-relaxed);
}

/* markdown 渲染样式的段落瘦底稿 */
.assistant-markdown :deep(p) {
  margin: 0 0 var(--space-2-5);
}

.assistant-markdown :deep(p:last-child) {
  margin: 0;
}

.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2),
.assistant-markdown :deep(h3) {
  margin: var(--space-3) 0 var(--space-2);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: var(--leading-snug);
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  margin: 0 0 var(--space-2-5);
  padding-left: 1.5em;
}

.assistant-markdown :deep(li) {
  margin: var(--space-1) 0;
  line-height: var(--leading-normal);
}

.assistant-markdown :deep(strong) {
  font-weight: 600;
}

.assistant-markdown :deep(a) {
  color: var(--color-research);
  text-decoration: none;
}

.assistant-markdown :deep(a:hover) {
  text-decoration: underline;
}

.assistant-markdown :deep(code) {
  padding: 0.15em 0.35em;
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  font-family: var(--font-utility, var(--font-mono));
  font-size: 0.9em;
  white-space: pre-wrap;
  word-break: break-word;
}

.assistant-markdown :deep(pre) {
  margin: 0 0 var(--space-2-5);
  border-radius: var(--radius-md);
  overflow-x: auto;
  white-space: pre;
}

.assistant-markdown :deep(blockquote) {
  margin: 0 0 var(--space-2-5);
  border-left: 3px solid var(--color-border-soft);
  padding-left: var(--space-3);
  color: var(--color-text-muted);
}

.assistant-markdown :deep(table) {
  margin: 0 0 var(--space-2-5);
  border-collapse: collapse;
  width: 100%;
  max-width: 100%;
}

.assistant-markdown :deep(td),
.assistant-markdown :deep(th) {
  border: 1px solid var(--color-border-soft);
  padding: var(--space-1-5) var(--space-2);
  font-size: var(--text-sm);
  text-align: left;
}

.message-error {
  margin: 0;
  color: var(--color-error);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
}

.import-list {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: var(--space-1-5);
  margin: 0;
  padding: 0;
  list-style: none;
}

.import-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
}

.import-id {
  color: var(--color-text-muted);
}

.import-msg {
  flex: 1;
  color: var(--color-error);
}

.assistant-live-status {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: fit-content;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  background: var(--color-research-soft-bg, rgba(0, 100, 200, 0.08));
  color: var(--color-research);
  font-size: var(--text-xs);
}

.live-text {
  font-weight: 600;
}

.live-meta {
  color: var(--color-text-muted);
}

/* ---------- 运行中的过程轨迹 ---------- */
.assistant-steps {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: var(--space-1);
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border-soft);
  background: var(--color-surface-alt);
}

.step-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.step-line--latest {
  color: var(--color-research);
  font-weight: 500;
}

.step-dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-border-strong, var(--color-text-muted));
}

.step-line--latest .step-dot {
  background: var(--color-research);
}

.step-text {
  line-height: var(--leading-normal);
  word-break: break-word;
}

/* ---------- composer ---------- */
.composer-wrap {
  display: flex;
  flex: none;
  justify-content: center;
  padding: var(--space-2) var(--space-4) max(var(--space-4), env(safe-area-inset-bottom));
}

.composer {
  display: flex;
  width: min(960px, 100%);
  min-height: 60px;
  flex-direction: column;
  gap: var(--space-1-5);
  border: none;
  border-radius: var(--radius-composer, var(--radius-lg));
  background: var(--color-surface);
  box-shadow: var(--shadow-composer, var(--shadow-card));
  padding: var(--space-2-5) var(--space-3-5) var(--space-2);
  transition: box-shadow var(--duration-base) var(--ease-standard);
}

.composer:focus-within {
  box-shadow: var(--shadow-composer-hover, var(--shadow-card));
}

.command-hints {
  display: flex;
  gap: var(--space-2);
}

.hint-chip {
  padding: var(--space-1) var(--space-2-5);
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  cursor: pointer;
}

.hint-chip b {
  margin-right: 6px;
  color: var(--color-research);
}

.composer-row {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
}

.composer-input {
  flex: 1 1 auto;
}

.composer-input :deep(.n-input-wrapper) {
  padding: 0;
}

.composer-send {
  flex: none;
}

/* ---------- 候选栏 ---------- */
.cand-panel {
  display: flex;
  width: 360px;
  min-height: 0;
  flex: none;
  flex-direction: column;
  border-left: 1px solid var(--color-border-soft);
  background: var(--color-surface);
}

.cand-head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-3) var(--space-1-5);
}

.cand-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--space-1-5);
}

.cand-title-text {
  font-size: var(--text-sm);
  font-weight: 600;
}

.cand-count {
  padding: 0 7px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 1.7;
}

.cand-live-flag {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--color-research);
  font-size: var(--text-xs);
  font-weight: 500;
}

.cand-live-flag i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-research);
  animation: cand-pulse 1.6s var(--ease-standard) infinite;
}

@keyframes cand-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.3;
  }
}

.cand-close {
  padding: var(--space-0-5);
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;

  &:hover {
    background: var(--color-surface-alt);
    color: var(--color-text);
  }
}

.cand-scroll {
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  padding: 0 var(--space-2-5) var(--space-2);
  gap: var(--space-1);
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.cand-group {
  flex: none;
}

.cand-group--imported {
  margin-top: var(--space-2);
}

.cand-group-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-0-5) var(--space-2);
}

.cand-group-name {
  color: var(--color-text);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.cand-group-count {
  flex: 1;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cand-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1-5);
}

/* ---- 候选卡片 ---- */
.cand-card {
  border: 1px solid var(--color-border-soft);
  border-radius: var(--radius-panel);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    background var(--duration-fast) var(--ease-standard);

  &:hover {
    border-color: var(--color-border-strong);
  }
}

.cand-card.is-selected {
  border-color: var(--color-research);
  background: var(--color-research-soft-bg);
}

.cand-card.is-live {
  box-shadow:
    inset 2px 0 0 var(--color-research),
    var(--shadow-card);
}

.cand-card.is-importing {
  opacity: 0.55;
}

.cand-card--imported {
  cursor: default;
  opacity: 0.78;
}

.cand-card-main {
  padding: var(--space-2-5) var(--space-3) var(--space-2);
}

.cand-card-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}

.cand-card-check {
  flex: none;
  margin-top: 1px;
}

.cand-card-title {
  display: -webkit-box;
  overflow: hidden;
  flex: 1 1 auto;
  color: var(--color-text);
  font-family: var(--font-reading);
  font-size: var(--text-sm);
  font-weight: 600;
  line-height: var(--leading-snug);
  text-decoration: none;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;

  &:hover {
    color: var(--color-research);
  }
}

.cand-card--imported .cand-card-title {
  -webkit-line-clamp: 2;
}

.cand-card-import {
  flex: none;
  padding: 2px var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.5;
  cursor: pointer;
  transition:
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);

  &:hover:not(:disabled) {
    border-color: var(--color-research);
    color: var(--color-research);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.cand-card-summary {
  display: -webkit-box;
  overflow: hidden;
  margin: var(--space-1-5) 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.cand-card-authors {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cand-card-foot {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  margin-top: var(--space-2);
}

.cand-card-id {
  color: var(--color-research);
  font-family: var(--font-utility);
  font-size: 11px;
  line-height: 1.5;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

.cand-card-year {
  color: var(--color-text-muted);
  font-size: 11px;
}

.cand-card-live {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--color-research);
  font-size: 11px;
  font-weight: 500;
}

.cand-card-live i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-research);
  animation: cand-pulse 1.6s var(--ease-standard) infinite;
}

.cand-card-err {
  flex: 1;
  overflow: hidden;
  color: var(--color-error);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cand-card-cats {
  display: flex;
  flex: 1;
  justify-content: flex-end;
  gap: 4px;
  overflow: hidden;
}

.cand-card-cat {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: 10px;
  line-height: 1.7;
  white-space: nowrap;
}

.cand-status {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  font-weight: 500;
  line-height: 1.6;

  &[data-tone='success'] {
    background: rgba(22, 128, 57, 0.1);
    color: var(--color-success);
  }

  &[data-tone='warning'] {
    background: rgba(183, 121, 31, 0.12);
    color: var(--color-warning-text);
  }

  &[data-tone='error'] {
    background: rgba(217, 45, 32, 0.1);
    color: var(--color-error);
  }

  &[data-tone='default'] {
    background: var(--color-surface-alt);
    color: var(--color-text-muted);
  }
}

/* 底部固定操作条 */
.cand-action {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-2-5);
  border-top: 1px solid var(--color-border-soft);
  background: var(--color-surface);
}

.cand-action-toggle {
  flex: none;
  padding: 0;
  border: none;
  background: none;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  cursor: pointer;

  &:hover {
    color: var(--color-text);
  }
}

.cand-action-btn {
  flex: 1;
}

.cand-action-num {
  margin-left: 4px;
  font-family: var(--font-utility);
}

/* 空态 */
.cand-empty {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-8) var(--space-4);
  text-align: center;
}

.cand-empty-icon {
  font-size: 30px;
  color: var(--color-text-muted);
  opacity: 0.45;
}

.cand-empty-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--text-sm);
  font-weight: 600;
}

.cand-empty-text {
  max-width: 240px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-relaxed);
}

/* 悬浮按钮 */
.cand-float {
  position: absolute;
  z-index: 20;
  right: var(--space-4);
  bottom: calc(var(--space-14) + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  gap: 6px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  box-shadow: var(--shadow-float);
  color: var(--color-research);
  font-size: var(--text-xs);
  cursor: pointer;
}

/* 响应式 */
@media (max-width: 960px) {
  .cand-panel {
    position: absolute;
    z-index: 30;
    inset: 46px 0 max(92px, calc(var(--space-14))) auto;
    width: min(360px, 88vw);
    border-left: 1px solid var(--color-border-strong);
    box-shadow: var(--shadow-card-soft);
  }
}

/* ---------- 记忆面板 ---------- */
.memory-new {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border-soft);
  margin-bottom: var(--space-2);
}

.memory-new-row {
  display: flex;
  gap: var(--space-1-5);
}

.memory-kind-chip {
  cursor: pointer;
}

.memory-tip {
  padding: var(--space-2) 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
}

.memory-list {
  display: flex;
  flex-direction: column;
}

.memory-item {
  padding: var(--space-2-5) 0;
  border-bottom: 1px dashed var(--color-border-soft);

  &:last-child {
    border-bottom: none;
  }
}

.memory-item.inactive .memory-content {
  opacity: 0.5;
}

.memory-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
}

.memory-source {
  color: var(--color-text-muted);
}

.memory-toggle,
.memory-delete {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-research);
  font-size: var(--text-xs);
  cursor: pointer;
}

.memory-toggle {
  margin-left: auto;
}

.memory-delete {
  margin-left: var(--space-2);
  color: var(--color-error);
}

.memory-content {
  margin: var(--space-1-5) 0 0;
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.memory-empty {
  padding-top: var(--space-6);
}

/* ---------- 响应式 ---------- */
@media (max-width: 960px) {
  .cand-panel {
    position: absolute;
    z-index: 30;
    inset: 46px 0 max(92px, calc(var(--space-14))) auto;
    width: min(340px, 88vw);
    border-left: 1px solid var(--color-border-strong);
    box-shadow: var(--shadow-card);
  }
}
</style>

.status-section { margin-bottom: var(--space-5); h4 { margin: 0 0 var(--space-2); color: var(--color-text-muted);
font-size: var(--text-xs); font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; } } .status-row {
display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-1) 0;
font-size: var(--text-sm); .status-label { color: var(--color-text-muted); font-size: var(--text-xs); flex-shrink: 0; }
.status-value { color: var(--color-text); font-size: var(--text-sm); display: flex; align-items: center; gap:
var(--space-1); } } .status-actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
