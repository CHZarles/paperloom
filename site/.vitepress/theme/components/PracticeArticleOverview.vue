<script setup lang="ts">
import { computed } from 'vue';
import { useData } from 'vitepress';

const { frontmatter } = useData();

const rows = computed(() => [
  { label: '背景', value: frontmatter.value.background },
  { label: '问题', value: frontmatter.value.problem },
  { label: '做法', value: frontmatter.value.approach },
  { label: '结果', value: frontmatter.value.outcome }
].filter(row => row.value));
</script>

<template>
  <dl v-if="rows.length" class="practice-article-overview" aria-label="文章概要">
    <div v-for="row in rows" :key="row.label" class="practice-article-overview__row">
      <dt>{{ row.label }}</dt>
      <dd>{{ row.value }}</dd>
    </div>
  </dl>
</template>
