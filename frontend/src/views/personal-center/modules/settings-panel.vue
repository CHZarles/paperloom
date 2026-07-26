<script setup lang="ts">
import { computed, defineAsyncComponent, h, onMounted, ref, watch } from 'vue';
import { NTag } from 'naive-ui';

const InviteCodePage = defineAsyncComponent(() => import('@/views/invite-code/index.vue'));
const UserPage = defineAsyncComponent(() => import('@/views/user/index.vue'));

const settingsPanelCacheKey = Symbol.for('paperloom.settingsPanelCache');

type SettingsPanelCache = {
  userId: string;
  profileLoaded: boolean;
};

type SettingsPanelGlobal = typeof globalThis & {
  [settingsPanelCacheKey]?: SettingsPanelCache | null;
};

type SettingsSection =
  | 'general'
  | 'userAdmin'
  | 'inviteCode';

const adminSections = new Set<SettingsSection>([
  'userAdmin',
  'inviteCode'
]);

const emit = defineEmits<{
  close: [];
}>();

const authStore = useAuthStore();
const { userInfo } = storeToRefs(authStore);

const profileLoading = ref(false);
const profileLoaded = ref(false);

const activeSection = ref<SettingsSection>('general');

const userCacheId = computed(() => String(userInfo.value.id || userInfo.value.username || 'anonymous'));
const accountName = computed(() => userInfo.value.username || 'Folio User');
const accountRoleLabel = computed(() => (userInfo.value.role === 'ADMIN' ? 'Admin' : 'User'));
const avatarCells = computed(() => buildAvatarCells(accountName.value));
const avatarStyle = computed(() => ({
  '--account-avatar-fill': buildAvatarFill(accountName.value)
}));

onMounted(() => {
  restoreCachedSettings();
});

watch(
  () => authStore.isAdmin,
  isAdmin => {
    if (!isAdmin && isAdminSection(activeSection.value)) {
      activeSection.value = 'general';
    }
  }
);

function isAdminSection(section: SettingsSection) {
  return adminSections.has(section);
}

function restoreCachedSettings() {
  const cachedSettings = readSettingsPanelCache();
  if (!cachedSettings || cachedSettings.userId !== userCacheId.value) {
    return;
  }

  profileLoaded.value = cachedSettings.profileLoaded;
}

function cacheSettingsPanelState() {
  (globalThis as SettingsPanelGlobal)[settingsPanelCacheKey] = {
    userId: userCacheId.value,
    profileLoaded: profileLoaded.value
  };
}

function readSettingsPanelCache() {
  return (globalThis as SettingsPanelGlobal)[settingsPanelCacheKey] || null;
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
</script>

<template>
  <div class="settings-spin">
    <div class="settings-modal" data-testid="settings-modal" :data-profile-loading="profileLoading && !profileLoaded">
      <aside class="settings-nav">
        <button type="button" class="settings-account">
          <span class="account-avatar" :style="avatarStyle" aria-hidden="true">
            <span
              v-for="(filled, index) in avatarCells"
              :key="index"
              class="account-avatar__cell"
              :class="{ 'account-avatar__cell--filled': filled }"
            />
          </span>
          <span class="account-copy">
            <strong>{{ accountName }}</strong>
            <small>{{ accountRoleLabel }}</small>
          </span>
        </button>

        <div class="settings-nav__label">Account</div>
        <button
          type="button"
          class="settings-nav__item"
          :class="{ 'settings-nav__item--active': activeSection === 'general' }"
          @click="activeSection = 'general'"
        >
          <icon-lucide:settings />
          <span>General</span>
        </button>

        <template v-if="authStore.isAdmin">
          <div class="settings-nav__label">Admin</div>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'userAdmin' }"
            @click="activeSection = 'userAdmin'"
          >
            <icon-lucide:users />
            <span>User Management</span>
          </button>
          <button
            type="button"
            class="settings-nav__item"
            :class="{ 'settings-nav__item--active': activeSection === 'inviteCode' }"
            @click="activeSection = 'inviteCode'"
          >
            <icon-lucide:ticket />
            <span>Invite Codes</span>
          </button>
        </template>
      </aside>

      <main class="settings-main">
        <button type="button" class="settings-close" aria-label="关闭设置" @click="emit('close')">
          <icon-lucide:x />
        </button>

        <section v-show="activeSection === 'general'" class="settings-section">
          <h1>General</h1>
          <div class="settings-rule" />
          <div class="settings-row">
            <label>Username</label>
            <strong>{{ accountName }}</strong>
          </div>
          <div class="settings-row">
            <label>Role</label>
            <strong>{{ accountRoleLabel }}</strong>
          </div>
        </section>

        <section
          v-if="authStore.isAdmin && activeSection === 'userAdmin'"
          class="settings-section settings-section--embedded"
        >
          <div id="header-extra" class="settings-embedded-header-extra" />
          <UserPage />
        </section>
        <section
          v-if="authStore.isAdmin && activeSection === 'inviteCode'"
          class="settings-section settings-section--embedded"
        >
          <InviteCodePage />
        </section>
      </main>
    </div>

      </div>
</template>

<style scoped lang="scss">
.settings-spin {
  width: min(1480px, calc(100vw - 32px));
}

.settings-modal {
  display: grid;
  width: min(1480px, calc(100vw - 32px));
  height: min(830px, calc(100vh - 92px));
  grid-template-columns: 260px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 16px;
  background: var(--color-surface);
  box-shadow: var(--shadow-card-soft);
}

.settings-nav {
  min-width: 0;
  border-right: 1px solid var(--color-border);
  background: var(--color-bg);
  padding: 16px 10px;
}

.settings-account,
.settings-nav__item {
  display: flex;
  width: 100%;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
}

.settings-account {
  min-width: 0;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 6px 8px;
}

.account-avatar {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  grid-template-columns: repeat(5, 1fr);
  grid-template-rows: repeat(5, 1fr);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-border) 72%, transparent);
  border-radius: 8px;
  background: var(--color-surface-elevated);
  box-shadow:
    0 1px 0 color-mix(in srgb, var(--color-text) 8%, transparent) inset,
    var(--shadow-card);
  gap: 1px;
  padding: 5px;
}

.account-avatar__cell {
  border-radius: 1px;
}

.account-avatar__cell--filled {
  background: var(--account-avatar-fill, #57606a);
}

.account-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.account-copy strong,
.account-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy strong {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 680;
}

.account-copy small {
  color: var(--color-text-muted);
  font-size: 11px;
}

.settings-nav__label {
  margin: 18px 10px 7px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 680;
}

.settings-nav__item {
  align-items: center;
  gap: 9px;
  height: 34px;
  margin: 1px 0;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 620;
  transition:
    background-color 0.16s ease,
    color 0.16s ease;
}

.settings-nav__item svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
  color: var(--color-text-muted);
}

.settings-nav__item span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-nav__item:hover,
.settings-nav__item--active {
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.settings-main {
  position: relative;
  min-width: 0;
  overflow-y: auto;
  padding: 42px 56px 48px;
}

.settings-close {
  position: absolute;
  top: 18px;
  right: 20px;
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.settings-close:hover {
  background: var(--color-surface-alt);
  color: var(--color-text);
}

.settings-section {
  min-width: 0;
  width: 100%;
}

.settings-embedded-header-extra {
  margin-bottom: 12px;
}

.settings-section h1 {
  margin: 0;
  color: var(--color-text);
  font-size: 22px;
  font-weight: 680;
  letter-spacing: 0;
  line-height: 1.2;
}

.settings-rule {
  height: 1px;
  margin: 23px 0 24px;
  background: var(--color-border);
}

.settings-row {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--color-border-soft);
}

.settings-row label {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 620;
}

.settings-row strong {
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 560;
  text-align: right;
}

.quota-list {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
}

.quota-panel {
  min-width: 0;
  border-bottom: 1px solid var(--color-border-soft);
  padding: 0 0 18px;
}

.quota-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.quota-panel__head h2,
.section-count {
  margin: 0 0 3px;
  color: var(--color-text);
  font-size: 15px;
  font-weight: 760;
  letter-spacing: 0;
  line-height: 1.2;
}

.quota-panel__head strong {
  color: var(--color-text);
  font-size: 20px;
  line-height: 1;
}

.quota-panel__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.quota-panel__metrics span {
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-alt);
  color: var(--color-text);
  font-size: 13px;
  font-weight: 700;
  padding: 8px 9px;
}

.quota-panel__metrics small {
  display: block;
  margin-bottom: 2px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 650;
}

.section-count {
  margin-bottom: 12px;
  color: var(--color-text-muted);
  font-weight: 620;
}

.settings-section :deep(.n-data-table) {
  --n-td-color: var(--color-surface) !important;
  --n-th-color: var(--color-card-band) !important;
  --n-border-color: var(--color-border) !important;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
}

.settings-section :deep(.n-data-table-wrapper) {
  min-width: 0;
}

.settings-section :deep(.n-data-table-th) {
  background: var(--color-card-band-pressed);
  color: var(--color-text);
  font-size: 12px;
  padding: 10px 10px;
}

.settings-section :deep(.n-data-table-td) {
  background: var(--color-surface);
  padding: 10px 10px;
  vertical-align: middle;
}

@media (max-width: 1120px) {
  .settings-modal {
    width: calc(100vw - 32px);
    height: calc(100vh - 48px);
  }
}

@media (max-width: 720px) {
  .settings-spin,
  .settings-modal {
    width: 100vw;
    height: 100vh;
  }

  .settings-modal {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
    border: 0;
    border-radius: 0;
  }

  .settings-nav {
    display: flex;
    gap: 4px;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
    padding: 8px 44px 8px 8px;
    scrollbar-width: none;
  }

  .settings-nav::-webkit-scrollbar {
    display: none;
  }

  .settings-account,
  .settings-nav__label {
    display: none;
  }

  .settings-nav__item {
    width: auto;
    flex: 0 0 auto;
    margin: 0;
    white-space: nowrap;
  }

  .settings-main {
    padding: 24px 18px;
  }

  .quota-panel__metrics {
    grid-template-columns: 1fr;
  }

  .settings-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
    padding: 12px 0;
  }

  .settings-row strong {
    text-align: left;
  }
}
</style>
