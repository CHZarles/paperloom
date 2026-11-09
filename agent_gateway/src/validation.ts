// Final-answer gate: citation rules ported from harness_py research_contract.py
// (single submit_answer merges the direct/research validation rules).

export const CITATION_RE = /\[\[(source_quote_[A-Za-z0-9_-]+)\]\]/g;
const NUMERIC_CITATION_RE = /(?<!\[)\[(\d+)\]/;
const DOUBLE_BRACKET_MARKER_RE = /\[\[([^\]]+?)\]\]/;
const THINK_TAG_RE = /<\/?think(?:\s[^>]*)?>/i;

export const ANSWER_OUTCOMES = ["answered", "needs_clarification", "partial", "abstained"] as const;
export type AnswerOutcome = (typeof ANSWER_OUTCOMES)[number];
export const LANGUAGES = ["ZH_CN", "EN"] as const;
export const ABSTENTION_REASONS = ["NO_MATCHING_PAPER", "NO_SUPPORTING_SOURCE", "OUT_OF_SCOPE"] as const;

export const ABSTENTION_MESSAGES: Record<string, Record<(typeof ABSTENTION_REASONS)[number], string>> = {
  ZH_CN: {
    NO_MATCHING_PAPER: "当前论文库中没有找到与问题匹配的论文。",
    NO_SUPPORTING_SOURCE: "当前论文库中没有找到足以支持回答的原文证据。",
    OUT_OF_SCOPE: "这个问题超出了当前论文研究范围。",
  },
  EN: {
    NO_MATCHING_PAPER: "No papers matching the question were found in the current corpus.",
    NO_SUPPORTING_SOURCE: "No source evidence sufficient to answer was found in the current corpus.",
    OUT_OF_SCOPE: "This question is outside the current paper-research scope.",
  },
};

export type Issue = {
  code: string;
  block_ids?: string[];
  unknown_source_quote_refs?: string[];
};

export type EvidenceRecord = Record<string, unknown> & { source_quote_ref: string };

export type SubmitPayload = {
  outcome: AnswerOutcome;
  language: string;
  markdown: string | null;
  fields: Record<string, string>;
  abstention_reason: string | null;
};

const NON_MATERIAL_BLOCK_KINDS = new Set(["heading", "table_header", "thematic_break"]);
const LIST_ITEM_RE = /^\s*(?:[-+*]|\d+[.)])\s+/;
const TABLE_ROW_RE = /^\s*\|/;
const TABLE_SEPARATOR_RE = /^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*$/;
const HEADING_RE = /^#{1,6}\s/;
const THEMATIC_BREAK_RE = /^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$/;

type Block = { block_id: string; kind: string; text: string; evidence_ids: string[] };

// Ported from harness_py/utils/answer_blocks.py (minimal block segmentation).
function markdownBlocks(markdown: string): { kind: string; text: string }[] {
  const rawBlocks = markdown.split(/\n\s*\n/).flatMap((paragraph) => splitByLines(paragraph));
  return rawBlocks.filter((block) => block.text.trim());
}

function splitByLines(paragraph: string): { kind: string; text: string }[] {
  const lines = paragraph.split("\n");
  const split: { kind: string; text: string; inTable: boolean }[] = [];
  for (const line of lines) {
    const kind = blockKind(line);
    const inTable = kind === "table_row" || kind === "table_header";
    const last = split[split.length - 1];
    if (last && last.inTable === inTable && (last.inTable || kind === "paragraph" || kind === "list_item")) {
      if (inTable) {
        last.text += "\n" + line;
      } else {
        last.text += "\n" + line;
      }
      continue;
    }
    split.push({ kind, text: line, inTable });
  }
  return split.map(({ kind, text }) => ({ kind, text }));
}

function blockKind(line: string): string {
  if (HEADING_RE.test(line)) return "heading";
  if (TABLE_SEPARATOR_RE.test(line)) return "table_header";
  if (TABLE_ROW_RE.test(line)) return "table_row";
  if (LIST_ITEM_RE.test(line)) return "list_item";
  if (THEMATIC_BREAK_RE.test(line)) return "thematic_break";
  return "paragraph";
}

function answerBlocks(markdown: string): Block[] {
  const blocks: Block[] = [];
  markdownBlocks(markdown).forEach((rawBlock, index) => {
    const evidenceIds = [...CITATION_RE_GLOBAL.findAll(rawBlock.text)].map((match) => match);
    const text = rawBlock.text
      .replace(/\[\[(?:source_quote|ev)_[A-Za-z0-9_-]+\]\]/g, "")
      .replace(/\s+/g, " ")
      .trim()
      .replace(/^[|\t]+|[|\t]+$/g, "");
    if (!text && !evidenceIds.length) return;
    blocks.push({ block_id: `block_${index + 1}`, kind: rawBlock.kind, text, evidence_ids: evidenceIds });
  });
  return blocks;
}

const CITATION_RE_GLOBAL = {
  findAll(text: string): string[] {
    const re = /\[\[(source_quote_[A-Za-z0-9_-]+)\]\]/g;
    const ids: string[] = [];
    let match: RegExpExecArray | null;
    while ((match = re.exec(text))) ids.push(match[1]);
    return ids;
  },
};

export type Validation = { issues: Issue[] };
export const accepted = (validation: Validation): boolean => validation.issues.length === 0;

export function validateSubmission(
  payload: SubmitPayload,
  knownQuotes: Map<string, EvidenceRecord>,
): Validation {
  const issues: Issue[] = [];
  if (!isLanguage(payload.language)) {
    issues.push({ code: "LANGUAGE_INVALID" });
  }
  if (!ANSWER_OUTCOMES.includes(payload.outcome)) {
    issues.push({ code: "OUTCOME_INVALID" });
    return { issues };
  }
  if (payload.fields && !isStringMap(payload.fields)) {
    issues.push({ code: "FIELDS_INVALID" });
  }
  if (payload.outcome === "needs_clarification") {
    const markdown = payload.markdown ?? "";
    if (!markdown.trim() || markdown.length > 4000 || DOUBLE_BRACKET_MARKER_RE.test(markdown)) {
      issues.push({ code: "MARKDOWN_INVALID" });
    }
    if (payload.abstention_reason) issues.push({ code: "ABSTENTION_REASON_NOT_ALLOWED" });
    return { issues };
  }
  if (payload.outcome === "abstained") {
    if ((payload.markdown ?? "").trim()) {
      issues.push({ code: "ABSTENTION_MARKDOWN_NOT_ALLOWED" });
    }
    if (!ABSTENTION_REASONS.includes(payload.abstention_reason as never)) {
      issues.push({ code: "ABSTENTION_REASON_INVALID" });
    }
    return { issues };
  }
  // answered / partial
  if (payload.abstention_reason) {
    issues.push({ code: "ABSTENTION_REASON_NOT_ALLOWED" });
  }
  const markdown = payload.markdown ?? "";
  if (!markdown.trim()) {
    issues.push({ code: "MARKDOWN_REQUIRED" });
    return { issues };
  }
  if (THINK_TAG_RE.test(markdown)) {
    issues.push({ code: "INTERNAL_REASONING_NOT_ALLOWED" });
  }
  if (NUMERIC_CITATION_RE.test(markdown)) {
    issues.push({ code: "NUMERIC_CITATION_NOT_ALLOWED" });
  }
  const invalidMarkers = [
    ...new Set(
      [...markdown.matchAll(/\[\[([^\]]+?)\]\]/g)]
        .map((match) => match[1].trim())
        .filter((marker) => !/^source_quote_[A-Za-z0-9_-]+$/.test(marker)),
    ),
  ];
  if (invalidMarkers.length) {
    issues.push({ code: "INVALID_SOURCE_MARKER" });
  }
  const cited = [...markdown.matchAll(/\[\[(source_quote_[A-Za-z0-9_-]+)\]\]/g)].map((m) => m[1]);
  const unknown = [...new Set(cited.filter((ref) => !knownQuotes.has(ref) && !invalidMarkers.includes(ref)))];
  if (unknown.length) {
    issues.push({ code: "UNKNOWN_SOURCE_REF", unknown_source_quote_refs: unknown.sort() });
  }
  const blocks = answerBlocks(markdown);
  const contentBlocks = blocks.filter(
    (block) => !NON_MATERIAL_BLOCK_KINDS.has(block.kind) && block.text.trim(),
  );
  if (!contentBlocks.length) {
    issues.push({ code: "CONTENT_REQUIRED" });
  }
  const markerInvalid = invalidMarkers.length > 0 || NUMERIC_CITATION_RE.test(markdown);
  if (!markerInvalid) {
    const uncited = contentBlocks.filter((block) => !block.evidence_ids.length).map((block) => block.block_id);
    if (uncited.length) {
      issues.push({ code: "UNCITED_CONTENT_BLOCK", block_ids: uncited });
    }
  }
  return { issues };
}

// Renders the accepted draft: markdown [N] + ordered citations array, mirroring run_output.py.
export function renderAnswer(
  payload: SubmitPayload,
  knownQuotes: Map<string, EvidenceRecord>,
): { markdown: string; citations: Record<string, unknown>[]; cited_refs: string[] } {
  const rawMarkdown =
    payload.outcome === "abstained"
      ? ABSTENTION_MESSAGES[payload.language as "ZH_CN" | "EN"][payload.abstention_reason as never]
      : (payload.markdown ?? "");
  const citedRefs = [...new Set([...rawMarkdown.matchAll(/\[\[(source_quote_[A-Za-z0-9_-]+)\]\]/g)].map((m) => m[1]))];
  const numbers = new Map(citedRefs.map((ref, index) => [ref, index + 1]));
  const markdown = rawMarkdown.replace(/\[\[(source_quote_[A-Za-z0-9_-]+)\]\]/g, (_, ref) => `[${numbers.get(ref)}]`);
  const citations = citedRefs
    .filter((ref) => knownQuotes.has(ref))
    .map((ref, index) => {
      const record = knownQuotes.get(ref)!;
      return { ...record, evidence_id: ref, reference_number: index + 1 };
    });
  return { markdown, citations, cited_refs: citedRefs };
}

export function issueFeedback(issues: Issue[]): string {
  const lines = issues.map((issue) => {
    const extras = [
      ...(issue.block_ids?.length ? [`block_ids=${issue.block_ids.join(",")}`] : []),
      ...(issue.unknown_source_quote_refs?.length
        ? [`unknown_source_quote_refs=${issue.unknown_source_quote_refs.join(",")}`]
        : []),
    ];
    return `- ${issue.code}${extras.length ? ` (${extras.join("; ")})` : ""}`;
  });
  return `FINAL_SUBMISSION_REJECTED. Fix every issue and call submit_answer again:\n${lines.join("\n")}`;
}

function isLanguage(value: string): value is (typeof LANGUAGES)[number] {
  return LANGUAGES.includes(value as never);
}

function isStringMap(value: Record<string, string>): boolean {
  return Object.entries(value).every(([key, entry]) => typeof key === "string" && typeof entry === "string");
}
