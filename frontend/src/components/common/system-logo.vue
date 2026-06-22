<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <svg
    class="system-logo"
    aria-label="CiteWeave"
    role="img"
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <rect x="5" y="4" width="14" height="16" rx="3" />
    <path d="M8.2 8.4h7.6" />
    <path d="M8.2 11.8h5.4" />
    <path d="M8.2 15.2h3.2" />
    <rect x="13.6" y="13.5" width="3.5" height="3.5" rx="0.9" fill="currentColor" stroke="none" />
  </svg>
</template>

<style scoped>
.system-logo {
  width: 1em;
  height: 1em;
  color: var(--color-primary);
}
</style>
