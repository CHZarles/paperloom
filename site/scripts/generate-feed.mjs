import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const siteRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
const practiceRoot = join(siteRoot, 'practice');
const outputPath = join(siteRoot, '.vitepress', 'dist', 'feed.xml');
const siteUrl = 'https://chzarles.github.io/paperloom';

function markdownFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return markdownFiles(path);
    if (entry.name === 'index.md' || !entry.name.endsWith('.md')) return [];
    return [path];
  });
}

function frontmatter(markdown) {
  const match = markdown.match(/^---\n([\s\S]*?)\n---/);
  if (!match) return {};
  return Object.fromEntries(
    match[1]
      .split('\n')
      .map(line => {
        const separator = line.indexOf(':');
        if (separator < 0) return null;
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      })
      .filter(Boolean)
  );
}

function xml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

const items = markdownFiles(practiceRoot)
  .map(path => {
    const metadata = frontmatter(readFileSync(path, 'utf8'));
    const route = relative(siteRoot, path).replace(/\\/g, '/').replace(/\.md$/, '');
    return {
      title: metadata.title,
      description: metadata.description,
      date: metadata.date,
      url: `${siteUrl}/${route}`
    };
  })
  .filter(item => item.title && item.date)
  .sort((left, right) => String(right.date).localeCompare(String(left.date)));

const feed = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>PaperLoom Engineering Practice</title>
    <link>${siteUrl}/</link>
    <description>Evidence-backed notes from building PaperLoom.</description>
    <language>zh-CN</language>
${items.map(item => `    <item>
      <title>${xml(item.title)}</title>
      <link>${item.url}</link>
      <guid>${item.url}</guid>
      <pubDate>${new Date(`${item.date}T00:00:00+08:00`).toUTCString()}</pubDate>
      <description>${xml(item.description || '')}</description>
    </item>`).join('\n')}
  </channel>
</rss>
`;

writeFileSync(outputPath, feed, 'utf8');
console.log(`generated ${relative(siteRoot, outputPath)} with ${items.length} items`);
