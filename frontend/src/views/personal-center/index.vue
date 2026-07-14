<script setup lang="ts">
import { useRouter } from 'vue-router';
import SettingsPanel from './modules/settings-panel.vue';

const router = useRouter();

function closeSettings() {
  router.push({ name: 'chat' });
}
</script>

<template>
  <div class="settings-page">
    <SettingsPanel @close="closeSettings" />
  </div>
</template>

<style scoped lang="scss">
.settings-page {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--color-bg);
  color: var(--color-text);
  padding: 44px;
}

@media (max-width: 1120px) {
  .settings-page {
    padding: 24px;
  }
}

@media (max-width: 720px) {
  .settings-page {
    align-items: stretch;
    padding: 0;
  }
}
</style>
