import markdownItKatex from '@traptitech/markdown-it-katex';
import 'katex/dist/katex.min.css';
import type MarkdownIt from 'markdown-it';

export function configureResearchMarkdown(markdown: MarkdownIt) {
  markdown.use(markdownItKatex as any, {
    throwOnError: false,
    strict: false
  });
}

export const researchMarkdownOptions = {
  theme: 'dracula-soft',
  defaultHighlightLang: 'javascript',
  config: configureResearchMarkdown
};
