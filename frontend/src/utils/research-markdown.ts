import markdownItKatex from '@traptitech/markdown-it-katex';
import type MarkdownIt from 'markdown-it';

const BOX_DRAWING_RE = /[┌┐└┘├┤┬┴┼│─═║╔╗╚╝╠╣╦╩╬]/;
const ASCII_BORDER_RE = /^[\s+\-|\\/=_—]{6,}$/;

export function looksLikeResearchPreformattedBlock(content: string) {
  const normalized = content.replace(/\r\n?/g, '\n').trimEnd();
  if (!normalized.includes('\n')) {
    return false;
  }

  const lines = normalized
    .split('\n')
    .map(line => line.trimEnd())
    .filter(line => line.trim().length > 0);

  if (lines.length < 2) {
    return false;
  }

  const boxDrawingLines = lines.filter(line => BOX_DRAWING_RE.test(line)).length;
  if (boxDrawingLines >= 2) {
    return true;
  }

  const indentedLines = lines.filter(line => /^\s{2,}\S/.test(line)).length;
  const alignedLines = lines.filter(line => /\S\s{2,}\S/.test(line)).length;
  const borderLines = lines.filter(line => ASCII_BORDER_RE.test(line)).length;

  return (indentedLines >= 2 && alignedLines >= 1) || (borderLines >= 1 && alignedLines >= 1);
}

export function configureResearchMarkdown(markdown: MarkdownIt) {
  markdown.use(markdownItKatex as any, {
    throwOnError: false,
    strict: false
  });

  const defaultFence = markdown.renderer.rules.fence;
  // markdown-it renderer rules use this five-argument signature.
  // eslint-disable-next-line max-params
  markdown.renderer.rules.fence = (tokens, index, options, env, renderer) => {
    const token = tokens[index];
    if (looksLikeResearchPreformattedBlock(token.content)) {
      const escaped = markdown.utils.escapeHtml(token.content);
      return `<pre class="research-preformatted-block research-preformatted-block--fence"><code>${escaped}</code></pre>`;
    }

    return defaultFence
      ? defaultFence(tokens, index, options, env, renderer)
      : renderer.renderToken(tokens, index, options);
  };

  markdown.core.ruler.after('inline', 'research_preformatted_blocks', state => {
    for (let index = 0; index < state.tokens.length - 2; index += 1) {
      const openToken = state.tokens[index];
      const inlineToken = state.tokens[index + 1];
      const closeToken = state.tokens[index + 2];

      const isParagraphBlock =
        openToken.type === 'paragraph_open' && inlineToken.type === 'inline' && closeToken.type === 'paragraph_close';

      if (isParagraphBlock && looksLikeResearchPreformattedBlock(inlineToken.content)) {
        openToken.attrJoin('class', 'research-preformatted-block');
      }
    }
  });
}

export const researchMarkdownOptions = {
  theme: 'dracula-soft',
  defaultHighlightLang: 'javascript',
  config: configureResearchMarkdown
};
