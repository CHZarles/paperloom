<script lang="ts" setup>
import { computed } from 'vue';

defineOptions({ name: 'MenuToggler' });

interface Props {
  /** Show collapsed icon */
  collapsed?: boolean;
  /** Arrow style icon */
  arrowIcon?: boolean;
  zIndex?: number;
}

const props = withDefaults(defineProps<Props>(), {
  arrowIcon: false,
  zIndex: 98
});

type NumberBool = 0 | 1;

const icon = computed(() => {
  const icons: Record<NumberBool, Record<NumberBool, string>> = {
    0: {
      0: 'lucide:panel-left-close',
      1: 'lucide:panel-left-open'
    },
    1: {
      0: 'lucide:chevron-left',
      1: 'lucide:chevron-right'
    }
  };

  const arrowIcon = Number(props.arrowIcon || false) as NumberBool;

  const collapsed = Number(props.collapsed || false) as NumberBool;

  return icons[arrowIcon][collapsed];
});
</script>

<template>
  <ButtonIcon :key="String(collapsed)" :z-index="zIndex">
    <SvgIcon :icon="icon" />
  </ButtonIcon>
</template>

<style scoped></style>
