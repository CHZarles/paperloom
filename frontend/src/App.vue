<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { NConfigProvider } from 'naive-ui';
import type { GlobalThemeOverrides, WatermarkProps } from 'naive-ui';
import { useAppStore } from './store/modules/app';
import { useThemeStore } from './store/modules/theme';
import { naiveDateLocales, naiveLocales } from './locales/naive';

defineOptions({
  name: 'App'
});

const appStore = useAppStore();
const themeStore = useThemeStore();
const route = useRoute();

const naiveThemeOverrides = computed<GlobalThemeOverrides>(() => themeStore.naiveTheme);

const naiveLocale = computed(() => {
  return naiveLocales[appStore.locale];
});

const naiveDateLocale = computed(() => {
  return naiveDateLocales[appStore.locale];
});

const routeLayoutKey = computed(() => route.matched[0]?.path || route.path);

const watermarkProps = computed<WatermarkProps>(() => {
  return {
    content: themeStore.watermark.text,
    cross: true,
    fullscreen: true,
    fontSize: 16,
    lineHeight: 16,
    width: 384,
    height: 384,
    xOffset: 12,
    yOffset: 60,
    rotate: -15,
    zIndex: 9999
  };
});
</script>

<template>
  <NConfigProvider
    :theme-overrides="naiveThemeOverrides"
    :locale="naiveLocale"
    :date-locale="naiveDateLocale"
    class="h-full"
  >
    <AppProvider>
      <RouterView :key="routeLayoutKey" class="bg-layout" />
      <NWatermark v-if="themeStore.watermark.visible" v-bind="watermarkProps" />
    </AppProvider>
  </NConfigProvider>
</template>

<style scoped></style>
