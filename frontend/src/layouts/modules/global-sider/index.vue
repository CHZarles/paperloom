<script setup lang="ts">
import { computed } from 'vue';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({
  name: 'GlobalSider'
});

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));
</script>

<template>
  <DarkModeContainer class="global-sider size-full flex-col-stretch" :inverted="darkMenu">
    <GlobalLogo
      v-if="showLogo"
      :show-title="!appStore.siderCollapse"
      :style="{ height: themeStore.header.height + 'px' }"
    />
    <div :id="GLOBAL_SIDER_MENU_ID" :class="menuWrapperClass"></div>
  </DarkModeContainer>
</template>

<style scoped>
.global-sider {
  border-right: 1px solid var(--color-border);
  background: #f0f0f0;
}

.global-sider :deep(.n-menu) {
  --n-item-height: 36px !important;
  --n-item-text-color: var(--color-text) !important;
  --n-item-text-color-hover: var(--color-text) !important;
  --n-item-text-color-active: var(--color-text) !important;
  --n-item-text-color-child-active: var(--color-text) !important;
  --n-item-icon-color: var(--color-text-muted) !important;
  --n-item-icon-color-hover: var(--color-text) !important;
  --n-item-icon-color-active: var(--color-text) !important;
  --n-item-color-active: #dfdfdd !important;
  --n-item-color-active-hover: #dfdfdd !important;
  --n-item-color-hover: #e7e7e5 !important;
  --n-arrow-color: var(--color-text-muted) !important;
  --n-arrow-color-active: var(--color-text) !important;
  padding: 4px 8px 48px;
}

.global-sider :deep(.n-menu-item-content) {
  margin: 1px 0;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 620;
}

.global-sider :deep(.n-menu-item-content__icon) {
  font-size: 17px;
}

.global-sider :deep(.n-menu .n-menu-item-content::before) {
  border-radius: 8px;
}

.dark .global-sider {
  background: var(--color-card-band);
}
</style>
