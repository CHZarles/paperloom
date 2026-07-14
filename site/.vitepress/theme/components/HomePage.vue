<script setup lang="ts">
import {
  ArrowRight,
  BookOpen,
  ExternalLink,
  FileText,
  FileSearch,
  GitFork,
  GitPullRequestArrow,
  Quote,
  Search,
  ScrollText
} from '@lucide/vue';
import { withBase } from 'vitepress';

const pipeline = [
  ['01', '上传', '研究论文 PDF'],
  ['02', '解析', 'MinerU 与原始产物'],
  ['03', '建模', '页面、章节与阅读元素'],
  ['04', '检索', '授权范围内的内存 BM25'],
  ['05', '阅读', 'Agent 读取可核验证据'],
  ['06', '回溯', '重新打开持久化引用']
];

const evidenceRows = [
  {
    label: 'READING MODEL',
    title: '搜索结果需要保留论文结构，不能只剩孤立文本块。',
    body: '物理页、阅读顺序、表格、图片、图表与公式都进入同一个产品阅读模型。',
    links: [
      ['ADR-0003', 'https://github.com/CHZarles/paperloom/blob/main/docs/adr/0003-use-paper-locations-for-reading-structure.md'],
      ['Reading Model', withBase('/project/reading-model')]
    ]
  },
  {
    label: 'HARNESS_PY TOOLS',
    title: '模型选择研究动作，协议判断哪些内容可以成为 Evidence。',
    body: 'Paper、Location 和 Evidence 逐层公开，最终提交只能引用已经准确读取的内容。',
    links: [
      ['harness_py tools', withBase('/project/reading-model')],
      ['Architecture', withBase('/project/architecture')]
    ]
  },
  {
    label: 'PERSISTENT EVIDENCE',
    title: '每条引用都保存成可以再次定位的论文位置。',
    body: '回答里的 Evidence ID 会映射到持久化 Reference，历史会话也能重新打开原文。',
    links: [
      ['ADR-0008', 'https://github.com/CHZarles/paperloom/blob/main/docs/adr/0008-use-explicit-chat-turn-targets.md'],
      ['Evidence model', 'https://github.com/CHZarles/paperloom/blob/main/docs/architecture/evidence-and-citations.md']
    ]
  },
  {
    label: 'BEHAVIOR EVALUATION',
    title: 'Golden Case 分开检查 Candidate、Read、Cited 与最终结果。',
    body: '评估约束证据、声明、结果形态和可见轨迹，同时允许模型用不同措辞回答。',
    links: [
      ['ADR-0011', 'https://github.com/CHZarles/paperloom/blob/main/docs/adr/0011-use-evidence-first-golden-cases-for-harness-eval.md'],
      ['Evaluation', withBase('/project/evaluation')]
    ]
  }
];

const articles = [
  {
    date: '2026.07.14',
    result: '36.7%',
    title: '换成更强的模型之后，为什么 Harness 反而下降',
    summary: '接口迁移跑通后，同一套工具预算和停止条件在新模型上造成了明显回归。',
    href: withBase('/practice/evaluation/provider-migration-experiment')
  },
  {
    date: '2026.07.14',
    result: '53.3%',
    title: '同时跑两遍 Agent，为什么没有提高通过率',
    summary: '两条并行路径的候选池只到 17/30，Selector 还额外选错了 1 个 Case。',
    href: withBase('/practice/evaluation/best-of-two-agent-orchestration')
  },
  {
    date: '2026.07.13',
    result: '29/32',
    title: '召回恢复之后，为什么最终只多通过一个 Case',
    summary: '输入契约修复了召回，剩余失败集中到了 Evidence Selection。',
    href: withBase('/practice/evaluation/golden-data-harness-evolution')
  }
];
</script>

<template>
  <main class="home-page">
    <section class="project-hero" aria-labelledby="project-title">
      <div class="project-hero__visual" aria-hidden="true">
        <div class="trace-board">
          <div class="trace-board__meta">
            <span>EVIDENCE TRACE / TURN 024</span>
            <span>SOURCE SET LOCKED</span>
          </div>
          <div class="trace-stages">
            <div class="trace-stage trace-stage--source">
              <span class="trace-stage__label">01 / SOURCE</span>
              <span class="trace-stage__node"><FileText :size="20" /></span>
              <strong>PAGE 08</strong>
              <span>Figure 4 · §3.2</span>
            </div>
            <div class="trace-stage trace-stage--read">
              <span class="trace-stage__label">02 / READ</span>
              <span class="trace-stage__node"><Search :size="20" /></span>
              <strong>EVIDENCE 042</strong>
              <span>caption + paragraph</span>
            </div>
            <div class="trace-stage trace-stage--cite">
              <span class="trace-stage__label">03 / CITE</span>
              <span class="trace-stage__node"><Quote :size="20" /></span>
              <strong>REFERENCE [4]</strong>
              <span>claim bound to source</span>
            </div>
            <div class="trace-stage trace-stage--reopen">
              <span class="trace-stage__label">04 / REOPEN</span>
              <span class="trace-stage__node"><ExternalLink :size="20" /></span>
              <strong>PAGE 08</strong>
              <span>historical citation</span>
            </div>
            <span class="trace-board__packet" />
          </div>
          <div class="trace-board__ledger">
            <span>candidate <strong>12</strong></span>
            <span>opened <strong>4</strong></span>
            <span>cited <strong>3</strong></span>
            <span>reopenable <strong>3</strong></span>
          </div>
        </div>
      </div>
      <div class="project-hero__copy">
        <p class="project-hero__eyebrow">PROJECT + ENGINEERING PRACTICE</p>
        <h1 id="project-title">PaperLoom</h1>
        <p class="project-hero__summary">
          我在 PaperLoom 里搭了一条研究论文阅读链路。Agent 可以自己检索和阅读，每个内容声明都必须引用当前会话授权过的原文。
        </p>
        <div class="project-hero__actions">
          <a class="action-link action-link--primary" :href="withBase('/project/')">
            <BookOpen :size="18" aria-hidden="true" />
            了解项目
          </a>
          <a class="action-link action-link--quiet" :href="withBase('/practice/')">
            <ScrollText :size="18" aria-hidden="true" />
            阅读实践
          </a>
          <a class="action-link action-link--icon" href="https://github.com/CHZarles/paperloom" aria-label="Open PaperLoom on GitHub" title="GitHub">
            <GitFork :size="20" aria-hidden="true" />
          </a>
        </div>
        <div class="project-hero__sources" aria-label="Project evidence links">
          <span>项目证据</span>
          <a :href="withBase('/project/architecture')">Architecture</a>
          <a :href="withBase('/project/reading-model')">Reading Model</a>
          <a href="https://github.com/CHZarles/paperloom/tree/main/docs/adr">ADRs</a>
          <a :href="withBase('/project/evaluation')">Evaluation</a>
        </div>
      </div>
    </section>

    <section class="current-focus" aria-label="Current focus">
      <span class="current-focus__label">NOW</span>
      <p>我正在研究 Reading Model 投影、Evidence Selection，以及如何用保存下来的 Trace 改进 Agent。</p>
      <a :href="withBase('/now')">查看当前问题 <ArrowRight :size="16" aria-hidden="true" /></a>
    </section>

    <section class="home-section pipeline-section" aria-labelledby="pipeline-title">
      <div class="section-intro">
        <p class="section-kicker">HOW IT READS</p>
        <h2 id="pipeline-title">从 PDF 到可重新打开的证据</h2>
        <p>解析、授权范围、内存检索、Agent 阅读和历史引用都能在同一条链路里核对。</p>
      </div>
      <ol class="pipeline" aria-label="Paper reading pipeline">
        <li v-for="step in pipeline" :key="step[0]">
          <span class="pipeline__number">{{ step[0] }}</span>
          <strong>{{ step[1] }}</strong>
          <span>{{ step[2] }}</span>
        </li>
      </ol>
    </section>

    <section class="home-section evidence-section" aria-labelledby="evidence-title">
      <div class="section-intro section-intro--compact">
        <p class="section-kicker">DESIGN QUESTIONS</p>
        <h2 id="evidence-title">四个我反复校验的设计问题</h2>
      </div>
      <div class="evidence-list">
        <article v-for="row in evidenceRows" :key="row.label" class="evidence-row">
          <div class="evidence-row__marker">
            <Quote :size="20" aria-hidden="true" />
            <span>{{ row.label }}</span>
          </div>
          <div class="evidence-row__content">
            <h3>{{ row.title }}</h3>
            <p>{{ row.body }}</p>
          </div>
          <div class="evidence-row__sources">
            <a v-for="link in row.links" :key="link[0]" :href="link[1]">[{{ link[0] }}]</a>
          </div>
        </article>
      </div>
    </section>

    <section class="home-section evolution-section" aria-labelledby="evolution-title">
      <div class="section-intro">
        <p class="section-kicker">ENGINEERING EVOLUTION</p>
        <h2 id="evolution-title">理解被事实改写的过程</h2>
        <p>这里保留改变过系统边界的故障、实验、决策和验证。没有结论的过程稿不会进入公开站点。</p>
        <a class="inline-command" :href="withBase('/evolution/')">
          <GitPullRequestArrow :size="18" aria-hidden="true" />
          查看演化时间线
        </a>
      </div>
      <div class="evolution-rail">
        <div class="evolution-event">
          <time>2026.06</time>
          <strong>把产品边界收敛到研究论文</strong>
          <p>分离产品论文与评估语料，建立明确的会话来源范围。</p>
        </div>
        <div class="evolution-event">
          <time>2026.06</time>
          <strong>让错误路由失败得足够明确</strong>
          <p>把语义路由与能力执行拆开，不再用检索掩盖无法识别的任务。</p>
        </div>
        <div class="evolution-event">
          <time>2026.07</time>
          <strong>建立产品自己的 Reading Model</strong>
          <p>让页面、结构化元素、Location 与视觉证据共享同一份来源语义。</p>
        </div>
        <div class="evolution-event evolution-event--active">
          <time>NOW</time>
          <strong>从答案评分走向行为与证据评分</strong>
          <p>分开观察召回、阅读、引用、结果选择与成本。</p>
        </div>
      </div>
    </section>

    <section class="home-section practice-section" aria-labelledby="practice-title">
      <div class="section-intro section-intro--compact">
        <p class="section-kicker">LATEST PRACTICE</p>
        <h2 id="practice-title">最近的工程实践</h2>
      </div>
      <div class="article-list">
        <a v-for="article in articles" :key="article.href" class="article-row" :href="article.href">
          <time>{{ article.date }}</time>
          <span class="article-row__result">{{ article.result }}</span>
          <span class="article-row__body">
            <strong>{{ article.title }}</strong>
            <span>{{ article.summary }}</span>
          </span>
          <ArrowRight :size="20" aria-hidden="true" />
        </a>
      </div>
    </section>

    <section class="closing-section" aria-labelledby="closing-title">
      <FileSearch :size="34" aria-hidden="true" />
      <div>
        <p class="section-kicker">A GROWING BODY OF WORK</p>
        <h2 id="closing-title">系统在变，我对 Agentic AI 的理解也在变。</h2>
        <p>我把实现、实验和判断一起记下来，方便以后回看自己为何改了架构，又删掉了哪些方案。</p>
      </div>
      <a class="action-link action-link--primary" :href="withBase('/about')">
        关于这段实践
        <ArrowRight :size="18" aria-hidden="true" />
      </a>
    </section>
  </main>
</template>
