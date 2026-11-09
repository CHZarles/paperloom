// Model-visible tools: the 5 corpus tools + submit_answer, registered as pi custom tools.

import { Type } from "typebox";
import type { ToolDefinition } from "@earendil-works/pi-coding-agent";
import { CorpusClient, RecoverableCorpusError, type TurnContext } from "./corpus.ts";
import {
  accepted,
  renderAnswer,
  validateSubmission,
  type AnswerOutcome,
  type EvidenceRecord,
  type SubmitPayload,
} from "./validation.ts";

const EVIDENCE_ELEMENT_TYPES = ["passage", "table", "figure"];
const PAPER_RESULT_LIMIT = 100;
const SEARCH_RESULT_LIMIT = 20;

// Internal fields never shown to the model, ported from MODEL_REDACTED_FIELDS.
const MODEL_REDACTED_FIELDS = new Set([
  "matched_anchor_id", "matched_anchor_ids", "evidence_anchor_id", "dense_score", "sparse_score",
  "fused_score", "index_version", "source_span_json", "bbox_json", "bbox_or_cell_ref",
  "parser_name", "parser_version",
]);

export type RunState = {
  ctx: TurnContext;
  cancelCheck: () => void;
  authorizedPaperIds: Set<string>;
  disclosedLocationRefs: Set<string>;
  structureLocationRefs: Set<string>;
  observations: Map<string, EvidenceRecord>;
  submittedAnswer: { payload: SubmitPayload; rendered: ReturnType<typeof renderAnswer> } | null;
  trace: { tool: string; args: Record<string, unknown>; ok: boolean; error?: string }[];
};

export function newRunState(ctx: TurnContext, cancelCheck: () => void): RunState {
  return {
    ctx,
    cancelCheck,
    authorizedPaperIds: new Set(),
    disclosedLocationRefs: new Set(),
    structureLocationRefs: new Set(),
    observations: new Map(),
    submittedAnswer: null,
    trace: [],
  };
}

export function buildTools(state: RunState, corpus: CorpusClient, maxVisibleChars: number): ToolDefinition[] {
  const guard = (name: string) => {
    state.cancelCheck();
    if (state.submittedAnswer) {
      throw new Error("submission already accepted: no further tool calls are allowed");
    }
  };

  const wrap = async (
    name: string,
    args: Record<string, unknown>,
    run: () => Promise<Record<string, unknown>> | Record<string, unknown>,
  ) => {
    guard(name);
    try {
      const payload = await run();
      const visible = project(payload);
      state.trace.push({
        tool: name,
        args: briefArgs(name, args),
        ok: !visible.error_code,
        error: visible.error_code ? String(visible.error_code) : undefined,
      });
      return textResult(visible, maxVisibleChars);
    } catch (error) {
      if (error instanceof RecoverableCorpusError) {
        state.trace.push({ tool: name, args: briefArgs(name, args), ok: false, error: error.code });
        return textResult(
          {
            error: error.code.toLowerCase(),
            error_code: error.code,
            recoverable: true,
            message: error.safeMessage,
          },
          maxVisibleChars,
        );
      }
      throw error;
    }
  };

  const searchPaperCandidates: ToolDefinition = {
    name: "search_paper_candidates",
    label: "Search paper candidates",
    description:
      "Search or browse candidate papers in the fixed corpus using title, abstract, author, venue, year, and metadata. Results are authoritative for corpus counts, inventories, identities, and metadata filters, but are not citeable paper-content evidence. Use an empty query_text with a large limit to inspect the complete fixed corpus in one call.",
    parameters: Type.Object({
      query_text: Type.Optional(Type.String()),
      paper_ids: Type.Optional(Type.Array(Type.String())),
      authors: Type.Optional(Type.Array(Type.String())),
      venues: Type.Optional(Type.Array(Type.String())),
      year_from: Type.Optional(Type.Integer()),
      year_to: Type.Optional(Type.Integer()),
      offset: Type.Optional(Type.Integer({ minimum: 0 })),
      limit: Type.Optional(Type.Integer({ minimum: 1, maximum: PAPER_RESULT_LIMIT })),
    }),
    executionMode: "sequential",
    execute(_id, params, _signal, _onUpdate, _ctx) {
      const args = params as Record<string, unknown>;
      return wrap("search_paper_candidates", args, async () => {
        const payload = await corpus.searchPapers(state.ctx, args);
        for (const card of asList(payload.candidates)) {
          const paperId = String((card as Record<string, unknown>).paper_id || "");
          if (paperId) state.authorizedPaperIds.add(paperId);
        }
        return payload;
      });
    },
  };

  const findPapersByIdentity: ToolDefinition = {
    name: "find_papers_by_identity",
    label: "Find papers by identity",
    description:
      "Resolve a specific paper from structured identity hints. Do not use this tool for topical discovery, recommendations, or generic research questions.",
    parameters: Type.Object({
      paper_id: Type.Optional(Type.String()),
      title: Type.Optional(Type.String()),
      filename: Type.Optional(Type.String()),
      doi: Type.Optional(Type.String()),
      arxiv_id: Type.Optional(Type.String()),
      authors: Type.Optional(Type.Array(Type.String())),
      year: Type.Optional(Type.Integer()),
    }),
    executionMode: "sequential",
    execute(_id, params, _signal, _onUpdate, _ctx) {
      const args = params as Record<string, unknown>;
      return wrap("find_papers_by_identity", args, async () => {
        const payload = await corpus.findPapersByIdentity(state.ctx, args);
        if (payload.status === "resolved") {
          for (const card of asList(payload.matches)) {
            const paperId = String((card as Record<string, unknown>).paper_id || "");
            if (paperId) state.authorizedPaperIds.add(paperId);
          }
        }
        return payload;
      });
    },
  };

  const searchPaperContent: ToolDefinition = {
    name: "search_paper_content",
    label: "Search paper content",
    description:
      "Search relevant passages, tables, and figures inside previously disclosed candidate papers. Use concise English keywords when the paper text is English; do not pass a full Chinese question unchanged as query_text. For Appendix, Section, Table, Figure, page, or section-number questions, call get_paper_structure first and pass its returned section location refs as section_refs. If the first search is not useful, rewrite the query once before expanding the search. Returns non-citeable previews and location refs. Read selected refs before making paper-content claims.",
    parameters: Type.Object({
      paper_ids: Type.Array(Type.String()),
      query_text: Type.Optional(Type.String()),
      section_query: Type.Optional(Type.String()),
      section_refs: Type.Optional(Type.Array(Type.String())),
      element_types: Type.Optional(Type.Array(Type.String({ enum: [...EVIDENCE_ELEMENT_TYPES] }))),
      page_from: Type.Optional(Type.Integer({ minimum: 1 })),
      page_to: Type.Optional(Type.Integer({ minimum: 1 })),
      top_k: Type.Optional(Type.Integer({ minimum: 1, maximum: SEARCH_RESULT_LIMIT })),
    }),
    executionMode: "sequential",
    execute(_id, params, _signal, _onUpdate, _ctx) {
      const args = params as Record<string, unknown>;
      return wrap("search_paper_content", args, async () => {
        const disclosure = paperDisclosureError(args, state, "locations");
        if (disclosure) return disclosure;
        const sectionRefError = locationDisclosureError(args, state, "locations", "section_refs");
        if (sectionRefError) return sectionRefError;
        const payload = await corpus.searchLocations(state.ctx, args);
        for (const item of asList(payload.locations)) {
          const ref = String((item as Record<string, unknown>).location_ref || "");
          if (ref) state.disclosedLocationRefs.add(ref);
        }
        return payload;
      });
    },
  };

  const getPaperStructure: ToolDefinition = {
    name: "get_paper_structure",
    label: "Get paper structure",
    description:
      "Browse the section outline or page structure of previously disclosed papers. This returns only navigation metadata and location refs, never paper body text or citations. Do not cite or read the returned section ref as body evidence; pass it as section_refs to search_paper_content, then read a returned passage, table, or figure ref.",
    parameters: Type.Object({
      paper_ids: Type.Array(Type.String()),
      structure_type: Type.Optional(Type.String({ enum: ["SECTION", "PAGE"] })),
      section_query: Type.Optional(Type.String()),
      page_from: Type.Optional(Type.Integer({ minimum: 1 })),
      page_to: Type.Optional(Type.Integer({ minimum: 1 })),
    }),
    executionMode: "sequential",
    execute(_id, params, _signal, _onUpdate, _ctx) {
      const args = params as Record<string, unknown>;
      return wrap("get_paper_structure", args, async () => {
        const disclosure = paperDisclosureError(args, state, "items");
        if (disclosure) return disclosure;
        const payload = await corpus.getStructure(state.ctx, args);
        for (const item of asList(payload.items)) {
          const ref = String((item as Record<string, unknown>).location_ref || "");
          if (ref) {
            state.disclosedLocationRefs.add(ref);
            state.structureLocationRefs.add(ref);
          }
        }
        return payload;
      });
    },
  };

  const readPaperContent: ToolDefinition = {
    name: "read_paper_content",
    label: "Read paper content",
    description:
      "Read exact content from passage, table, or figure location refs returned by search_paper_content. For multi-part questions, include relevant refs for each requested part in one call when possible. Returns source_quote_ref values; only those values can be cited in the final answer.",
    parameters: Type.Object({
      location_refs: Type.Array(Type.String()),
    }),
    executionMode: "sequential",
    execute(_id, params, _signal, _onUpdate, _ctx) {
      const args = params as Record<string, unknown>;
      return wrap("read_paper_content", args, async () => {
        const disclosure = locationDisclosureError(args, state, "items", "location_refs");
        if (disclosure) return disclosure;
        const structureRefs = (args.location_refs as string[]).filter((ref) =>
          state.structureLocationRefs.has(ref),
        );
        if (structureRefs.length) {
          return {
            error: "structure_ref_not_evidence",
            error_code: "STRUCTURE_REF_NOT_EVIDENCE",
            recoverable: true,
            next_action: "search_paper_content",
            message: "Use section_refs with search_paper_content, then read a passage, table, or figure ref.",
          };
        }
        const payload = await corpus.readLocations(state.ctx, args);
        for (const rawItem of asList(payload.items)) {
          const item = rawItem as Record<string, unknown>;
          for (const rawQuote of asList(item.source_quotes)) {
            const quote = rawQuote as Record<string, unknown>;
            const sourceQuoteRef = String(quote.source_quote_ref || "");
            if (sourceQuoteRef) {
              // Item fields carry paper/page/section/span context for citation rendering.
              state.observations.set(sourceQuoteRef, {
                ...item,
                ...quote,
                source_quote_ref: sourceQuoteRef,
              } as EvidenceRecord);
            }
          }
        }
        return payload;
      });
    },
  };

  const submitAnswer: ToolDefinition = {
    name: "submit_answer",
    label: "Submit answer",
    description:
      "Submit the final user-facing answer for this turn. outcome: answered (final answer), needs_clarification (one blocking question), partial (supported parts, state what is missing), abstained (no supported answer; abstention_reason required). Paper-content claims must cite [[source_quote_...]] refs returned by read_paper_content in this run. This is the only way to end the turn.",
    parameters: Type.Object({
      outcome: Type.String({ enum: ["answered", "needs_clarification", "partial", "abstained"] }),
      language: Type.String({ enum: ["ZH_CN", "EN"] }),
      markdown: Type.Optional(Type.String({ maxLength: 16000 })),
      fields: Type.Optional(Type.Record(Type.String(), Type.String())),
      abstention_reason: Type.Optional(Type.String({
        enum: ["NO_MATCHING_PAPER", "NO_SUPPORTING_SOURCE", "OUT_OF_SCOPE"],
      })),
    }),
    executionMode: "sequential",
    async execute(_id, params, _signal, _onUpdate, _ctx) {
      state.cancelCheck();
      const payload: SubmitPayload = {
        outcome: params.outcome as AnswerOutcome,
        language: params.language,
        markdown: (params.markdown as string) ?? null,
        fields: (params.fields as Record<string, string>) ?? {},
        abstention_reason: (params.abstention_reason as string) ?? null,
      };
      const validation = validateSubmission(payload, state.observations);
      if (!accepted(validation)) {
        state.trace.push({
          tool: "submit_answer",
          args: {},
          ok: false,
          error: validation.issues[0]?.code,
        });
        const issuesPayload = validation.issues.map((issue) => ({
          code: issue.code,
          ...(issue.block_ids?.length ? { block_ids: issue.block_ids } : {}),
          ...(issue.unknown_source_quote_refs?.length
            ? { unknown_source_quote_refs: issue.unknown_source_quote_refs }
            : {}),
        }));
        return textResult(
          {
            error: "final_submission_rejected",
            issues: issuesPayload,
            instruction: "Fix every issue and call submit_answer again.",
          },
          maxVisibleChars,
        );
      }
      const rendered = renderAnswer(payload, state.observations);
      state.submittedAnswer = { payload, rendered };
      state.trace.push({ tool: "submit_answer", args: {}, ok: true });
      return {
        content: [{
          type: "text",
          text: "Submission accepted. The answer has been recorded. End the turn now: do not call any other tool and do not output any further text.",
        }],
        details: { submitted: true },
      };
    },
  };

  return [
    searchPaperCandidates,
    findPapersByIdentity,
    searchPaperContent,
    getPaperStructure,
    readPaperContent,
    submitAnswer,
  ];
}

function textResult(payload: Record<string, unknown>, maxChars: number) {
  let text = JSON.stringify(payload);
  if (text.length > maxChars) {
    text = `${text.slice(0, maxChars)}\n{"error":"result_truncated","next_action":"narrow the tool arguments"}`;
  }
  return { content: [{ type: "text", text }], details: {} };
}

function project(value: unknown): Record<string, unknown> {
  const visit = (node: unknown): unknown => {
    if (Array.isArray(node)) return node.map(visit);
    if (node && typeof node === "object") {
      const result: Record<string, unknown> = {};
      for (const [key, item] of Object.entries(node as Record<string, unknown>)) {
        if (!MODEL_REDACTED_FIELDS.has(key)) result[key] = visit(item);
      }
      return result;
    }
    return node;
  };
  return visit(value) as Record<string, unknown>;
}

function paperDisclosureError(
  args: Record<string, unknown>,
  state: RunState,
  resultKey: string,
): Record<string, unknown> | null {
  const values = args.paper_ids;
  if (!Array.isArray(values) || !values.length) return null;
  const ids = values.map(String);
  const unauthorized = ids.filter((id) => !state.authorizedPaperIds.has(id));
  if (!unauthorized.length) return null;
  return {
    error: "paper_not_authorized_for_reading",
    error_code: "PAPER_ID_NOT_DISCLOSED",
    recoverable: true,
    next_action: "find_papers_by_identity",
    unauthorized_paper_ids: unauthorized,
    [resultKey]: [],
  };
}

function locationDisclosureError(
  args: Record<string, unknown>,
  state: RunState,
  resultKey: string,
  key: string,
): Record<string, unknown> | null {
  const values = args[key];
  if (!Array.isArray(values) || !values.length) return null;
  const refs = values.map(String);
  const undisclosed = refs.filter((ref) => !state.disclosedLocationRefs.has(ref));
  if (!undisclosed.length) return null;
  return {
    error: "location_ref_not_disclosed",
    error_code: "LOCATION_NOT_DISCLOSED",
    recoverable: true,
    next_action: key === "location_refs" ? "read_paper_content" : "search_paper_content",
    location_refs: undisclosed,
    [resultKey]: [],
  };
}

function briefArgs(name: string, args: Record<string, unknown>): Record<string, unknown> {
  const interesting: Record<string, Record<string, unknown>> = {
    search_paper_candidates: { query_text: 1, limit: 1 },
    find_papers_by_identity: { paper_id: 1, title: 1 },
    search_paper_content: { query_text: 1, paper_ids: 1 },
    get_paper_structure: { paper_ids: 1, structure_type: 1 },
    read_paper_content: { location_refs: 1 },
    submit_answer: { outcome: 1 },
  };
  const keys = Object.keys(interesting[name] ?? {});
  return keys.length ? Object.fromEntries(keys.map((key) => [key, args[key]])) : {};
}

function asList(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}
