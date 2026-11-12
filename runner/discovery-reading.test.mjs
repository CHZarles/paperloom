import test from "node:test";
import assert from "node:assert/strict";
import { CHAT_CONTRACT, buildReadingTools } from "./discovery-reading.mjs";

function fixture(reply = {}) {
  const calls = [];
  const state = { userId: "7", turnId: "turn/one", steps: [] };
  const tools = buildReadingTools(state, async (route, body) => {
    calls.push({ route, body });
    return reply;
  });
  return { calls, state, tools };
}
const text = result => JSON.parse(result.content[0].text);

test("discovery reading tools use external endpoints only and bind the host turn/user", async () => {
  const { tools, calls } = fixture();
  assert.deepEqual(tools.map(t => t.name), ["search_external_papers", "read_external_paper"]);
  await tools[0].execute("search", { query: "Ding density peaks", limit: 5, userId: "attacker" });
  await tools[1].execute("read", { doi: "10.1234/example", userId: "attacker", scope_paper_ids: ["internal"] });
  assert.deepEqual(calls.map(c => c.route), [
    "/internal/v1/discovery/turns/turn%2Fone/external/search",
    "/internal/v1/discovery/turns/turn%2Fone/external/read"
  ]);
  assert.ok(calls.every(c => c.body.userId === "7"));
  assert.ok(calls.every(c => c.body.scope_paper_ids === undefined && c.body.paper_ids === undefined));
  assert.equal(calls[0].body.max, 5);
});

test("external metadata search preserves DOI identity and is not evidence", async () => {
  const items = [{ doi: "10.1234/example", title: "Density peaks", authors: ["Ding"], year: 2025,
    sourceUrl: "https://doi.org/10.1234/example" }];
  const { tools, calls } = fixture({ source: "crossref", basis: "metadata_only", evidence: false, items, total: 1 });
  const result = text(await tools[0].execute("search", { query: "Ding 2025 density peaks" }));
  assert.equal(calls[0].body.query, "Ding 2025 density peaks");
  assert.equal(result.basis, "metadata_only");
  assert.equal(result.evidence, false);
  assert.deepEqual(result.items, items);
});

test("external PDF read retains actual page, source and truncation without inventing library evidence", async () => {
  const items = [{ text: "Extracted public PDF text", page: 4, offset: 10000 }];
  const reply = { status: "partial", incomplete: true, basis: "external_fulltext", source: "arxiv",
    sourceUrl: "https://arxiv.org/pdf/2309.13420v2", arxivId: "2309.13420v2", items, nextOffset: 20000 };
  const { tools, calls } = fixture(reply);
  const result = text(await tools[1].execute("read", { arxiv_id: "2309.13420v2", offset: 10000, max_chars: 10000 }));
  assert.deepEqual(calls[0].body, { userId: "7", arxivId: "2309.13420v2", doi: undefined, offset: 10000, maxChars: 10000 });
  assert.deepEqual(result, reply);
  assert.equal(result.evidence_id, undefined);
  assert.equal(result.source_quote_ref, undefined);
  assert.equal(result.paper_id, undefined);
});

test("non-arXiv DOI reading preserves external XML sections without invented PDF pages", async () => {
  const reply = { status: "complete", incomplete: false, basis: "external_fulltext", source: "europe_pmc",
    sourceUrl: "https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML", doi: "10.1234/example",
    items: [{ text: "Open access body text", section: "Methods", offset: 0 }], nextOffset: null };
  const { tools, calls } = fixture(reply);
  const result = text(await tools[1].execute("read", { doi: "10.1234/example" }));
  assert.equal(calls[0].body.doi, "10.1234/example");
  assert.deepEqual(result, reply);
  assert.equal(result.items[0].page, undefined);
});

test("unavailable full text is never relabeled as an exact read", async () => {
  const reply = { status: "unavailable", incomplete: true, basis: "unavailable", items: [],
    reason: "No supported open access full text", nextOffset: null };
  const { tools } = fixture(reply);
  assert.deepEqual(text(await tools[1].execute("read", { doi: "10.1234/closed" })), reply);
});

test("upstream/auth failures remain errors instead of successful empty search or full text", async () => {
  const state = { turnId: "t", userId: "7", steps: [] };
  const tools = buildReadingTools(state, async () => { throw new Error("Java 502 upstream failed"); });
  for (const tool of tools) {
    const result = await tool.execute("call", {});
    assert.equal(result.isError, true);
    assert.equal(text(result).error, "external_paper_operation_failed");
    assert.match(text(result).message, /502 upstream failed/);
    assert.equal(text(result).items, undefined);
  }
});

test("contract makes pre-import external reading independent from system reading", () => {
  assert.match(CHAT_CONTRACT, /理解论文，并根据读到的内容作推荐/);
  assert.match(CHAT_CONTRACT, /内容获取独立于 PaperLoom 论文库、Reading Model 和系统内阅读接口/);
  assert.match(CHAT_CONTRACT, /不要要求用户先入库再解释/);
  assert.match(CHAT_CONTRACT, /不代表论文不存在/);
  assert.match(CHAT_CONTRACT, /仅据摘要、待原文核实/);
  assert.match(CHAT_CONTRACT, /只对仍无法确认的身份或全文请求/);
  assert.match(CHAT_CONTRACT, /引用工具返回的真实来源 URL/);
  assert.doesNotMatch(CHAT_CONTRACT, /search_library_papers|find_library_locations|read_library_locations/);
  assert.doesNotMatch(CHAT_CONTRACT, /任何一档都不做|判断只依据元数据|250 字以内|职能只有一句话/);
});
