import assert from 'node:assert/strict';
import MarkdownIt from 'markdown-it';
import { configureResearchMarkdown, looksLikeResearchPreformattedBlock } from '../src/utils/research-markdown';

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
