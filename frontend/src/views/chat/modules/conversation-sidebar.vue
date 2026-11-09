<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';

defineOptions({
  name: 'ConversationSidebar'
});

const collapsed = defineModel<boolean>('collapsed', { default: false });

const emit = defineEmits<{
  openSettings: [];
}>();

const chatStore = useChatStore();
const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const { conversationId, generatingConversationIds, sessions, sessionsLoading } = storeToRefs(chatStore);
const keyword = ref('');
const showArchived = ref(false);

const emptySessionLabel = computed(() => {
  const query = keyword.value.trim();
  if (query) return `没有匹配「${query}」的 query`;
  return showArchived.value ? '暂无归档 query' : '暂无 query 记录';
});

const visibleSessions = computed(() => {
  const status = showArchived.value ? 'ARCHIVED' : 'ACTIVE';
  const query = keyword.value.trim().toLowerCase();

  return sessions.value
    .filter(session => session.status === status)
    .filter(session => {
      if (!query) return true;
      return [session.title, session.conversationId].some(item =>
        String(item || '')
          .toLowerCase()
          .includes(query)
      );
    });
});

const activeCount = computed(() => sessions.value.filter(item => item.status === 'ACTIVE').length);
// Server flag (any conversation of the user, any tab) wins; the store set is
// the immediate local fallback for the conversation open in this tab.
function isSessionGenerating(session: Api.Chat.ConversationSession): boolean {
  return (
    Boolean((session as { generating?: boolean }).generating) ||
    generatingConversationIds.value.has(session.conversationId)
  );
}
const archivedCount = computed(() => sessions.value.filter(item => item.status === 'ARCHIVED').length);
const accountName = computed(() => authStore.userInfo.username || 'Citewise User');
const accountRole = computed(() => (authStore.isAdmin ? 'Admin management' : 'Workspace settings'));
const avatarCells = computed(() => buildAvatarCells(accountName.value));
const avatarStyle = computed(() => ({
  '--avatar-fill': buildAvatarFill(accountName.value)
}));

onMounted(() => {
  chatStore.loadSessions({ loadDetails: route.name === 'chat' });
});

function handleCollapse() {
  collapsed.value = true;
}

function handleNewChat() {
  chatStore.startDraftSession();

  if (route.name !== 'chat') {
    void router.push({ name: 'chat' });
  }
}

async function handleSelect(cid: string) {
  await chatStore.switchSession(cid, { loadDetails: route.name === 'chat' });

  if (route.name !== 'chat') {
    await router.push({ name: 'chat' });
  }
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

function handleDelete(cid: string) {
  chatStore.deleteSession(cid);
}

const isWorkbenchActive = computed(() => {
  return route.name === 'knowledge-base';
});

const isExploreActive = computed(() => {
  return route.name === 'discover';
});

const canDiscover = computed(() => authStore.userInfo.role !== 'GUEST');

async function handleWorkbenchClick() {
  await router.push({ name: 'knowledge-base' });
}

async function handleExploreClick() {
  await router.push({ name: 'discover' });
}

async function handleAccountClick() {
  emit('openSettings');
}

function handleLogout() {
  authStore.logout();
}

function buildAvatarCells(seed: string) {
  let hash = 0;
  const normalized = seed || 'Citewise';

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
  const normalized = seed || 'Citewise';

  for (let index = 0; index < normalized.length; index += 1) {
    hash = Math.imul(33, hash) + normalized.charCodeAt(index);
  }

  const hue = Math.abs(hash) % 360;
  return `hsl(${hue} 54% 38%)`;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '';
  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'week')) {
    return date.format('ddd');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}
</script>

<template>
  <div class="chat-sidebar" :class="collapsed ? 'w-0 min-w-0 border-r-0' : 'w-[276px] min-w-[276px]'">
    <div class="w-[276px] flex flex-col flex-1 overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
      <div class="brand-row">
        <SystemLogo class="brand-logo" />
        <div class="min-w-0 flex-1">
          <div class="brand-title">Citewise</div>
        </div>
        <NButton
          text
          size="small"
          class="collapse-button"
          aria-label="收起侧边栏"
          title="收起侧边栏"
          @click="handleCollapse"
        >
          <template #icon>
            <icon-lucide:panel-left-close />
          </template>
        </NButton>
      </div>

      <div class="sidebar-actions">
        <button
          type="button"
          class="library-button"
          :class="{ 'library-button--active': isWorkbenchActive }"
          aria-label="进入文献库"
          @click="handleWorkbenchClick"
        >
          <icon-lucide:library class="library-button__icon" />
          <span>Paper Library</span>
        </button>
        <button
          v-if="canDiscover"
          type="button"
          class="library-button"
          :class="{ 'library-button--active': isExploreActive }"
          aria-label="进入论文探索"
          @click="handleExploreClick"
        >
          <icon-lucide:compass class="library-button__icon" />
          <span>探索论文</span>
        </button>
        <NButton type="primary" block class="new-chat-button" @click="handleNewChat">
          <template #icon>
            <icon-lucide:plus />
          </template>
          New Query
        </NButton>
      </div>

      <div class="sidebar-search">
        <NInput v-model:value="keyword" clearable placeholder="Search chats">
          <template #prefix>
            <icon-lucide:search class="search-icon" />
          </template>
        </NInput>
      </div>

      <div class="sidebar-section-header">
        <span>{{ showArchived ? 'Archived' : 'Recent queries' }}</span>
        <button type="button" class="archive-toggle" @click="showArchived = !showArchived">
          {{ showArchived ? `recent ${activeCount}` : `archived ${archivedCount}` }}
        </button>
      </div>

      <div class="session-scroll flex-1 px-2 pb-2">
        <NSpin :show="sessionsLoading" class="h-full">
          <div v-if="visibleSessions.length === 0 && !sessionsLoading" class="empty-sessions">
            <icon-lucide:message-circle class="empty-sessions__icon" />
            <span>{{ emptySessionLabel }}</span>
          </div>

          <NVirtualList
            v-else
            :items="visibleSessions"
            :item-size="60"
            key-field="conversationId"
            class="session-virtual-list"
          >
            <template #default="{ item: session }">
              <div
                :key="session.conversationId"
                data-testid="conversation-session"
                :data-conversation-id="session.conversationId"
                class="session-item group"
                :class="session.conversationId === conversationId ? 'session-item--active' : ''"
                role="button"
                tabindex="0"
                :aria-current="session.conversationId === conversationId ? 'true' : undefined"
                @click="handleSelect(session.conversationId)"
                @keydown.enter.prevent="handleSelect(session.conversationId)"
                @keydown.space.prevent="handleSelect(session.conversationId)"
              >
                <icon-lucide:sparkles
                  v-if="isSessionGenerating(session)"
                  class="session-item__generating shrink-0"
                  aria-hidden="true"
                />
                <div class="min-w-0 flex-1">
                  <div class="session-title">
                    {{ session.title }}
                  </div>
                  <div class="session-date">
                    {{ formatDate(session.updatedAt) }}
                  </div>
                </div>

                <NPopconfirm v-if="!showArchived" @positive-click="handleArchive(session.conversationId)">
                  <template #trigger>
                    <NButton
                      class="shrink-0 transition-opacity focus-visible:opacity-100"
                      :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                      text
                      size="small"
                      :aria-label="`归档 ${session.title}`"
                      :title="`归档 ${session.title}`"
                      @click.stop
                    >
                      <template #icon>
                        <icon-lucide:archive class="text-15px" />
                      </template>
                    </NButton>
                  </template>
                  归档后可在 Archived 中找回
                </NPopconfirm>
                <NButton
                  v-else
                  class="shrink-0 opacity-0 transition-opacity focus-visible:opacity-100 group-hover:opacity-100"
                  text
                  size="small"
                  :aria-label="`取消归档 ${session.title}`"
                  :title="`取消归档 ${session.title}`"
                  @click.stop="handleUnarchive(session.conversationId)"
                >
                  <template #icon>
                    <icon-lucide:archive-restore class="text-15px" />
                  </template>
                </NButton>
                <NPopconfirm
                  positive-text="删除"
                  negative-text="取消"
                  @positive-click="handleDelete(session.conversationId)"
                >
                  <template #trigger>
                    <NButton
                      class="shrink-0 transition-opacity focus-visible:opacity-100"
                      :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                      text
                      size="small"
                      :aria-label="`删除 ${session.title}`"
                      :title="`删除 ${session.title}`"
                      @click.stop
                    >
                      <template #icon>
                        <icon-lucide:trash-2 class="text-15px" />
                      </template>
                    </NButton>
                  </template>
                  删除后会移除此 session 和历史记录
                </NPopconfirm>
              </div>
            </template>
          </NVirtualList>
        </NSpin>
      </div>

      <div class="sidebar-footer">
        <button
          type="button"
          class="account-button"
          :class="{
            'account-button--active': route.name === 'personal-center'
          }"
          aria-label="进入管理页面"
          title="进入管理页面"
          @click="handleAccountClick"
        >
          <span class="avatar-identicon" :style="avatarStyle" aria-hidden="true">
            <span
              v-for="(filled, index) in avatarCells"
              :key="index"
              class="avatar-identicon__cell"
              :class="{ 'avatar-identicon__cell--filled': filled }"
            />
          </span>
          <span class="account-copy">
            <strong>{{ accountName }}</strong>
            <small>{{ accountRole }}</small>
          </span>
          <icon-lucide:settings class="account-button__icon" />
        </button>

        <button type="button" class="logout-button" aria-label="退出登录" @click="handleLogout">
          <icon-lucide:log-out />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-sidebar {
  position: relative;
  flex-shrink: 0;
  display: flex;
  height: 100%;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--color-border);
  background: var(--color-bg);
  transition:
    width var(--duration-base) var(--ease-standard),
    min-width var(--duration-base) var(--ease-standard);
}

.brand-row {
  display: flex;
  height: 60px;
  flex-shrink: 0;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
}

.brand-logo {
  flex: 0 0 auto;
  color: var(--color-primary);
  font-size: 22px;
}

.brand-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-primary);
  font-family: var(--font-interface);
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
}

.collapse-button {
  min-width: 28px;
  min-height: 28px;
  color: var(--color-text-muted);
}

.sidebar-actions {
  display: grid;
  gap: var(--space-2);
  padding: 0 var(--space-3);
}

.new-chat-button {
  height: 40px;
  border-radius: var(--radius-md);
  font-weight: 600;
}

.library-button {
  display: flex;
  width: 100%;
  height: 36px;
  align-items: center;
  gap: var(--space-2);
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font-size: var(--text-base);
  font-weight: 500;
  padding: 0 var(--space-2-5);
  text-align: left;
  transition:
    background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.library-button:hover {
  background: var(--color-hover-tint);
}

.library-button--active {
  background: var(--color-research-soft-bg);
  color: var(--color-research);
  font-weight: 600;
}

.library-button__icon {
  flex: 0 0 auto;
  font-size: 17px;
}

.sidebar-search {
  padding: var(--space-3) var(--space-3) 0;
}

.sidebar-search :deep(.n-input) {
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

/* Naive's own focus border is the same colour as the resting border, so the
   search field showed no focus state at all. */
.sidebar-search :deep(.n-input.n-input--focus) {
  box-shadow: 0 0 0 1px rgba(23, 24, 26, 0.14);
}

.sidebar-search :deep(.n-input:has(input:focus-visible)) {
  box-shadow: 0 0 0 2px var(--color-research);
}

.search-icon {
  color: var(--color-text-muted);
  font-size: 15px;
}

.sidebar-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-4) var(--space-1-5);
  color: var(--color-text-muted);
  font-family: var(--font-interface);
  font-size: var(--text-2xs);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.archive-toggle {
  /* Text-sized label with an expanded pointer target. */
  margin: -4px -6px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-research);
  cursor: pointer;
  font-size: var(--text-2xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  padding: 4px 6px;
  text-transform: none;
}

.archive-toggle:hover {
  background: var(--color-hover-tint);
}

.empty-sessions {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2-5);
  padding: var(--space-12) var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.empty-sessions__icon {
  color: var(--color-border-strong);
  font-size: 32px;
}

.session-scroll {
  /* Flex column child: without min-height the list grows to its content
     height and pushes the account area below the viewport once there are
     more sessions than fit. Allow it to shrink; the virtual list scrolls. */
  min-height: 0;
  scrollbar-width: none;
  scrollbar-color: transparent transparent;
}

/* NVirtualList 需要有界高度才启用虚拟滚动；NSpin 的内容层默认无高度，
   会把列表撑到内容全高，再被上层 overflow-hidden 裁掉——正是侧栏滑不动的根因 */
.session-scroll :deep(.n-spin-content),
.session-scroll :deep(.n-spin-container) {
  height: 100%;
}

.session-virtual-list {
  height: 100%;
}

.session-scroll--overflowing {
  scrollbar-width: none;
  scrollbar-color: transparent transparent;
}

.session-scroll::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.session-scroll--overflowing:hover {
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--color-text-muted) 38%, transparent) transparent;
}

.session-scroll--overflowing:hover::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.session-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.session-scroll::-webkit-scrollbar-thumb {
  min-height: 40px;
  border: 2px solid transparent;
  border-radius: 999px;
  background-color: transparent;
  background-clip: content-box;
}

.session-scroll--overflowing:hover::-webkit-scrollbar-thumb {
  background-color: color-mix(in srgb, var(--color-text-muted) 38%, transparent);
}

.session-scroll::-webkit-scrollbar-thumb:hover {
  background-color: color-mix(in srgb, var(--color-text-muted) 54%, transparent);
}

.session-item {
  display: flex;
  height: 100%;
  min-height: 56px;
  cursor: pointer;
  align-items: center;
  gap: var(--space-2);
  margin: 1px var(--space-2);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-2-5);
  color: var(--color-text);
  transition:
    background var(--duration-fast) var(--ease-standard),
    border-color var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.session-item:hover {
  background: var(--color-hover-tint);
}

/* Row actions stay 24px even though Naive renders text buttons smaller. */
.session-item :deep(.n-button) {
  min-width: 24px;
  min-height: 24px;
}

.session-item--active {
  border-color: var(--color-border);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  color: var(--color-research);
  font-weight: 600;
}

/* Same breathing language as the message-flow sparkle: this conversation
   has a turn streaming right now. */
.session-item__generating {
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
  color: var(--color-research);
  animation: session-generating-sparkle 1.5s ease-in-out infinite;
}

.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-base);
  font-weight: 500;
}

.session-item--active .session-title {
  font-weight: 600;
}

.session-date {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-family: var(--font-utility);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  line-height: var(--leading-tight);
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  border-top: 1px solid var(--color-border);
  padding: var(--space-2-5) var(--space-3) var(--space-3);
}

.account-button {
  display: flex;
  min-width: 0;
  min-height: 44px;
  flex: 1 1 0;
  align-items: center;
  gap: var(--space-2);
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  padding: var(--space-1-5) var(--space-2);
  text-align: left;
  transition:
    background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.account-button:hover,
.account-button--active {
  background: var(--color-hover-tint);
  color: var(--color-primary);
}

.avatar-identicon {
  display: grid;
  position: relative;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  grid-template-columns: repeat(5, 1fr);
  grid-template-rows: repeat(5, 1fr);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-elevated);
  box-shadow: var(--shadow-card);
  gap: 1px;
  padding: 4px;
}

.avatar-identicon__cell {
  border-radius: 1px;
}

.avatar-identicon__cell--filled {
  background: var(--avatar-fill, #57606a);
}

.account-copy {
  display: grid;
  min-width: 0;
  flex: 1 1 0;
  gap: 1px;
}

.account-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-base);
  font-weight: 600;
}

.account-copy small {
  overflow: hidden;
  color: var(--color-text-muted);
  font-family: var(--font-interface);
  font-size: var(--text-xs);
  line-height: var(--leading-tight);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-button__icon {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 15px;
  opacity: 0;
  transition:
    color var(--duration-fast) var(--ease-standard),
    opacity var(--duration-fast) var(--ease-standard);
}

.account-button:hover .account-button__icon,
.account-button--active .account-button__icon {
  color: var(--color-primary);
  opacity: 1;
}

.logout-button {
  display: inline-flex;
  width: var(--control-icon-size);
  height: var(--control-icon-size);
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 16px;
  transition:
    background var(--duration-fast) var(--ease-standard),
    color var(--duration-fast) var(--ease-standard);
}

.logout-button:hover {
  background: color-mix(in srgb, var(--color-error) 10%, transparent);
  color: var(--color-error);
}

.dark .chat-sidebar {
  border-right-color: var(--color-border);
  background: var(--color-bg);
}

.dark .sidebar-section-header,
.dark .session-date {
  color: var(--color-text-muted);
}

.dark .session-item {
  color: var(--color-text);
}

.dark .session-item:hover {
  background: var(--color-hover-tint);
}

/* Row actions stay 24px even though Naive renders text buttons smaller. */
.session-item :deep(.n-button) {
  min-width: 24px;
  min-height: 24px;
}

.dark .session-item--active {
  border-color: var(--color-border);
  background: var(--color-surface-alt);
  color: var(--color-research);
}

.dark .session-scroll--overflowing:hover {
  scrollbar-color: color-mix(in srgb, var(--color-primary) 58%, transparent) transparent;
}

.dark .session-scroll--overflowing:hover::-webkit-scrollbar-thumb {
  background-color: color-mix(in srgb, var(--color-primary) 58%, transparent);
}

.dark .session-scroll::-webkit-scrollbar-thumb:hover {
  background-color: color-mix(in srgb, var(--color-primary) 74%, transparent);
}

.dark .sidebar-footer {
  border-top-color: var(--color-border);
}

.dark .account-copy small {
  color: var(--color-text-muted);
}

.dark .account-button,
.dark .logout-button,
.dark .library-button {
  color: var(--color-text-muted);
}

.dark .account-button:hover,
.dark .account-button--active,
.dark .library-button:hover,
.dark .library-button--active,
.dark .logout-button:hover {
  background: var(--color-primary-soft-bg);
  color: var(--color-text);
}

.dark .avatar-identicon {
  background: var(--color-surface-elevated);
  box-shadow: var(--shadow-card);
}

@keyframes session-generating-sparkle {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.86);
  }

  50% {
    opacity: 1;
    transform: scale(1.06);
  }
}

@media (prefers-reduced-motion: reduce) {
  .session-item__generating {
    animation: session-generating-breath 2.4s ease-in-out infinite;
  }
}

@keyframes session-generating-breath {
  0%,
  100% {
    opacity: 0.45;
  }

  50% {
    opacity: 1;
  }
}
</style>
