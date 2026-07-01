<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="global-logo w-full nowrap-hidden">
    <SystemLogo class="global-logo__mark" />
    <h2 v-show="showTitle" class="global-logo__title">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>

<style scoped>
.global-logo {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  padding: 0 18px;
  color: var(--color-text);
}

.global-logo__mark {
  flex: 0 0 auto;
  color: var(--color-primary);
  font-size: 22px;
}

.global-logo__title {
  overflow: hidden;
  margin: 0;
  color: var(--color-text);
  font-size: 18px;
  font-weight: 720;
  letter-spacing: 0;
  line-height: 1;
  text-overflow: ellipsis;
  transition: opacity 0.16s ease;
  white-space: nowrap;
}
</style>
