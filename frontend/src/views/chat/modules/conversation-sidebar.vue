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
const { conversationId, sessions, sessionsLoading } = storeToRefs(chatStore);
const keyword = ref('');
const showArchived = ref(false);
const sessionScrollRef = ref<HTMLElement | null>(null);
const isSessionOverflowing = ref(false);
let resizeObserver: ResizeObserver | null = null;

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
const archivedCount = computed(() => sessions.value.filter(item => item.status === 'ARCHIVED').length);
const accountName = computed(() => authStore.userInfo.username || 'Folio User');
const accountRole = computed(() => (authStore.isAdmin ? 'Admin management' : 'Workspace settings'));
const avatarCells = computed(() => buildAvatarCells(accountName.value));
const avatarStyle = computed(() => ({
  '--avatar-fill': buildAvatarFill(accountName.value)
}));

onMounted(() => {
  chatStore.loadSessions();
  syncSessionOverflow();
  window.addEventListener('resize', syncSessionOverflow);

  if (sessionScrollRef.value) {
    resizeObserver = new ResizeObserver(syncSessionOverflow);
    resizeObserver.observe(sessionScrollRef.value);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncSessionOverflow);
  resizeObserver?.disconnect();
});

watch(
  () => [visibleSessions.value.length, sessionsLoading.value, collapsed.value],
  () => {
    syncSessionOverflow();
  },
  { flush: 'post' }
);

function syncSessionOverflow() {
  nextTick(() => {
    const el = sessionScrollRef.value;
    if (!el) return;

    isSessionOverflowing.value = el.scrollHeight > el.clientHeight + 8;

    if (!isSessionOverflowing.value) {
      el.scrollTop = 0;
    }
  });
}

function handleCollapse() {
  collapsed.value = true;
}

async function handleNewChat() {
  const createdConversationId = await chatStore.createNewSession();
  if (!createdConversationId) return;
  await chatStore.loadSessions({ silent: true });

  if (route.name !== 'chat') {
    await router.push({ name: 'chat' });
  }
}

async function handleSelect(cid: string) {
  await chatStore.switchSession(cid);

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

async function handleWorkbenchClick() {
  await router.push({ name: 'knowledge-base' });
}

async function handleAccountClick() {
  emit('openSettings');
}

function handleLogout() {
  authStore.logout();
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
          <div class="brand-title">Folio</div>
        </div>
        <NButton text size="tiny" class="collapse-button" @click="handleCollapse">
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
        <NButton type="primary" block class="new-chat-button" @click="handleNewChat">
          <template #icon>
            <icon-lucide:plus />
          </template>
          New Query
        </NButton>
      </div>

      <div class="sidebar-search">
        <NInput v-model:value="keyword" clearable size="small" placeholder="Search chats">
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

      <div
        ref="sessionScrollRef"
        class="session-scroll flex-1 px-2 pb-2"
        :class="isSessionOverflowing ? 'session-scroll--overflowing overflow-y-auto' : 'overflow-hidden'"
      >
        <NSpin :show="sessionsLoading" class="h-full">
          <TransitionGroup name="session-list" tag="div">
            <div v-if="visibleSessions.length === 0 && !sessionsLoading" class="empty-sessions">
              <icon-lucide:message-circle class="empty-sessions__icon" />
              <span>{{ showArchived ? '暂无归档 query' : '暂无 query 记录' }}</span>
            </div>

            <div
              v-for="session in visibleSessions"
              :key="session.conversationId"
              data-testid="conversation-session"
              :data-conversation-id="session.conversationId"
              class="session-item group"
              :class="session.conversationId === conversationId ? 'session-item--active' : ''"
              @click="handleSelect(session.conversationId)"
            >
              <icon-lucide:circle-dot v-if="session.conversationId === conversationId" class="shrink-0 text-12px" />
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
                    class="shrink-0 transition-opacity"
                    :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                    text
                    size="tiny"
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
                class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
                text
                size="tiny"
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
                    class="shrink-0 transition-opacity"
                    :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                    text
                    size="tiny"
                    :aria-label="`删除 ${session.title}`"
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
          </TransitionGroup>
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
  border-right: 1px solid color-mix(in srgb, var(--color-border) 74%, transparent);
  background: color-mix(in srgb, var(--color-surface) 88%, #eef1ec);
  transition:
    width 0.2s ease,
    min-width 0.2s ease;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 10px 10px;
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
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 15px;
  font-weight: 720;
  letter-spacing: 0;
}

.collapse-button {
  color: var(--color-text-muted);
}

.sidebar-actions {
  display: grid;
  gap: 6px;
  padding: 0 10px;
}

.new-chat-button {
  height: 38px;
  border-radius: 8px;
  font-weight: 720;
}

.library-button {
  display: flex;
  width: 100%;
  height: 34px;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
  padding: 0 10px;
  text-align: left;
  transition:
    background 0.16s ease,
    color 0.16s ease;
}

.library-button:hover,
.library-button--active {
  background: color-mix(in srgb, var(--color-surface) 88%, var(--color-primary) 12%);
  color: var(--color-primary);
}

.library-button__icon {
  flex: 0 0 auto;
  font-size: 17px;
}

.sidebar-search {
  padding: 10px 10px 0;
}

.sidebar-search :deep(.n-input) {
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-surface) 82%, transparent);
}

.search-icon {
  color: var(--color-text-muted);
  font-size: 15px;
}

.sidebar-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 14px 6px;
  color: var(--color-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
  font-weight: 680;
  letter-spacing: 0;
}

.archive-toggle {
  border: 0;
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 650;
}

.empty-sessions {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 12px;
  color: var(--color-text-muted);
  font-size: 13px;
}

.empty-sessions__icon {
  color: var(--color-border-soft);
  font-size: 34px;
}

.session-scroll {
  scrollbar-width: none;
  scrollbar-color: transparent transparent;
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
  scrollbar-color: rgba(38, 54, 74, 0.28) transparent;
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
  background-color: rgba(38, 54, 74, 0.28);
}

.session-scroll::-webkit-scrollbar-thumb:hover {
  background-color: rgba(38, 54, 74, 0.42);
}

.session-item {
  display: flex;
  min-height: 38px;
  cursor: pointer;
  align-items: center;
  gap: 9px;
  margin: 1px 2px;
  border: 0;
  border-radius: 8px;
  padding: 8px 9px;
  color: var(--color-text);
  transition:
    background 0.16s ease,
    color 0.16s ease;
}

.session-item:hover {
  background: color-mix(in srgb, var(--color-surface) 84%, transparent);
}

.session-item--active {
  background: color-mix(in srgb, var(--color-surface) 80%, var(--color-primary) 12%);
  color: var(--color-primary);
  font-weight: 600;
}

.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 650;
}

.session-date {
  margin-top: 1px;
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
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  border-top: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  padding: 8px 10px 10px;
}

.account-button {
  display: flex;
  min-width: 0;
  min-height: 42px;
  flex: 1 1 0;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  padding: 5px 7px;
  text-align: left;
  transition:
    background 0.16s ease,
    color 0.16s ease;
}

.account-button:hover,
.account-button--active {
  background: color-mix(in srgb, var(--color-surface) 86%, transparent);
  color: var(--color-primary);
}

.avatar-identicon {
  display: grid;
  position: relative;
  width: 31px;
  height: 31px;
  flex: 0 0 auto;
  grid-template-columns: repeat(5, 1fr);
  grid-template-rows: repeat(5, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  border-radius: 7px;
  background: #f6f8fa;
  box-shadow:
    0 1px 0 rgb(255 255 255 / 90%) inset,
    0 1px 2px rgb(15 23 42 / 8%);
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
  gap: 2px;
}

.account-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 680;
}

.account-copy small {
  overflow: hidden;
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
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-button__icon {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: 15px;
  opacity: 0;
  transition:
    color 0.16s ease,
    opacity 0.16s ease;
}

.account-button:hover .account-button__icon,
.account-button--active .account-button__icon {
  color: var(--color-primary);
  opacity: 1;
}

.logout-button {
  display: inline-flex;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 16px;
  transition:
    background 0.16s ease,
    color 0.16s ease;
}

.logout-button:hover {
  background: color-mix(in srgb, var(--color-accent-soft-bg) 78%, transparent);
  color: var(--color-error);
}

.session-list-enter-active,
.session-list-leave-active {
  transition: all 0.2s ease;
}
.session-list-enter-from,
.session-list-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}

.dark .chat-sidebar {
  border-right-color: color-mix(in srgb, var(--color-border) 80%, transparent);
  background: color-mix(in srgb, var(--color-bg) 88%, #111827);
}

.dark .sidebar-section-header,
.dark .session-date {
  color: var(--color-text-muted);
}

.dark .session-item {
  color: var(--color-text);
}

.dark .session-item:hover {
  background: var(--color-primary-soft-bg);
}

.dark .session-item--active {
  background: var(--color-primary-soft-bg);
  color: var(--color-text);
}

.dark .session-scroll--overflowing:hover {
  scrollbar-color: var(--color-primary) transparent;
}

.dark .session-scroll--overflowing:hover::-webkit-scrollbar-thumb {
  background-color: var(--color-primary);
}

.dark .session-scroll::-webkit-scrollbar-thumb:hover {
  background-color: var(--color-primary);
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
  background: #161b22;
  box-shadow: none;
}
</style>
