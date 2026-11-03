<script lang="ts" setup>
import { useRouterPush } from '@/hooks/common/router';
import { $t } from '@/locales';

defineOptions({ name: 'ExceptionBase' });

type ExceptionType = '403' | '404' | '500';

interface Props {
  /**
   * Exception type
   *
   * - 403: no permission
   * - 404: not found
   * - 500: service error
   */
  type: ExceptionType;
}

const props = defineProps<Props>();

const { routerPushByKey } = useRouterPush();
</script>

<template>
  <div class="exception-page">
    <div class="exception-panel">
      <span class="exception-code" aria-hidden="true">{{ props.type }}</span>
      <h1 class="exception-title">{{ $t(`route.${props.type}`) }}</h1>
      <p class="exception-desc">{{ $t(`page.exception.${props.type}`) }}</p>
      <NButton type="primary" @click="routerPushByKey('root')">{{ $t('common.backToHome') }}</NButton>
    </div>
  </div>
</template>

<style scoped>
.exception-page {
  display: grid;
  width: 100%;
  min-height: 520px;
  place-items: center;
  background: var(--color-bg);
  padding: var(--space-12) var(--space-5);
}

.exception-panel {
  display: flex;
  width: 100%;
  max-width: 420px;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  text-align: center;
}

.exception-code {
  color: #7f8a84;
  font-family: var(--font-utility);
  font-size: 56px;
  font-weight: 500;
  letter-spacing: -0.03em;
  line-height: 1;
}

.exception-title {
  margin: 0;
  color: var(--color-text);
  font-size: var(--text-xl);
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: var(--leading-tight);
}

.exception-desc {
  margin: 0 0 var(--space-2);
  color: var(--color-text-muted);
  font-size: var(--text-base);
  line-height: var(--leading-normal);
}

@media (max-width: 640px) {
  .exception-page {
    min-height: 100vh;
    padding: var(--space-8) var(--space-4);
  }

  .exception-code {
    font-size: 44px;
  }
}
</style>
