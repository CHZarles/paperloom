<script setup lang="ts">
import { useAppStore } from '@/store/modules/app';
import { $t } from '@/locales';

defineOptions({
  name: 'ThemeButton'
});

const appStore = useAppStore();
</script>

<template>
  <ButtonIcon icon="lucide:palette" :tooltip-content="$t('icon.themeConfig')" @click="appStore.openThemeDrawer" />
</template>

<style scoped></style>
