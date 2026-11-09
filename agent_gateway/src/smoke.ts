// No-model self-check: final-answer gate, projection, and request parsing asserts.

import assert from "node:assert";
import { accepted, renderAnswer, validateSubmission, type SubmitPayload, type EvidenceRecord } from "./validation.ts";
import { parseRunLimits, DEFAULT_RUN_LIMITS } from "./config.ts";
import { parseRequest } from "./run.ts";

const quotes: Map<string, EvidenceRecord> = new Map([
  ["source_quote_1", { source_quote_ref: "source_quote_1", paper_id: "p1", page: 2, section: "Method", span_text: "quote one" }],
  ["source_quote_2", { source_quote_ref: "source_quote_2", paper_id: "p1", page: 3, section: "Method", span_text: "quote two" }],
]);

const submit = (over: Partial<SubmitPayload>): SubmitPayload => ({
  outcome: "answered",
  language: "EN",
  markdown: null,
  fields: {},
  abstention_reason: null,
  ...over,
});

async function main(): Promise<void> {
  // 1. answered with citations in every block is accepted
  const answered = validateSubmission(
    submit({ markdown: "LoRA freezes weights [[source_quote_1]]. It uses rank decomposition [[source_quote_2]]." }),
    quotes,
  );
  assert(accepted(answered), `expected acceptance, got ${JSON.stringify(answered.issues)}`);

  // 2. uncited content block rejected
  const uncited = validateSubmission(
    submit({ markdown: "Cited part [[source_quote_1]].\n\nThis block cites nothing." }),
    quotes,
  );
  assert(uncited.issues.some((issue) => issue.code === "UNCITED_CONTENT_BLOCK"), JSON.stringify(uncited.issues));

  // 3. unknown quote ref rejected
  const unknown = validateSubmission(submit({ markdown: "Claim [[source_quote_missing]]." }), quotes);
  assert(unknown.issues.some((issue) => issue.code === "UNKNOWN_SOURCE_REF"), JSON.stringify(unknown.issues));

  // 4. numeric citation rejected
  const numeric = validateSubmission(submit({ markdown: "Claim [1] with numeric citation." }), quotes);
  assert(numeric.issues.some((issue) => issue.code === "NUMERIC_CITATION_NOT_ALLOWED"), JSON.stringify(numeric.issues));

  // 5. abstained accepts reason and forbids markdown
  const abstainedOk = validateSubmission(
    submit({ outcome: "abstained", abstention_reason: "NO_SUPPORTING_SOURCE", language: "ZH_CN", markdown: null }),
    quotes,
  );
  assert(accepted(abstainedOk), JSON.stringify(abstainedOk.issues));
  const abstainedBad = validateSubmission(
    submit({ outcome: "abstained", abstention_reason: "NO_SUPPORTING_SOURCE", markdown: "text" }),
    quotes,
  );
  assert(abstainedBad.issues.some((issue) => issue.code === "ABSTENTION_MARKDOWN_NOT_ALLOWED"));

  // 6. needs_clarification forbids evidence markers, allows a plain question
  const clarifyOk = validateSubmission(
    submit({ outcome: "needs_clarification", markdown: "Which paper do you mean?" }),
    quotes,
  );
  assert(accepted(clarifyOk), JSON.stringify(clarifyOk.issues));
  const clarifyBad = validateSubmission(
    submit({ outcome: "needs_clarification", markdown: "See [[source_quote_1]]." }),
    quotes,
  );
  assert(clarifyBad.issues.some((issue) => issue.code === "MARKDOWN_INVALID"), JSON.stringify(clarifyBad.issues));

  // 7. invalid outcome rejected
  const bogus = validateSubmission(
    submit({ outcome: "bogus" as never, markdown: "x" }),
    quotes,
  );
  assert(bogus.issues.some((issue) => issue.code === "OUTCOME_INVALID"));

  // 8. citation rendering renumbers in first-appearance order
  const rendered = renderAnswer(
    submit({ markdown: "B first [[source_quote_2]] then A [[source_quote_1]]." }),
    quotes,
  );
  assert(rendered.markdown.includes("[1]") && rendered.markdown.includes("[2]"));
  assert(rendered.markdown.indexOf("[1]") < rendered.markdown.indexOf("[2]"), "quote_2 cited first must render as [1]");
  assert(rendered.citations[0].reference_number === 1);
  assert((rendered.citations[0].paper_id as string) === "p1");

  // 9. run limits parsing rejects schema drift
  const limitsOk = parseRunLimits({ run_limits: { schema_version: "paperloom-run-limits/v1", ...DEFAULT_RUN_LIMITS } });
  assert(limitsOk.max_wall_clock_ms === DEFAULT_RUN_LIMITS.max_wall_clock_ms);
  assert.throws(() => parseRunLimits({ run_limits: { schema_version: "paperloom-run-limits/v1", ...DEFAULT_RUN_LIMITS, extra: 1 } }));
  assert.throws(() => parseRunLimits({ run_limits: { schema_version: "wrong/v2" } }));
  assert.throws(() => parseRunLimits({ run_limits: { schema_version: "paperloom-run-limits/v1", ...DEFAULT_RUN_LIMITS, max_wall_clock_ms: -1 } }));

  // 10. request parsing essentials
  const request = parseRequest(
    {
      request_id: "gen-1",
      conversation_id: "conv-1",
      user_id: 7,
      user_message: "hi",
      scope: { paper_ids: ["a", "a", "b"] },
      history: [{ role: "user", content: "x" }, { role: "tool", content: "ignored" }],
      research_memory: { previous_evidence: [{ source_quote_ref: "source_quote_9", paper_id: "a" }] },
      options: { include_trace: false },
    },
    DEFAULT_RUN_LIMITS,
  );
  assert.deepStrictEqual(request.scope.paper_ids, ["a", "b"]);
  assert(request.history.length === 1);
  assert(Object.keys(request.research_memory.previous_evidence).includes("source_quote_9"));
  assert(!request.includeTrace);
  assert.throws(() => parseRequest({ conversation_id: "c", user_id: 1, scope: { paper_ids: ["a"] } }, DEFAULT_RUN_LIMITS), /user_message/);
  assert.throws(() => parseRequest({ user_message: "m", conversation_id: "c", scope: { paper_ids: [] } }, DEFAULT_RUN_LIMITS), /paper_ids/);

  console.log("smoke: all checks passed");
}

void main();
