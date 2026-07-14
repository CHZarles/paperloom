import { defineConfig } from 'vitepress';

const isGitHubPages = process.env.GITHUB_ACTIONS === 'true';

export default defineConfig({
  lang: 'zh-CN',
  title: 'PaperLoom',
  description: 'An evidence-bounded agentic RAG system and a growing engineering practice journal.',
  base: isGitHubPages ? '/paperloom/' : '/',
  appearance: false,
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: 'https://chzarles.github.io/paperloom/'
  },
  head: [
    ['link', { rel: 'icon', href: isGitHubPages ? '/paperloom/favicon.svg' : '/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#f7f8f5' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'PaperLoom' }],
    ['meta', {
      property: 'og:description',
      content: 'An evidence-bounded agentic RAG system and a growing engineering practice journal.'
    }],
    ['meta', {
      property: 'og:image',
      content: 'https://chzarles.github.io/paperloom/images/paperloom-evidence-flow.png'
    }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['link', {
      rel: 'alternate',
      type: 'application/rss+xml',
      title: 'PaperLoom Engineering Practice',
      href: isGitHubPages ? '/paperloom/feed.xml' : '/feed.xml'
    }]
  ],
  themeConfig: {
    logo: '/favicon.svg',
    siteTitle: 'PaperLoom',
    nav: [
      { text: '项目', link: '/project/' },
      { text: '架构', link: '/project/architecture' },
      { text: '演化', link: '/evolution/' },
      { text: '实践', link: '/practice/' },
      { text: '现在', link: '/now' },
      { text: '关于', link: '/about' }
    ],
    sidebar: {
      '/project/': [
        {
          text: 'PaperLoom',
          items: [
            { text: '项目介绍', link: '/project/' },
            { text: '系统架构', link: '/project/architecture' },
            { text: 'Reading Model 与 harness_py', link: '/project/reading-model' },
            { text: '评估方法', link: '/project/evaluation' },
            { text: '边界与路线', link: '/project/roadmap' }
          ]
        }
      ],
      '/evolution/': [
        {
          text: '工程演化',
          items: [
            { text: '演化索引', link: '/evolution/' },
            { text: '完整技术记录', link: 'https://github.com/CHZarles/paperloom/tree/main/docs/engineering-evolution' }
          ]
        }
      ],
      '/practice/': [
        {
          text: '实践记录',
          items: [
            { text: '文章索引', link: '/practice/' },
            { text: 'Golden Data 与 Harness', link: '/practice/evaluation/golden-data-harness-evolution' },
            { text: 'Best-of-2 编排实验', link: '/practice/evaluation/best-of-two-agent-orchestration' },
            { text: 'Provider 迁移实验', link: '/practice/evaluation/provider-migration-experiment' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/CHZarles/paperloom' }
    ],
    search: {
      provider: 'local'
    },
    outline: {
      level: [2, 3],
      label: '本页目录'
    },
    docFooter: {
      prev: '上一篇',
      next: '下一篇'
    },
    lastUpdated: {
      text: '最后更新'
    },
    footer: {
      message: 'Built in public as PaperLoom evolves.',
      copyright: 'Copyright © 2026 Charles'
    }
  }
});
