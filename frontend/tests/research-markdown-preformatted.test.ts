import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import MarkdownIt from 'markdown-it';
import {
  configureResearchMarkdown,
  looksLikeResearchPreformattedBlock,
  normalizeLegacyDisplayMathCitations
} from '../src/utils/research-markdown';

const diagram = `——————————————————————————--
      Encoder (N=6 层)              Decoder (N=6 层)
  ┌──────────────────────┐      ┌──────────────────────┐
  │ Multi-Head Attention │      │ Masked Self-Attention│
  │   + Add & Norm       │      │   + Add & Norm       │
  ├──────────────────────┤      ├──────────────────────┤
  │  Feed-Forward (FFN)  │      │ Encoder-Decoder Attn │
  │   + Add & Norm       │      │   + Add & Norm       │
  └──────────────────────┘      ├──────────────────────┤
                                │  Feed-Forward (FFN)  │
                                │   + Add & Norm       │
                                └──────────────────────┘`;

assert.equal(looksLikeResearchPreformattedBlock(diagram), true);
assert.equal(looksLikeResearchPreformattedBlock('This is a normal paragraph.\nIt should stay normal.'), false);

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
});

configureResearchMarkdown(markdown);

const rendered = markdown.render(`${diagram}\n\nThis is a normal paragraph.\nIt should stay normal.`);

assert.match(rendered, /class="research-preformatted-block"/);
assert.match(rendered, /This is a normal paragraph/);

const fencedRendered = markdown.render(`\`\`\`\n${diagram}\n\`\`\``);

assert.match(fencedRendered, /research-preformatted-block--fence/);
assert.doesNotMatch(fencedRendered, /language-javascript/);
assert.doesNotMatch(fencedRendered, /class="line"/);

const legacyFormulaMarkdown = `$$
x = 1
$$ [1]

## Following heading

Normal paragraph.

$$
y = 2
$$ [2] [3]

\`\`\`markdown
$$
z = 3
$$ [9]
\`\`\``;
const normalizedFormulaMarkdown = normalizeLegacyDisplayMathCitations(legacyFormulaMarkdown);
const renderedFormulaMarkdown = markdown.render(normalizedFormulaMarkdown);

assert.match(normalizedFormulaMarkdown, /\$\$\n\n\[1]/);
assert.match(normalizedFormulaMarkdown, /\$\$\n\n\[2] \[3]/);
assert.match(normalizedFormulaMarkdown, /```markdown\n\$\$\nz = 3\n\$\$ \[9]\n```/);
assert.equal((renderedFormulaMarkdown.match(/katex-display/g) || []).length, 2);
assert.doesNotMatch(renderedFormulaMarkdown, /katex-error/);
assert.match(renderedFormulaMarkdown, /<h2>Following heading<\/h2>/);

const subscriptFormula = markdown.render('$X_{f16}$');
const baseClass = subscriptFormula.match(/<span class="((?:katex-)?base)">/)?.[1];
const katexCss = readFileSync(createRequire(import.meta.url).resolve('katex/dist/katex.css'), 'utf8');

assert.ok(baseClass, 'KaTeX should render a formula base');
assert.match(katexCss, new RegExp(`\\.katex \\.${baseClass}\\s*\\{`));
