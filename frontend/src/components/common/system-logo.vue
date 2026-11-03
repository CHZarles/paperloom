<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <svg
    class="system-logo"
    aria-label="Citewise"
    role="img"
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 32 32"
    fill="none"
  >
    <!-- Citation brackets around a single piece of evidence. -->
    <path class="system-logo__bracket" d="M12.4 7.6H8.9a2.1 2.1 0 0 0-2.1 2.1v12.6a2.1 2.1 0 0 0 2.1 2.1h3.5" />
    <path class="system-logo__bracket" d="M19.6 7.6h3.5a2.1 2.1 0 0 1 2.1 2.1v12.6a2.1 2.1 0 0 1-2.1 2.1h-3.5" />
    <circle class="system-logo__evidence" cx="16" cy="16" r="3" />
  </svg>
</template>

<style scoped>
.system-logo {
  width: 1em;
  height: 1em;
  color: var(--color-primary);
}

.system-logo__bracket {
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2.6;
}

.system-logo__evidence {
  fill: var(--brand-teal);
}
</style>
