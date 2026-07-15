<script setup lang="ts">
import { computed, ref } from 'vue';
import { ArrowRight, Rss } from '@lucide/vue';
import { withBase } from 'vitepress';
import { data as practices } from '../../../practice/practices.data';

const props = withDefaults(defineProps<{
  category?: string;
  showFilters?: boolean;
}>(), {
  category: '',
  showFilters: true
});

const allCategory = '全部';
const selectedCategory = ref(props.category || allCategory);
const categories = computed(() => [...new Set(practices.map(entry => entry.category))]);
const visiblePractices = computed(() => {
  if (selectedCategory.value === allCategory) return practices;
  return practices.filter(entry => entry.category === selectedCategory.value);
});

function categoryCount(category: string): number {
  if (category === allCategory) return practices.length;
  return practices.filter(entry => entry.category === category).length;
}

function displayDate(date: string): string {
  return date.replaceAll('-', '.');
}
</script>

<template>
  <section class="practice-archive" aria-label="实践记录列表">
    <div class="practice-archive__toolbar">
      <div
        v-if="showFilters"
        class="practice-archive__filters"
        role="tablist"
        aria-label="实践分类"
      >
        <button
          v-for="categoryName in [allCategory, ...categories]"
          :key="categoryName"
          class="practice-archive__filter"
          :class="{ 'practice-archive__filter--active': selectedCategory === categoryName }"
          type="button"
          role="tab"
          :aria-selected="selectedCategory === categoryName"
          @click="selectedCategory = categoryName"
        >
          <span>{{ categoryName }}</span>
          <span>{{ categoryCount(categoryName) }}</span>
        </button>
      </div>
      <p v-else class="practice-archive__count">
        {{ visiblePractices.length }} 篇记录
      </p>
      <a
        class="practice-archive__rss"
        :href="withBase('/feed.xml')"
        aria-label="订阅实践记录 RSS"
        title="订阅 RSS"
      >
        <Rss :size="18" aria-hidden="true" />
      </a>
    </div>

    <div class="practice-archive__list">
      <article v-for="entry in visiblePractices" :key="entry.url" class="practice-entry">
        <div class="practice-entry__meta">
          <time :datetime="entry.date">{{ displayDate(entry.date) }}</time>
          <a :href="withBase(entry.categoryUrl)">{{ entry.category }}</a>
          <span v-if="entry.status">{{ entry.status }}</span>
        </div>
        <div class="practice-entry__body">
          <h2>
            <a :href="withBase(entry.url)">{{ entry.title }}</a>
          </h2>
          <p>{{ entry.description }}</p>
          <div v-if="entry.topics.length" class="practice-entry__topics" aria-label="文章主题">
            <span v-for="topic in entry.topics" :key="topic">{{ topic }}</span>
          </div>
        </div>
        <a
          class="practice-entry__open"
          :href="withBase(entry.url)"
          :aria-label="`阅读《${entry.title}》`"
          :title="`阅读《${entry.title}》`"
        >
          <ArrowRight :size="19" aria-hidden="true" />
        </a>
      </article>
    </div>
  </section>
</template>
