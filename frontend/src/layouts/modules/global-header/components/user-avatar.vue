<script setup lang="ts">
import { computed, ref } from 'vue';
import type { VNode } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useSvgIcon } from '@/hooks/common/icon';
import { $t } from '@/locales';
import SettingsPanel from '@/views/personal-center/modules/settings-panel.vue';

defineOptions({
  name: 'UserAvatar'
});

const authStore = useAuthStore();
const { toLogin } = useRouterPush();
const { SvgIconVNode } = useSvgIcon();
const settingsVisible = ref(false);
const settingsModalHostStyle = { width: 'min(1480px, calc(100vw - 32px))' };

function loginOrRegister() {
  toLogin();
}

type DropdownKey = 'personal-center' | 'logout';

type DropdownOption =
  | {
      key: DropdownKey;
      label: string;
      icon?: () => VNode;
    }
  | {
      type: 'divider';
      key: string;
    };

const options = computed(() => {
  const opts: DropdownOption[] = [
    {
      label: '管理页面',
      key: 'personal-center',
      icon: SvgIconVNode({ icon: 'lucide:settings', fontSize: 18 })
    },
    {
      type: 'divider',
      key: 'divider-account'
    },
    {
      label: $t('common.logout'),
      key: 'logout',
      icon: SvgIconVNode({ icon: 'lucide:log-out', fontSize: 18 })
    }
  ];

  return opts;
});

const accountName = computed(() => authStore.userInfo.username || 'Folio User');
const avatarCells = computed(() => buildAvatarCells(accountName.value));
const avatarStyle = computed(() => ({
  '--avatar-fill': buildAvatarFill(accountName.value)
}));

function logout() {
  window.$dialog?.info({
    title: $t('common.tip'),
    content: $t('common.logoutConfirm'),
    positiveText: $t('common.confirm'),
    negativeText: $t('common.cancel'),
    onPositiveClick: async () => {
      await authStore.logout();
    }
  });
}

function handleDropdown(key: DropdownKey) {
  if (key === 'logout') {
    logout();
  } else {
    settingsVisible.value = true;
  }
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
  <NButton v-if="!authStore.isLogin" quaternary @click="loginOrRegister">
    {{ $t('page.login.common.loginOrRegister') }}
  </NButton>
  <NDropdown v-else placement="bottom" trigger="click" :options="options" @select="handleDropdown">
    <div>
      <ButtonIcon>
        <span class="avatar-identicon" :style="avatarStyle" aria-hidden="true">
          <span
            v-for="(filled, index) in avatarCells"
            :key="index"
            class="avatar-identicon__cell"
            :class="{ 'avatar-identicon__cell--filled': filled }"
          />
        </span>
        <span class="user-avatar__name text-14px font-medium">{{ accountName }}</span>
      </ButtonIcon>
    </div>
  </NDropdown>
  <NModal
    v-model:show="settingsVisible"
    :auto-focus="false"
    class="settings-modal-host"
    :style="settingsModalHostStyle"
  >
    <SettingsPanel @close="settingsVisible = false" />
  </NModal>
</template>

<style scoped>
.settings-modal-host {
  width: min(1480px, calc(100vw - 32px));
}

.avatar-identicon {
  display: grid;
  width: 28px;
  height: 28px;
  grid-template-columns: repeat(5, 1fr);
  grid-template-rows: repeat(5, 1fr);
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 7px;
  background: #f6f8fa;
  box-shadow: 0 1px 2px rgb(15 23 42 / 8%);
  gap: 1px;
  padding: 4px;
}

.avatar-identicon__cell {
  border-radius: 1px;
}

.avatar-identicon__cell--filled {
  background: var(--avatar-fill, #57606a);
}

@media (max-width: 640px) {
  .user-avatar__name {
    display: none;
  }
}
</style>
