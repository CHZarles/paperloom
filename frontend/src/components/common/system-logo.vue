<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <span class="system-logo" aria-label="PaperLoom" role="img">
    <span class="system-logo__mark">
      <span class="system-logo__strip system-logo__strip--vertical system-logo__strip--v1"></span>
      <span class="system-logo__strip system-logo__strip--vertical system-logo__strip--v2"></span>
      <span class="system-logo__strip system-logo__strip--vertical system-logo__strip--v3"></span>
      <span class="system-logo__strip system-logo__strip--horizontal system-logo__strip--h1"></span>
      <span class="system-logo__strip system-logo__strip--horizontal system-logo__strip--h2"></span>
      <span class="system-logo__strip system-logo__strip--horizontal system-logo__strip--h3"></span>
      <span class="system-logo__over system-logo__over--v1h2"></span>
      <span class="system-logo__over system-logo__over--v2h1"></span>
      <span class="system-logo__over system-logo__over--v2h3"></span>
      <span class="system-logo__over system-logo__over--v3h2"></span>
    </span>
  </span>
</template>

<style scoped>
.system-logo {
  position: relative;
  display: inline-flex;
  width: 1em;
  height: 1em;
  align-items: center;
  justify-content: center;
  color: var(--color-primary);
}

.system-logo__mark {
  position: absolute;
  inset: 0.07em;
  border-radius: 0.16em;
  transform: rotate(-4deg);
}

.system-logo__mark::after {
  position: absolute;
  inset: 0.16em 0.11em 0.11em 0.16em;
  z-index: 0;
  border-radius: 0.14em;
  background: rgba(201, 193, 178, 0.82);
  content: '';
  transform: translate(0.08em, 0.08em);
}

.system-logo__strip,
.system-logo__over {
  position: absolute;
  z-index: 1;
  border: 0.045em solid currentcolor;
  border-radius: 0.09em;
  box-sizing: border-box;
}

.system-logo__strip--vertical {
  top: 0.06em;
  bottom: 0.06em;
  width: 0.2em;
  background: var(--color-bg);
}

.system-logo__strip--horizontal {
  left: 0.06em;
  right: 0.06em;
  z-index: 2;
  height: 0.2em;
  background: var(--color-card-band);
}

.system-logo__strip--v1 {
  left: 0.12em;
}

.system-logo__strip--v2 {
  left: 0.39em;
  background: var(--color-card-band);
}

.system-logo__strip--v3 {
  left: 0.66em;
}

.system-logo__strip--h1 {
  top: 0.16em;
}

.system-logo__strip--h2 {
  top: 0.39em;
  background: #e7dde0;
}

.system-logo__strip--h3 {
  top: 0.62em;
}

.system-logo__over {
  z-index: 3;
  width: 0.2em;
  height: 0.2em;
  background: var(--color-bg);
}

.system-logo__over--v1h2 {
  top: 0.39em;
  left: 0.12em;
}

.system-logo__over--v2h1 {
  top: 0.16em;
  left: 0.39em;
  background: var(--color-card-band);
}

.system-logo__over--v2h3 {
  top: 0.62em;
  left: 0.39em;
  background: var(--color-card-band);
}

.system-logo__over--v3h2 {
  top: 0.39em;
  left: 0.66em;
}
</style>
