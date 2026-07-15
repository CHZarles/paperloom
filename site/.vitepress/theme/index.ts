import DefaultTheme from 'vitepress/theme';
import type { Theme } from 'vitepress';
import HomePage from './components/HomePage.vue';
import PracticeArchive from './components/PracticeArchive.vue';
import PracticeArticleOverview from './components/PracticeArticleOverview.vue';
import './style.css';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('HomePage', HomePage);
    app.component('PracticeArchive', PracticeArchive);
    app.component('PracticeArticleOverview', PracticeArticleOverview);
  }
} satisfies Theme;
