<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';

defineOptions({
  name: 'ConversationSidebar'
});

const collapsed = defineModel<boolean>('collapsed', { default: false });

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

function handleNewChat() {
  chatStore.createNewSession();
}

function handleSelect(cid: string) {
  chatStore.switchSession(cid);
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

const adminWorkbenchRoutes = [
  'user',
  'org-tag',
  'model-provider',
  'invite-code',
  'usage-monitor',
  'chat-history',
  'recharge-manage'
];

const isWorkbenchActive = computed(() => {
  return ['knowledge-base', 'personal-center', ...adminWorkbenchRoutes].includes(String(route.name || ''));
});

const workbenchSubtitle = computed(() => {
  return 'library / taxonomy / experiments';
});

async function handleWorkbenchClick() {
  await router.push({ name: authStore.isAdmin ? 'user' : 'knowledge-base' });
  collapsed.value = true;
}

function handleLogout() {
  authStore.logout();
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
  <div class="chat-sidebar" :class="collapsed ? 'w-0 min-w-0 border-r-0' : 'w-[292px] min-w-[292px]'">
    <div class="w-[292px] flex flex-col flex-1 overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
      <div class="brand-row">
        <div class="brand-mark">
          <SystemLogo class="text-36px" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="brand-title">PaperLoom</div>
          <div class="brand-subtitle">structured paper reading</div>
        </div>
        <NButton text size="tiny" class="collapse-button" @click="handleCollapse">
          <template #icon>
            <icon-material-symbols:left-panel-close-outline-rounded />
          </template>
        </NButton>
      </div>

      <div class="px-3">
        <NButton type="primary" block class="new-chat-button" @click="handleNewChat">
          <template #icon>
            <icon-material-symbols:add-rounded />
          </template>
          New Query
        </NButton>
      </div>

      <div class="px-3 pt-3">
        <NInput v-model:value="keyword" clearable size="small" placeholder="搜索 query / session">
          <template #prefix>
            <icon-material-symbols:search-rounded class="text-16px text-#a0aec0" />
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
              <icon-material-symbols:chat-outline-rounded class="text-36px color-#ccc dark:color-#444" />
              <span>{{ showArchived ? '暂无归档 query' : '暂无 query 记录' }}</span>
            </div>

            <div
              v-for="session in visibleSessions"
              :key="session.conversationId"
              class="session-item group"
              :class="session.conversationId === conversationId ? 'session-item--active' : ''"
              @click="handleSelect(session.conversationId)"
            >
              <div class="min-w-0 flex-1">
                <div class="session-title">
                  {{ session.title }}
                </div>
                <div class="session-date">{{ formatDate(session.updatedAt) }}</div>
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
                      <icon-material-symbols:archive-outline-rounded class="text-15px color-#999 hover:color-#666" />
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
                  <icon-material-symbols:unarchive-outline-rounded class="text-15px color-#999 hover:color-#666" />
                </template>
              </NButton>
            </div>
          </TransitionGroup>
        </NSpin>
      </div>

      <div class="sidebar-footer">
        <button
          type="button"
          class="footer-action footer-action--workbench"
          :class="{ 'footer-action--active': isWorkbenchActive }"
          aria-label="进入工作台"
          @click="handleWorkbenchClick"
        >
          <icon-mdi-bookshelf class="footer-action__icon" />
          <span class="footer-action__copy">
            <strong>Paper Library</strong>
            <small>{{ workbenchSubtitle }}</small>
          </span>
        </button>

        <button type="button" class="footer-action footer-action--danger" @click="handleLogout">
          <icon-material-symbols:logout-rounded class="footer-action__icon" />
          <span class="footer-action__copy">
            <strong>Logout</strong>
          </span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-sidebar {
  position: relative;
  height: 100%;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #c9c1b2;
  background: #e2dccc;
  transition:
    width 0.2s ease,
    min-width 0.2s ease;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 12px 12px;
}

.brand-mark {
  display: flex;
  height: 42px;
  width: 42px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9c1b2;
  border-radius: 6px;
  background: #fbfaf6;
  box-shadow: 4px 4px 0 rgba(201, 193, 178, 0.8);
  color: #26364a;
}

.brand-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #26364a;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 18px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 1px;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.collapse-button {
  color: #5e6470;
}

.new-chat-button {
  height: 38px;
  border-radius: 6px;
  font-weight: 700;
}

.sidebar-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 14px 8px;
  color: #5e6470;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.archive-toggle {
  border: 0;
  background: transparent;
  color: #26364a;
  cursor: pointer;
  font-size: 12px;
}

.empty-sessions {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 12px;
  color: #747a84;
  font-size: 13px;
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
  margin: 2px 4px;
  display: flex;
  cursor: pointer;
  align-items: center;
  gap: 10px;
  border-left: 3px solid transparent;
  border-radius: 6px;
  padding: 10px 10px;
  color: #20242a;
  box-shadow: 0 0 0 transparent;
  transition:
    background 0.16s ease,
    color 0.16s ease,
    border-left-color 0.16s ease,
    box-shadow 0.16s ease;
}

.session-item:hover {
  background: rgba(255, 253, 248, 0.88);
  border-left-color: rgba(126, 63, 70, 0.42);
}

.session-item--active {
  background: #fbfaf6;
  color: #26364a;
  border-left-color: #7e3f46;
  box-shadow: 2px 2px 0 rgba(201, 193, 178, 0.42);
}

.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 650;
}

.session-date {
  margin-top: 2px;
  color: #747a84;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
}

.sidebar-footer {
  display: grid;
  gap: 6px;
  border-top: 1px solid #c9c1b2;
  padding: 10px 10px 12px;
}

.footer-action {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #394150;
  cursor: pointer;
  padding: 0 10px;
  text-align: left;
  transition:
    background 0.16s ease,
    color 0.16s ease,
    box-shadow 0.16s ease;
}

.footer-action:hover {
  background: rgba(255, 253, 248, 0.8);
  color: #26364a;
}

.footer-action--active {
  background: #e2dccc;
  color: #26364a;
  box-shadow: inset 3px 0 0 #26364a;
}

.footer-action--workbench {
  min-height: 54px;
  border: 1px solid #c9c1b2;
  background: #fbfaf6;
}

.footer-action__icon {
  flex: 0 0 auto;
  font-size: 18px;
}

.footer-action__copy {
  display: grid;
  min-width: 0;
  flex: 1 1 0;
  gap: 1px;
}

.footer-action__copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 700;
}

.footer-action__copy small {
  overflow: hidden;
  color: #747a84;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.footer-action--danger:hover {
  background: rgba(245, 34, 45, 0.08);
  color: #d03050;
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
  border-right-color: rgba(255, 255, 255, 0.08);
  background: #161a21;
}

.dark .brand-mark {
  background: rgba(255, 253, 248, 0.08);
  box-shadow: none;
}

.dark .brand-subtitle,
.dark .sidebar-section-header,
.dark .session-date {
  color: #c6bba7;
}

.dark .session-item {
  color: #ede9df;
}

.dark .session-item:hover {
  background: rgba(38, 54, 74, 0.18);
  border-left-color: rgba(212, 165, 165, 0.42);
}

.dark .session-item--active {
  background: rgba(38, 54, 74, 0.34);
  color: #ede9df;
  border-left-color: #d4a5a5;
  box-shadow: 2px 2px 0 rgba(0, 0, 0, 0.32);
}

.dark .session-scroll--overflowing:hover {
  scrollbar-color: rgba(38, 54, 74, 0.34) transparent;
}

.dark .session-scroll--overflowing:hover::-webkit-scrollbar-thumb {
  background-color: rgba(38, 54, 74, 0.36);
}

.dark .session-scroll::-webkit-scrollbar-thumb:hover {
  background-color: rgba(38, 54, 74, 0.52);
}

.dark .sidebar-footer {
  border-top-color: rgba(255, 255, 255, 0.08);
}

.dark .footer-action__copy small {
  color: #bfb29d;
}

.dark .footer-action {
  color: #e5d8c2;
}

.dark .footer-action:hover,
.dark .footer-action--active {
  background: rgba(38, 54, 74, 0.2);
  color: #ede9df;
}

.dark .footer-action--workbench {
  background: rgba(38, 54, 74, 0.14);
}
</style>
