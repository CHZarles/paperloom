<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <svg
    class="system-logo"
    aria-label="Folio"
    role="img"
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 32 32"
    fill="none"
  >
    <path
      class="system-logo__folio"
      d="M7.5 7.8c0-1.5 1.18-2.66 2.68-2.55 2.54.19 4.45 1.04 5.82 2.4 1.37-1.36 3.28-2.21 5.82-2.4 1.5-.11 2.68 1.05 2.68 2.55v15.93c0 .77-.66 1.38-1.43 1.31-2.96-.27-5.06.32-6.3 1.47-.44.4-1.1.4-1.54 0-1.24-1.15-3.34-1.74-6.3-1.47-.77.07-1.43-.54-1.43-1.31V7.8Z"
    />
    <path class="system-logo__fold" d="M16 7.63v18.46" />
    <path class="system-logo__line" d="M10.8 10.9c1.82.19 3.18.7 4.1 1.48" />
    <path class="system-logo__line" d="M21.2 10.9c-1.82.19-3.18.7-4.1 1.48" />
    <path class="system-logo__line" d="M10.8 15.1c1.82.19 3.18.7 4.1 1.48" />
    <path class="system-logo__line" d="M21.2 15.1c-1.82.19-3.18.7-4.1 1.48" />
    <circle class="system-logo__marker" cx="21.35" cy="21.25" r="2.35" />
  </svg>
</template>

<style scoped>
.system-logo {
  width: 1em;
  height: 1em;
  color: var(--color-primary);
}

.system-logo__folio {
  fill: color-mix(in srgb, currentColor 12%, white);
  stroke: currentColor;
  stroke-linejoin: round;
  stroke-width: 1.85;
}

.system-logo__fold,
.system-logo__line {
  stroke: currentColor;
  stroke-linecap: round;
  stroke-width: 1.55;
}

.system-logo__fold {
  opacity: 0.78;
}

.system-logo__line {
  opacity: 0.5;
}

.system-logo__marker {
  fill: #c9a24a;
  stroke: color-mix(in srgb, currentColor 72%, #c9a24a);
  stroke-width: 1.15;
}
</style>
