/**
 * S0 spike: pi 宿主形态验证。
 * 验收点：工具面锁定（只有两个 customTools 激活）、MiniMax 供应商注入、
 * 「计划→检索→提交」闭环、提交门防编造 id。
 * 用法: node spike.mjs <topicId> "<研究目标>" <目标篇数>
 */
import { createAgentSession, ModelRuntime, SessionManager, SettingsManager, DefaultResourceLoader, defineTool } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { performance } from "node:perf_hooks";
import { writeFileSync } from "node:fs";
import path from "node:path";

const spikeDir = path.resolve(".");
const workDir = path.join(spikeDir, "work");
const topicId = process.argv[2] ?? "t1";
const goal = process.argv[3] ?? "speculative decoding 与 KV cache 量化方向的近两年代表工作";
const maxPapers = Number(process.argv[4] ?? "5");

// ---- run 级状态（未来 Runner 的全部持久化都在这里）----
const observed = new Map(); // arxiv_id -> {title, year}：提交门的事实源
const stepLog = [];
let shortlist = null;
let submitAttempts = 0;
let usageTotal = { input: 0, output: 0 };
const eventTypes = new Map(); // 见过的 pi 事件类型计数（S0 记录用）

// ---- arXiv 直查（S0 临时实现；S1 起换成 Java internal 端点，自带库内状态）----
let lastArxivCall = 0;
async function arxivSearch(query, opts = {}) {
  const start = opts.start ?? 0;
  const max = Math.min(opts.max ?? 10, 20);
  const sortBy = opts.sort === "submitted" ? "submittedDate" : "relevance";
  const wait = 1500 - (performance.now() - lastArxivCall);
  if (wait > 0) await new Promise(r => setTimeout(r, wait));
  lastArxivCall = performance.now();
  const url =
    `https://export.arxiv.org/api/query?search_query=${encodeURIComponent(`all:${query}`)}` +
    `&start=${start}&max_results=${max}&sortBy=${sortBy}&sortOrder=descending`;
  const res = await fetch(url, { headers: { "User-Agent": "paperloom-discovery-spike/0.1 (local dev)" } });
  const xml = await res.text();
  const entries = xml
    .split("<entry>")
    .slice(1)
    .map(e => {
      const pick = tag => {
        const m = e.match(new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`));
        return m ? m[1].trim().replace(/\s+/g, " ") : "";
      };
      const idm = e.match(/<id>https?:\/\/arxiv.org\/abs\/([^<]+)<\/id>/);
      const ym = e.match(/<published>(\d{4})-/);
      const cats = [...e.matchAll(/category term="([^"]+)"/g)].slice(0, 6).map(m => m[1]);
      let year = ym ? ym[1] : null;
      // 年份过滤在工具层做（S0 简化；正式版由 Runner 过滤后转发）
      if (opts.year_from && year && Number(year) < opts.year_from) year = null;
      if (opts.year_to && year && Number(year) > opts.year_to) year = null;
      return idm && year
        ? {
            arxiv_id: idm[1],
            title: pick("title"),
            summary: pick("summary").slice(0, 400),
            authors: [...e.matchAll(/<name>([^<]+)<\/name>/g)].slice(0, 6).map(m => m[1]),
            categories: cats,
            published_year: ym ? ym[1] : null,
            library_status: "NONE" // S0 桩；S1 起来自 Java ownership 查询
          }
        : null;
    })
    .filter(Boolean);
  return entries;
}

// ---- 两个 customTools：这个 run 的全部工具面 ----
const searchTool = defineTool({
  name: "search_arxiv_papers",
  label: "arXiv 检索",
  description:
    "在 arXiv 检索论文元数据。返回 arxiv_id、标题、摘要首段、作者、类目、发表年份与库内状态。" +
    "可以用不同检索词多次调用；不要重复完全相同的 query。",
  parameters: Type.Object({
    query: Type.String({ description: "arXiv 检索词（英文术语效果最好）" }),
    max: Type.Optional(Type.Integer({ minimum: 1, maximum: 20, description: "返回条数上限，默认 10" })),
    start: Type.Optional(Type.Integer({ minimum: 0, description: "分页偏移，默认 0" })),
    sort: Type.Optional(Type.Union([Type.Literal("relevance"), Type.Literal("submitted")]))
  }),
  async execute(_id, params) {
    const entries = await arxivSearch(params.query, params);
    for (const e of entries) observed.set(e.arxiv_id, { title: e.title, year: e.published_year });
    stepLog.push(`检索「${params.query}」→ ${entries.length} 条`);
    const text = JSON.stringify({ items: entries, observed_so_far: observed.size });
    return { content: [{ type: "text", text }], details: { query: params.query } };
  }
});

const submitTool = defineTool({
  name: "submit_discovery_shortlist",
  label: "提交短名单",
  description:
    "最终提交：把筛选好的论文短名单一次性提交并结束任务。" +
    "arxiv_id 必须来自此前检索结果中真实出现的 id；每篇给中文入选理由（≤300 字，只依据元数据）。",
  parameters: Type.Object({
    items: Type.Array(
      Type.Object({
        arxiv_id: Type.String(),
        reason: Type.String(),
        score: Type.Optional(Type.Number())
      }),
      { maxItems: 20 }
    )
  }),
  async execute(_id, params) {
    submitAttempts++;
    const errors = [];
    const seen = new Set();
    const okItems = [];
    for (const it of params.items) {
      if (!observed.has(it.arxiv_id)) {
        errors.push(`"${it.arxiv_id}" 不在本次检索结果中`);
        continue;
      }
      if (seen.has(it.arxiv_id)) continue;
      seen.add(it.arxiv_id);
      okItems.push(it);
      if (it.reason && it.reason.length > 300) errors.push(`"${it.arxiv_id}" 理由超长(${it.reason.length} 字)`);
    }
    if (okItems.length > maxPapers) errors.push(`条数 ${okItems.length} 超过目标 ${maxPapers}`);
    if (errors.length) {
      // 提交门错误自带观察集 digest：修复过程即记忆补给
      const digest = [...observed.entries()].slice(0, 60).map(([id, v]) => `${id} | ${v.title.slice(0, 80)}`);
      return {
        content: [{ type: "text", text: JSON.stringify({ ok: false, errors, observed_count: observed.size, observed_digest: digest }) }],
        details: {}
      };
    }
    shortlist = okItems;
    return { content: [{ type: "text", text: JSON.stringify({ ok: true, accepted: okItems.length }) }], details: {} };
  }
});

const SYSTEM_PROMPT = `你是 PaperLoom 的论文发现助手。你的唯一任务：围绕用户给的研究目标在 arXiv 上检索并筛选论文，最终提交一份短名单。

规则：
1. 只能用两个工具：search_arxiv_papers 检索，submit_discovery_shortlist 提交。
2. 多角度检索：同义词、缩写、算法名、变体写法都值得一试；不要重复完全相同的 query。
3. 判断与理由只依据检索返回的元数据（标题/摘要/作者/年份/类目）；不得给出论文内容性结论（例如"实验表明…"）。
4. library_status 非 NONE 的论文不要选。
5. 短名单条数不超过目标篇数；每条 reason 用中文、不超过 300 字、只引用元数据中可见的事实。
6. 筛选完成后调用 submit_discovery_shortlist 一次性提交，然后结束；不要输出多余的对话或解释。
7. 你不回答学术问题；用户问学术问题时说明你只做论文发现与筛选。`;

// ---- 组装会话：一切最小化 + 工具面 allowlist ----
const modelRuntime = await ModelRuntime.create({
  modelsPath: path.join(spikeDir, "models.json"),
  authPath: path.join(spikeDir, "agent-dir", "auth.json")
});
const model = modelRuntime.getModel("minimax", "MiniMax-M3.1-Flash");
if (!model) throw new Error("models.json 未注册出 MiniMax-M3.1-Flash");

const loader = new DefaultResourceLoader({
  cwd: workDir,
  agentDir: path.join(spikeDir, "agent-dir"),
  systemPromptOverride: () => SYSTEM_PROMPT,
  appendSystemPromptOverride: () => []
});
await loader.reload();

const { session } = await createAgentSession({
  cwd: workDir,
  model,
  modelRuntime,
  tools: ["search_arxiv_papers", "submit_discovery_shortlist"], // allowlist：内置工具全部不匹配
  customTools: [searchTool, submitTool],
  sessionManager: SessionManager.inMemory(),
  settingsManager: SettingsManager.inMemory(),
  resourceLoader: loader
});

session.subscribe(event => {
  const t = event.type;
  eventTypes.set(t, (eventTypes.get(t) ?? 0) + 1);
  const usage = event?.message?.usage ?? event?.assistantMessage?.usage;
  if (usage) {
    usageTotal.input += usage.input ?? 0;
    usageTotal.output += usage.output ?? 0;
  }
  if (event.type === "message_update" && event.assistantMessageEvent?.type === "text_delta") {
    process.stdout.write(event.assistantMessageEvent.delta);
  }
});

const activeTools = session.getActiveToolNames(); // 工具面锁定的直接证据
console.log("[spike] active tools:", JSON.stringify(activeTools));

const watchdog = setTimeout(() => {
  console.error("\n[spike] 10 分钟 watchdog，abort（spike 花费护栏，不是产品行为）");
  session.abort();
}, 10 * 60 * 1000);

const t0 = performance.now();
try {
  await session.prompt(
    `研究目标：${goal}\n目标篇数：${maxPapers} 篇。\n请多角度检索后提交短名单。`
  );
} catch (e) {
  console.error("\n[spike] prompt 抛错:", String(e).slice(0, 300));
} finally {
  clearTimeout(watchdog);
  session.dispose();
}

const elapsed = Math.round((performance.now() - t0) / 1000);
const result = {
  topic: topicId,
  goal,
  max_papers: maxPapers,
  active_tools: activeTools,
  shortlist,
  shortlist_enriched: shortlist
    ? shortlist.map(s => ({ ...s, title: observed.get(s.arxiv_id)?.title, year: observed.get(s.arxiv_id)?.year }))
    : null,
  submit_attempts: submitAttempts,
  observed_count: observed.size,
  searches: stepLog,
  usage_total: usageTotal,
  event_types: Object.fromEntries(eventTypes),
  model: "MiniMax-M3.1-Flash@api.minimaxi.com/v1",
  elapsed_s: elapsed
};
writeFileSync(path.join(spikeDir, "spike-out", `${topicId}.json`), JSON.stringify(result, null, 2));

console.log("\n===== S0 结果 =====");
console.log(`短名单: ${shortlist ? shortlist.length : 0} 篇 (提交尝试 ${submitAttempts} 次)`);
console.log(`观察集: ${observed.size} 条 | 检索 ${stepLog.length} 次 | 耗时 ${elapsed}s`);
console.log(`用量: input ${usageTotal.input} / output ${usageTotal.output} tokens`);
if (shortlist) {
  for (const s of result.shortlist_enriched) console.log(`  - [${s.arxiv_id}] ${s.title} (${s.year})\n    ${s.reason.slice(0, 120)}...`);
}
