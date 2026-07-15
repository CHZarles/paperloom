import { createContentLoader } from 'vitepress';

export interface PracticeEntry {
  url: string;
  title: string;
  description: string;
  date: string;
  category: string;
  categoryUrl: string;
  stage: string;
  status: string;
  result?: string;
  topics: string[];
}

const articleOverviewFields = ['background', 'problem', 'approach', 'outcome'] as const;

function normalizeDate(value: unknown): string {
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return String(value ?? '').slice(0, 10);
}

function validateArticleOverview(url: string, frontmatter: Record<string, unknown>): void {
  const missingFields = articleOverviewFields.filter(field => !String(frontmatter[field] ?? '').trim());

  if (missingFields.length > 0) {
    throw new Error(`${url} is missing practice article frontmatter: ${missingFields.join(', ')}`);
  }
}

export default createContentLoader<PracticeEntry[]>('practice/**/*.md', {
  transform(pages) {
    return pages
      .filter(page => page.frontmatter.title && page.frontmatter.date && page.frontmatter.category)
      .map(page => {
        validateArticleOverview(page.url, page.frontmatter);
        const categoryUrl = `${page.url.slice(0, page.url.lastIndexOf('/'))}/`;

        return {
          url: page.url,
          title: String(page.frontmatter.title),
          description: String(page.frontmatter.description ?? ''),
          date: normalizeDate(page.frontmatter.date),
          category: String(page.frontmatter.category),
          categoryUrl,
          stage: String(page.frontmatter.stage ?? ''),
          status: String(page.frontmatter.status ?? ''),
          result: page.frontmatter.result ? String(page.frontmatter.result) : undefined,
          topics: Array.isArray(page.frontmatter.topics)
            ? page.frontmatter.topics.map(String)
            : []
        };
      })
      .sort((left, right) => {
        const byDate = right.date.localeCompare(left.date);
        return byDate || left.title.localeCompare(right.title, 'zh-CN');
      });
  }
});
