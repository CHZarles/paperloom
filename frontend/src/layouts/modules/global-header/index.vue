<script setup lang="ts">
import { useFullscreen } from '@vueuse/core';
import { useRouter } from 'vue-router';
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

function goToChatbot() {
  router.push('/chat');
}
</script>

<template>
  <DarkModeContainer class="ml-12 h-full flex-y-center justify-between bg-transparent">
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
    <div class="h-full flex-y-center justify-end px-6 gap-2">
      <NButton
        size="small"
        secondary
        aria-label="返回 Chatbot"
        title="返回 Chatbot"
        class="chatbot-back-button"
        @click="goToChatbot"
      >
        <template #icon>
          <icon-mdi-robot-outline class="text-icon" />
        </template>
        Chatbot
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
  --n-border-color: #c9c1b2;
  --n-color: #fbfaf6;
  --n-color-hover: #f1ebd9;
  --n-color-pressed: #e2dccc;
  --n-text-color: #5e6470;
  --n-text-color-hover: #26364a;
  --n-text-color-pressed: #26364a;
  font-weight: 600;
}
</style>
