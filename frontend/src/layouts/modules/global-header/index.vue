<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useFullscreen } from '@vueuse/core';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalSearch from '../global-search/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';

defineOptions({
  name: 'GlobalHeader'
});

interface Props {
  /** Whether to show the logo */
  // showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  // showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const router = useRouter();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;

function goToChat() {
  router.push('/chat');
}
</script>

<template>
  <DarkModeContainer class="global-header-shell h-full flex-y-center justify-between bg-transparent">
    <div id="header-extra" class="h-full flex-col justify-center"></div>
    <!-- <GlobalLogo v-if="showLogo" class="h-full" :style="{ width: themeStore.sider.width + 'px' }" /> -->
    <MenuToggler
      v-if="showMenuToggler && appStore.isMobile"
      :collapsed="appStore.siderCollapse"
      @click="appStore.toggleSiderCollapse"
    />
    <!--
    <div v-if="showMenu" :id="GLOBAL_HEADER_MENU_ID" class="h-full flex-y-center flex-1-hidden"></div>
    <div v-else class="h-full flex-y-center flex-1-hidden">
      <GlobalBreadcrumb v-if="!appStore.isMobile" class="ml-12px" />
    </div>
-->
    <div class="global-header-actions h-full flex-y-center justify-end gap-2 px-5">
      <NButton
        size="small"
        secondary
        aria-label="返回 Chat"
        title="返回 Chat"
        class="chatbot-back-button"
        @click="goToChat"
      >
        <template #icon>
          <icon-lucide:message-circle class="text-icon" />
        </template>
        <span class="chatbot-back-button__label">Chat</span>
      </NButton>
      <GlobalSearch />
      <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        @change-lang="appStore.changeLocale"
      />
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :is-dark="themeStore.darkMode"
        @switch="themeStore.toggleThemeScheme"
      />
      <ThemeButton v-if="isDev" />
      <UserAvatar />
    </div>
  </DarkModeContainer>
</template>

<style scoped>
.chatbot-back-button {
  --n-border-color: var(--color-border);
  --n-color: var(--color-card-band);
  --n-color-hover: var(--color-card-band-pressed);
  --n-color-pressed: #dfdfdd;
  --n-text-color: var(--color-text-muted);
  --n-text-color-hover: var(--color-text);
  --n-text-color-pressed: var(--color-text);
  font-weight: 600;
}

.global-header-shell {
  border-bottom: 1px solid var(--color-border);
  background: rgb(255 255 255 / 92%);
}

@media (max-width: 640px) {
  .global-header-shell {
    margin-left: 0;
    min-width: 0;
  }

  .global-header-actions {
    min-width: 0;
    gap: 6px;
    padding-right: 8px;
    padding-left: 8px;
  }

  .chatbot-back-button {
    width: 36px;
    padding: 0;
  }

  .chatbot-back-button__label {
    display: none;
  }
}
</style>
