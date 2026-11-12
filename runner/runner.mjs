/**
 * PaperLoom Discovery Runner：pi 宿主进程（v3 聊天单例形态）。
 *
 * 每条用户消息 = 一个 turn（CHAT 或 DISTILL）。Java 是唯一权威：窗口、长期记忆、候选池、
 * 入库预算全部在 Java；runner 无状态地为每个 turn 重建一个 in-memory pi 会话，注入：
 *   - 契约 system prompt + 长期记忆块（Java 已渲染顺序）；
 *   - 短期窗口历史（Java 已做硬截断与候选 enrich，SDK 原生消息条目）；
 *   - lastMessage 作为本轮用户输入。
 *
 * 工具面按 kind 锁定（安全事实见下方 allowlist 注释）：
 *   CHAT: 外部元数据检索、独立公开原文获取、候选操作（入库门：ids ∈ 本窗口候选池）
 *   DISTILL: save_memory（唯一允许写长期记忆的通道，且只在显式 /沉淀 轮存在）
 *
 * 鉴权：Bearer $DISCOVERY_RUNNER_INTERNAL_TOKEN；环境变量同 v2（PORT/JAVA_INTERNAL_BASE_URL/
 * OPENGO_API_KEY/PROVIDER_ID/MODEL_ID，MiniMax 套餐可用 PROVIDER_ID=minimax 切回）。护栏：无行为预算（D3）、
 * 30 分钟进程卫生 deadline、终态 turn 2 小时无人回收自动清理。
 */

import {
  createAgentSession,
  ModelRuntime,
  SessionManager,
  SettingsManager,
  DefaultResourceLoader,
  defineTool
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { CHAT_CONTRACT, buildReadingTools } from "./discovery-reading.mjs";
import http from "node:http";
import { mkdirSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { performance } from "node:perf_hooks";
import { timingSafeEqual } from "node:crypto";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT ?? 8092);
const INTERNAL_TOKEN = process.env.DISCOVERY_RUNNER_INTERNAL_TOKEN ?? "";
const JAVA_BASE_URL = (process.env.JAVA_INTERNAL_BASE_URL ?? "http://127.0.0.1:8080").replace(/\/+$/, "");
const PROVIDER_ID = process.env.PROVIDER_ID ?? "opengo";
const MODEL_ID = process.env.MODEL_ID ?? "deepseek-v4.1-flash";
const WORK_ROOT = path.join(__dirname, "work");

if (!INTERNAL_TOKEN) { console.error("DISCOVERY_RUNNER_INTERNAL_TOKEN 未设置"); process.exit(1); }
if (!process.env.OPENGO_API_KEY && !process.env.MINIMAX_API_KEY) {
  console.error("OPENGO_API_KEY（或 MINIMAX_API_KEY）未设置");
  process.exit(1);
}
mkdirSync(WORK_ROOT, { recursive: true });

const HARD_DEADLINE_MS = 30 * 60 * 1000;
const ORPHAN_SWEEP_MS = 2 * 60 * 60 * 1000;
const CONTEXT_HINT_PERCENT = 90;

const ZERO_USAGE = {
  input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 }
};

/** turnId -> 运行态。 */
const turns = new Map();

/** 窗口内历史卡片按 runner 内部形状播种进 observed，让模型能跨轮重复 shortlist（档三的「保留仍贴合的」）。 */
function seedHistoryCards(payload) {
  const observed = new Map();
  for (const c of payload.historyCards ?? []) {
    const id = String(c.arxivId ?? "").trim();
    if (id) observed.set(id, {
      arxiv_id: id,
      title: String(c.title ?? ""),
      summary: String(c.summary ?? "").slice(0, 400),
      authors: Array.isArray(c.authors) ? c.authors.slice(0, 6) : [],
      categories: Array.isArray(c.categories) ? c.categories.slice(0, 6) : [],
      published_year: c.publishedYear ?? null,
      library_status: c.libraryStatus ?? "NONE"
    });
  }
  return { observed };
}

function newState(payload) {
  return {
    turnId: String(payload.turnId ?? "").trim(),
    userId: String(payload.userId ?? "").trim(),
    kind: payload.kind === "DISTILL" ? "DISTILL" : "CHAT",
    lastMessage: String(payload.lastMessage ?? "").trim(),
    history: Array.isArray(payload.history) ? payload.history : [],
    memoryItems: Array.isArray(payload.memory) ? payload.memory : [],
    historyCardIds: new Set((payload.historyCardIds ?? []).map(String)),
    status: "STARTING", reasonCode: "",
    steps: [], observed: new Map(), cards: [],
    ...seedHistoryCards(payload),
    importResults: null, savedMemory: null, shortlisted: null,
    searchCount: 0, assistantText: "",
    queries: [],
    usage: { inputTokens: 0, outputTokens: 0 },
    session: null, watchdog: null, hintSent: false,
    startedAt: performance.now()
  };
}

/** 候选栏的可见集：模型筛过就只给筛过的（D2 shortlist），没筛就退回全量观测。 */
function visibleCards(state) {
  if (!state.shortlisted) return state.cards;
  const cards = [];
  for (const id of state.shortlisted) {
    const item = state.observed.get(id);
    if (item) cards.push({
      arxivId: item.arxiv_id, title: item.title,
      authors: item.authors, categories: item.categories,
      publishedYear: item.published_year, libraryStatus: item.library_status,
      summary: String(item.summary ?? "").slice(0, 240)
    });
  }
  return cards;
}

function snapshot(state) {
  return {
    turnId: state.turnId,
    status: state.status,
    reasonCode: state.reasonCode,
    assistantText: state.assistantText,
    cards: visibleCards(state),
    importResults: state.importResults,
    savedMemory: state.savedMemory,
    steps: state.steps.slice(-200),
    queries: state.queries.slice(-12),
    searchCount: state.searchCount,
    observedCount: state.observed.size,
    usage: state.usage,
    model: MODEL_ID,
    contextPercent: state.session ? state.session.getContextUsage()?.percent ?? null : null,
    elapsedMs: Math.round(performance.now() - state.startedAt)
  };
}

function release(state) {
  turns.delete(state.turnId);
  try { rmSync(path.join(WORK_ROOT, state.turnId), { recursive: true, force: true }); } catch { /* ignore */ }
}

// ---- 与 Java 的全部交互 ----

async function javaInternal(pathname, body) {
  const res = await fetch(`${JAVA_BASE_URL}${pathname}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": "Bearer " + INTERNAL_TOKEN },
    body: JSON.stringify(body)
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`Java ${pathname} ${res.status}: ${JSON.stringify(data).slice(0, 200)}`);
  }
  return data;
}

/** 决策 D4 的一次性提示。 */
function contextHint(state) {
  if (state.hintSent || !state.session) return "";
  let percent = state.session.getContextUsage()?.percent;
  if (percent == null || percent < CONTEXT_HINT_PERCENT) return "";
  state.hintSent = true;
  state.steps.push(`上下文已达 ${Math.round(percent)}%，提示模型收束`);
  return `\n[系统提示] 上下文已接近上限（${Math.round(percent)}%）。请立即结束本轮：给出结论或调用工具完成用户请求，不要再检索。`;
}

function buildChatTools(state) {
  const searchTool = defineTool({
    name: "search_arxiv_papers",
    label: "arXiv 检索",
    description:
      "在 arXiv 检索论文元数据；检索到的候选会自动出现在用户的候选栏，正文不必罗列清单。" +
      "可用不同检索词多次调用，不要重复完全相同的 query。",
    parameters: Type.Object({
      query: Type.String({ description: "检索词（英文术语效果最好）" }),
      max: Type.Optional(Type.Integer({ minimum: 1, maximum: 20, description: "返回条数上限，默认 10" })),
      start: Type.Optional(Type.Integer({ minimum: 0, description: "分页偏移" })),
      sort: Type.Optional(Type.Union([Type.Literal("relevance"), Type.Literal("submitted")])),
      year_from: Type.Optional(Type.Integer({ description: "发表年份下界（含）" })),
      year_to: Type.Optional(Type.Integer({ description: "发表年份上界（含）" }))
    }),
    async execute(_id, params) {
      let data;
      try {
        data = await javaInternal("/internal/v1/discovery/search", {
          turnId: state.turnId, userId: state.userId,
          query: params.query, start: params.start ?? 0,
          max: Math.min(params.max ?? 10, 20), sort: params.sort ?? "relevance",
          yearFrom: params.year_from ?? null, yearTo: params.year_to ?? null
        });
      } catch (e) {
        state.steps.push(`检索「${params.query}」失败：${String(e).slice(0, 160)}`);
        // 上游失败如实上抛，不静默变空列表
        return { content: [{ type: "text", text: JSON.stringify({ error: "search_upstream_failed", message: String(e).slice(0, 200) }) }], details: {} };
      }
      const items = (data.items ?? []).map(it => ({
        arxiv_id: it.arxivId,
        title: it.title,
        summary: String(it.summary ?? ""),
        authors: (it.authors ?? []).slice(0, 6),
        categories: (it.categories ?? []).slice(0, 6),
        published_year: it.publishedYear,
        library_status: it.libraryStatus
      }));
      for (const item of items) {
        if (!state.observed.has(item.arxiv_id)) {
          state.observed.set(item.arxiv_id, item);
          state.cards.push({
            arxivId: item.arxiv_id, title: item.title,
            authors: item.authors, categories: item.categories,
            publishedYear: item.published_year, libraryStatus: item.library_status,
            summary: String(item.summary ?? "").slice(0, 240)
          });
        }
      }
      state.searchCount += 1;
      state.queries.push({ q: params.query, hits: items.length });
      state.steps.push(`检索「${params.query}」→ ${items.length} 条（累计 ${state.observed.size}）`);
      const hint = contextHint(state);
      return {
        content: [{
          type: "text",
          text: JSON.stringify({ items, total: data.total ?? -1, has_next: data.hasNext ?? false }) + hint
        }],
        details: { query: params.query }
      };
    }
  });

  const shortlistTool = defineTool({
    name: "shortlist_papers",
    label: "筛选候选",
    description:
      "把这轮你真正推荐给用户的论文放进候选栏（完整 id 逐个列出，按推荐优先级排序）。" +
      "id 可以来自本轮检索，也可以来自之前轮次的候选（历史候选仍在池内，反馈轮可以直接沿用）。" +
      "没调过这个工具时，候选栏会显示本轮检索过的全部论文；调用后只显示你筛出的。" +
      "有合适的外部候选推荐时，在最终答复前调用；纯库内内容问答无需调用。",
    parameters: Type.Object({
      arxiv_ids: Type.Array(Type.String(), { minItems: 1, maxItems: 30 })
    }),
    async execute(_id, params) {
      const valid = [];
      const rejected = [];
      for (const id of params.arxiv_ids) {
        if (state.observed.has(id)) valid.push(id);
        else rejected.push(id);
      }
      if (valid.length === 0) {
        return { content: [{ type: "text", text: JSON.stringify({ error: "no_valid_ids", message: "所有 id 都不在候选池（本轮检索或窗口内历史候选）" }) }], details: {} };
      }
      state.shortlisted = valid;
      state.steps.push(
        rejected.length
          ? `筛选出 ${valid.length} 篇推荐候选（${rejected.length} 个无效 id 被忽略）`
          : `筛选出 ${valid.length} 篇推荐候选`
      );
      return {
        content: [{ type: "text", text: JSON.stringify({ shortlisted: valid.length, rejected }) }],
        details: { count: valid.length }
      };
    }
  });

  const importTool = defineTool({
    name: "import_arxiv_papers",
    label: "入库",    description:
      "把候选论文批量入库（仅在用户明确表达入库意图时调用，绝不自行调用）。" +
      "arxiv_id 必须来自本轮或之前卡片里真实出现过的 id。",
    parameters: Type.Object({
      arxiv_ids: Type.Array(Type.String(), { minItems: 1, maxItems: 20 })
    }),
    async execute(_id, params) {
      const allowed = new Set([...state.historyCardIds, ...state.observed.keys()]);
      const bad = params.arxiv_ids.filter(id => !allowed.has(id));
      if (bad.length) {
        state.steps.push(`入库被拒：${bad.length} 个 id 不在候选池`);
        const digest = [...allowed].slice(0, 60).join(", ");
        return {
          content: [{ type: "text", text: JSON.stringify({ ok: false, errors: ["以下 id 不在候选池: " + bad.join(", ")], allowed_digest: digest }) }],
          details: {}
        };
      }
      const data = await javaInternal("/internal/v1/discovery/import", {
        turnId: state.turnId, userId: state.userId, arxivIds: params.arxiv_ids
      });
      state.importResults = data.items ?? [];
      const okCount = state.importResults.filter(r => r.status !== "FAILED").length;
      state.steps.push(`入库请求 ${params.arxiv_ids.length} 篇：完成 ${okCount}，失败 ${state.importResults.length - okCount}`);
      return { content: [{ type: "text", text: JSON.stringify({ ok: true, results: state.importResults }) }], details: {} };
    }
  });

  const removeTool = defineTool({
    name: "remove_candidates",
    label: "移除候选",
    description:
      "把用户明确说不要的论文从候选栏移除（只在他表达排除/删掉/不要了这类意图时调用，绝不自行清理候选）。" +
      "arxiv_id 必须来自当前候选栏或本轮检索结果。已入库的论文只影响候选展示，不会删库内论文。",
    parameters: Type.Object({
      arxiv_ids: Type.Array(Type.String(), { minItems: 1, maxItems: 30 })
    }),
    async execute(_id, params) {
      const allowed = new Set([...state.historyCardIds, ...state.observed.keys()]);
      const bad = params.arxiv_ids.filter(id => !allowed.has(id));
      if (bad.length) {
        state.steps.push(`移除候选被拒：${bad.length} 个 id 不在候选池`);
        const digest = [...allowed].slice(0, 60).join(", ");
        return {
          content: [{ type: "text", text: JSON.stringify({ ok: false, errors: ["以下 id 不在候选池: " + bad.join(", ")], allowed_digest: digest }) }],
          details: {}
        };
      }
      const data = await javaInternal("/internal/v1/discovery/candidates/remove", {
        turnId: state.turnId, userId: state.userId, arxivIds: params.arxiv_ids
      });
      const removed = new Set(params.arxiv_ids);
      for (const id of removed) state.observed.delete(id);
      state.cards = state.cards.filter(c => !removed.has(c.arxivId));
      if (state.shortlisted) state.shortlisted = state.shortlisted.filter(id => !removed.has(id));
      state.historyCardIds = new Set([...state.historyCardIds].filter(id => !removed.has(id)));
      state.steps.push(`移除 ${removed.size} 篇候选`);
      return {
        content: [{ type: "text", text: JSON.stringify({ ok: true, removed: [...removed], rejected: data.rejected ?? [], removedRows: data.removedRows ?? 0 }) }],
        details: {}
      };
    }
  });

  return [searchTool, shortlistTool, importTool, removeTool, ...buildReadingTools(state, javaInternal)];
}

function buildDistillTools(state) {
  const saveTool = defineTool({
    name: "save_memory",
    label: "写入长期记忆",
    description:
      "把当前会话窗口中值得长期保留的信息写入长期记忆。kind 只能是 INSIGHT（事实性洞察/方向/结论）" +
      "或 PREF（研究偏好）。每条 ≤300 字，只写窗口里出现过的信息，不猜。",
    parameters: Type.Object({
      items: Type.Array(
        Type.Object({
          kind: Type.Union([Type.Literal("INSIGHT"), Type.Literal("PREF")]),
          content: Type.String({ maxLength: 300 })
        }),
        { minItems: 1, maxItems: 20 }
      )
    }),
    async execute(_id, params) {
      const data = await javaInternal("/internal/v1/discovery/memory", {
        userId: state.userId, items: params.items
      });
      state.savedMemory = data.items ?? [];
      state.steps.push(`沉淀 ${state.savedMemory.length} 条长期记忆`);
      return { content: [{ type: "text", text: JSON.stringify({ ok: true, saved: state.savedMemory.length }) }], details: {} };
    }
  });
  return [saveTool];
}

// ---- system prompt ----

const DISTILL_CONTRACT = `你是 PaperLoom 论文发现助理的记忆沉淀器。任务：从当前对话中提炼值得长期保留的信息，调用 save_memory 一次性写入。

规则：
1. 只有一个工具：save_memory（kind ∈ INSIGHT/PREF，每条 ≤300 字）。
2. 只沉淀窗口里真实出现过的信息：研究方向、工作背景、明确表达过的偏好、有价值的排除结论；不猜、不脑补。
3. 不要沉淀候选论文清单这类短期工作集（它们在卡片里，翻页就能看到）。
4. 尽量避免与「已有长期记忆」重复；重复主题可以合并成更准的新表述。
5. 保存后用一两句中文向用户确认沉淀了什么，不要输出清单原文。`;

function memoryBlocks(memoryItems) {
  if (!memoryItems || !memoryItems.length) {
    return "\n（当前没有长期记忆。）";
  }
  const byKind = kind => memoryItems.filter(m => m.kind === kind).map(m => `- ${m.content}`);
  const sections = [];
  const role = byKind("ROLE"); if (role.length) sections.push(`## 助理角色设定（用户定制）\n${role.join("\n")}`);
  const pref = byKind("PREF"); if (pref.length) sections.push(`## 用户研究偏好\n${pref.join("\n")}`);
  const insight = byKind("INSIGHT"); if (insight.length) sections.push(`## 长期沉淀的记忆\n${insight.join("\n")}`);
  return sections.length ? "\n" + sections.join("\n\n") : "\n（当前没有长期记忆。）";
}

function buildSystemPrompt(state) {
  const memory = memoryBlocks(state.memoryItems);
  if (state.kind === "DISTILL") {
    return DISTILL_CONTRACT + memory;
  }
  return CHAT_CONTRACT + memory;
}

// ---- 历史注入（SDK 原生消息条目） ----

function historyEntries(state, workDir) {
  const entries = [{
    type: "session",
    id: `turn-${state.turnId}`,
    timestamp: new Date().toISOString(),
    cwd: workDir
  }];
  let parentId = null;
  let n = 0;
  for (const m of state.history) {
    const id = `h${++n}`;
    const message = m.role === "ASSISTANT"
      ? {
          role: "assistant",
          content: [{ type: "text", text: String(m.text ?? "") }],
          api: "openai-completions",
          provider: PROVIDER_ID,
          model: MODEL_ID,
          usage: ZERO_USAGE,
          stopReason: "stop"
        }
      : { role: "user", content: String(m.text ?? ""), timestamp: Date.now() };
    entries.push({ type: "message", id, parentId, timestamp: new Date().toISOString(), message });
    parentId = id;
  }
  return entries;
}

let modelRuntime;

async function startTurn(state) {
  const workDir = path.join(WORK_ROOT, state.turnId);
  mkdirSync(workDir, { recursive: true });
  const loader = new DefaultResourceLoader({
    cwd: workDir,
    agentDir: path.join(__dirname, "agent-dir"),
    systemPromptOverride: () => buildSystemPrompt(state),
    appendSystemPromptOverride: () => []
  });
  await loader.reload();
  const model = modelRuntime.getModel(PROVIDER_ID, MODEL_ID);
  if (!model) throw new Error(`models.json 未注册出 ${PROVIDER_ID}/${MODEL_ID}`);

  const customTools = state.kind === "DISTILL" ? buildDistillTools(state) : buildChatTools(state);
  const { session } = await createAgentSession({
    cwd: workDir,
    model,
    modelRuntime,
    // 安全事实（AS对照 pi 1.5.0 源码与对抗实测验证）：
    // agent 可调用集合只装 allowlist 工具；模型发明的 bash/read 调用会在 prepareToolCall 的
    // tools.find() 落空返回 "Tool not found"，不执行任何代码。本进程 env 持有 MINIMAX_API_KEY
    // 与 internal token，shell 可用即等于密钥泄漏——工具面锁定是本服务唯一持密边界。
    tools: customTools.map(tool => tool.name),
    customTools,
    sessionManager: SessionManager.inMemory(workDir, undefined, historyEntries(state, workDir)),
    settingsManager: SettingsManager.inMemory({ compaction: { enabled: false } }),
    resourceLoader: loader
  });
  state.session = session;
  session.subscribe(event => {
    if (event.type === "message_update" && event.assistantMessageEvent?.type === "text_delta") {
      state.assistantText += event.assistantMessageEvent.delta || "";
    }
    if (event.type === "message_end" && event.message?.role === "assistant" && event.message?.usage) {
      const usage = event.message.usage;
      state.usage.inputTokens += usage.input ?? 0;
      state.usage.outputTokens += usage.output ?? 0;
    }
  });
  state.steps.push(state.kind === "DISTILL" ? "开始沉淀窗口" : "开始处理消息");
  state.status = "RUNNING";

  state.watchdog = setTimeout(() => {
    if (state.status !== "RUNNING") return;
    state.status = "FAILED"; state.reasonCode = "hard_deadline_exceeded";
    state.steps.push("达到 30 分钟进程卫生 deadline，强制中止");
    try { session.abort(); } catch { /* ignore */ }
  }, HARD_DEADLINE_MS);

  try {
    await session.prompt(state.lastMessage || "（用户没有附加说明，请按指令行事。）");
  } catch (e) {
    if (state.status === "RUNNING") {
      state.status = "FAILED"; state.reasonCode = "prompt_error";
      state.steps.push(`会话异常终止：${String(e).slice(0, 200)}`);
    }
  } finally {
    if (state.status === "RUNNING") {
      if (state.shortlist || state.assistantText || state.usage.outputTokens > 0
        || (state.kind === "CHAT" && state.observed.size > 0)
        || (state.kind === "DISTILL" && state.savedMemory?.length)) {
        state.status = "COMPLETED";
      } else {
        // provider 429/配额耗尽会被 pi 吞成空结束：转成显式 FAILED（诚实终态）
        state.status = "FAILED";
        state.reasonCode = "model_output_empty";
        state.steps.push("模型没有产生任何输出（可能配额/限流），已判 FAILED");
      }
    }
    try { session.dispose(); } catch { /* ignore */ }
    state.session = null; // 快照不再读 getContextUsage
    if (state.watchdog) clearTimeout(state.watchdog);
  }
}

// ---- HTTP 边界 ----

function authorized(req) {
  const expected = Buffer.from("Bearer " + INTERNAL_TOKEN, "utf8");
  const supplied = Buffer.from(String(req.headers.authorization ?? "").trim(), "utf8");
  return expected.length === supplied.length && timingSafeEqual(expected, supplied);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", c => chunks.push(c));
    req.on("end", () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8"))); }
      catch (e) { reject(e); }
    });
    req.on("error", reject);
  });
}

function send(res, status, payload) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(payload));
}

async function route(req, res) {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const parts = url.pathname.split("/").filter(Boolean);
  if (url.pathname === "/health") return send(res, 200, { ok: true, active_turns: turns.size });

  if (!authorized(req)) return send(res, 401, { error: "unauthorized" });
  if (parts[0] !== "v1" || parts[1] !== "discovery" || parts[2] !== "turns") {
    return send(res, 404, { error: "not_found" });
  }
  const turnId = parts[3];
  if (!turnId) {
    if (req.method !== "POST") return send(res, 405, { error: "method_not_allowed" });
    const payload = await readJson(req);
    const state = newState(payload);
    // CHAT 必须有话；DISTILL（/沉淀 无附加说明）允许 lastMessage 为空
    if (!state.turnId || !state.userId || (state.kind === "CHAT" && !state.lastMessage)) {
      return send(res, 400, { error: "turnId/userId 必填；CHAT 的 lastMessage 必填" });
    }
    turns.set(state.turnId, state);
    if (payload.selfTest) {
      state.steps.push("selftest 桩");
    } else {
      startTurn(state).catch(e => {
        if (state.status !== "RUNNING" && state.status !== "COMPLETED") {
          state.status = "FAILED";
          state.reasonCode = "runner_error";
          state.steps.push(`启动失败：${String(e).slice(0, 200)}`);
        }
        console.error(`[runner] turn ${state.turnId} 启动失败:`, e);
      });
    }
    return send(res, 202, { ok: true, turnId: state.turnId });
  }
  const state = turns.get(turnId);
  if (!state) return send(res, 404, { error: "unknown_turn" });
  if (req.method === "GET") return send(res, 200, snapshot(state));
  if (req.method === "DELETE") {
    release(state);
    return send(res, 200, { ok: true });
  }
  if (req.method === "POST" && parts[4] === "cancel") {
    if (state.status === "RUNNING" || state.status === "STARTING") {
      state.status = "CANCELLED";
      state.steps.push("收到取消");
      if (state.session) { try { state.session.abort(); } catch { /* ignore */ } }
    }
    return send(res, 200, { ok: true, status: state.status });
  }
  return send(res, 405, { error: "method_not_allowed" });
}

// 终态 turn 2 小时无人 DELETE 的自动回收
setInterval(() => {
  for (const state of turns.values()) {
    if (state.status !== "RUNNING" && state.status !== "STARTING" && performance.now() - state.startedAt > ORPHAN_SWEEP_MS) {
      console.log(`[runner] 回收孤儿 turn ${state.turnId}`);
      release(state);
    }
  }
}, 10 * 60 * 1000).unref();

const server = http.createServer((req, res) => {
  route(req, res).catch(e => {
    console.error("[runner] 路由异常:", e);
    try { send(res, 500, { error: "internal" }); } catch { /* ignore */ }
  });
});

async function main() {
  modelRuntime = await ModelRuntime.create({
    modelsPath: path.join(__dirname, "models.json"),
    authPath: path.join(__dirname, "agent-dir", "auth.json")
  });
  server.listen(PORT, "127.0.0.1", async () => {
    console.log(`[runner] listening on 127.0.0.1:${PORT}, model=${MODEL_ID}, java=${JAVA_BASE_URL}`);
    if (!process.argv.includes("--selftest")) return;
    // 最小自检：鉴权拒绝 + 健康端点 + 未知 turn 404 + 自检桩注册（不建会话、不花 token）
    const base = `http://127.0.0.1:${PORT}`;
    await fetch(base + "/v1/discovery/turns", {
      method: "POST",
      headers: { "Authorization": "Bearer " + INTERNAL_TOKEN, "Content-Type": "application/json" },
      body: JSON.stringify({ turnId: "selftest-turn", userId: "selftest", lastMessage: "自检", selfTest: true })
    });
    const health = await fetch(base + "/health").then(r => r.json());
    const unauth = await fetch(base + "/v1/discovery/turns", { method: "POST" });
    const stub = await fetch(base + "/v1/discovery/turns/selftest-turn", {
      headers: { "Authorization": "Bearer " + INTERNAL_TOKEN }
    }).then(r => r.json());
    const bad = await fetch(base + "/v1/discovery/turns/unknown-id", {
      headers: { "Authorization": "Bearer " + INTERNAL_TOKEN }
    });
    console.log("[selftest]", {
      health: health.ok, unauth: unauth.status, stubStatus: stub.status, unknownTurn: bad.status
    });
    if (!(health.ok === true && unauth.status === 401 && stub.status === "STARTING" && bad.status === 404)) {
      console.error("[selftest] 失败");
      process.exit(1);
    }
    console.log("[selftest] 通过");
    process.exit(0);
  });
}

main().catch(e => {
  console.error("[runner] 启动失败:", e);
  process.exit(1);
});
