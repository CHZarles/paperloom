// One research turn: session assembly, guardrails, and result projection.

import {
  createAgentSession,
  DefaultResourceLoader,
  ModelRuntime,
  SessionManager,
  SettingsManager,
  type ToolDefinition,
} from "@earendil-works/pi-coding-agent";
import type { GatewayConfig, RunLimits } from "./config.ts";
import { CorpusClient, CorpusSystemError } from "./corpus.ts";
import { REMINDER_MESSAGE, systemPrompt, userTurnMessage, type TurnSeed } from "./prompt.ts";
import { buildTools, newRunState, type RunState } from "./tools.ts";

export type ResearchRequest = {
  request_id: string;
  conversation_id: string;
  user_id: number;
  user_message: string;
  scope: { paper_ids: string[] };
  history: { role: "user" | "assistant"; content: string }[];
  research_memory: {
    selected_paper_ids: string[];
    selected_source_quote_refs: string[];
    previous_evidence: Record<string, Record<string, unknown>>;
  };
  limits: RunLimits;
  includeTrace: boolean;
};

export type TurnResult = Record<string, unknown>;

let modelRuntimePromise: Promise<ModelRuntime> | null = null;

export function modelRuntime(apiKey: string): Promise<ModelRuntime> {
  if (!modelRuntimePromise) {
    modelRuntimePromise = ModelRuntime.create().then(async (runtime) => {
      await runtime.setRuntimeApiKey("minimax-cn", apiKey);
      return runtime;
    });
  }
  return modelRuntimePromise;
}

export function parseRequest(raw: Record<string, unknown>, limits: RunLimits): ResearchRequest {
  const userMessage = String(raw.user_message ?? "").trim();
  const conversationId = String(raw.conversation_id ?? "").trim();
  const requestId = String(raw.request_id ?? "").trim();
  const scope = (raw.scope ?? {}) as Record<string, unknown>;
  const paperIds = Array.isArray(scope.paper_ids) ? scope.paper_ids.map(String).filter(Boolean) : [];
  const uniquePaperIds = [...new Set(paperIds)];
  if (!userMessage) throw new Error("user_message is required");
  if (!conversationId) throw new Error("conversation_id is required");
  if (!uniquePaperIds.length) throw new Error("scope.paper_ids must contain the papers authorized by Java");
  const rawUserId = raw.user_id;
  if (rawUserId === undefined || rawUserId === null || rawUserId === "") {
    throw new Error("user_id is required for the Java Corpus API");
  }
  const history = (Array.isArray(raw.history) ? raw.history : [])
    .filter(
      (item): item is { role: "user" | "assistant"; content: string } =>
        typeof item === "object" && item !== null &&
        ["user", "assistant"].includes(String((item as Record<string, unknown>).role)) &&
        String((item as Record<string, unknown>).content ?? "").trim() !== "",
    )
    .map((item) => ({
      role: String(item.role) === "user" ? ("user" as const) : ("assistant" as const),
      content: String(item.content ?? ""),
    }));
  const memory = (raw.research_memory ?? {}) as Record<string, unknown>;
  const previous: Record<string, Record<string, unknown>> = {};
  const previousEvidence = memory.previous_evidence;
  if (Array.isArray(previousEvidence)) {
    for (const item of previousEvidence) {
      const record = item as Record<string, unknown>;
      if (!record || typeof record !== "object") continue;
      const ref = String(record.source_quote_ref ?? record.evidence_id ?? "");
      if (ref) previous[ref] = record;
    }
  } else if (previousEvidence && typeof previousEvidence === "object") {
    for (const [ref, item] of Object.entries(previousEvidence as Record<string, unknown>)) {
      if (item && typeof item === "object") previous[ref] = item as Record<string, unknown>;
    }
  }
  const options = (raw.options ?? {}) as Record<string, unknown>;
  return {
    request_id: requestId,
    conversation_id: conversationId,
    user_id: Number(rawUserId),
    user_message: userMessage,
    scope: { paper_ids: uniquePaperIds },
    history,
    research_memory: {
      selected_paper_ids: [...new Set((Array.isArray(memory.selected_paper_ids) ? memory.selected_paper_ids : []).map(String))],
      selected_source_quote_refs: [
        ...new Set((Array.isArray(memory.selected_source_quote_refs) ? memory.selected_source_quote_refs : []).map(String)),
      ],
      previous_evidence: previous,
    },
    limits,
    includeTrace: (options.include_trace as boolean) ?? true,
  };
}

export class CancelledTurn extends Error {
  constructor() {
    super("research job cancelled");
  }
}

export class LimitedTurn extends Error {
  readonly reasonCode: string;
  constructor(reasonCode: string) {
    super(reasonCode);
    this.reasonCode = reasonCode;
  }
}

export type TurnOutcome =
  | { kind: "completed"; state: RunState; usage: TurnUsage; elapsedMs: number }
  | { kind: "failure"; status: "FAILED_TECHNICAL"; reasonCode: string; usage: TurnUsage; elapsedMs: number; state: RunState | null }
  | { kind: "limited"; reasonCode: string; usage: TurnUsage; elapsedMs: number; state: RunState | null }
  | { kind: "cancelled"; usage: TurnUsage; elapsedMs: number; state: RunState | null };

export type TurnUsage = { modelCalls: number; promptTokens: number; completionTokens: number };

export async function runResearchTurn(
  config: GatewayConfig,
  request: ResearchRequest,
  cancelSignal: AbortSignal,
): Promise<TurnOutcome> {
  const startedAt = Date.now();
  const deadline = startedAt + request.limits.max_wall_clock_ms;
  const corpus = new CorpusClient(
    config.corpusBaseUrl,
    config.authToken,
    config.corpusMaxBytes,
    config.corpusTimeoutMs,
    cancelSignal,
  );
  const cancelCheck = () => {
    if (cancelSignal.aborted) throw new CancelledTurn();
    if (Date.now() > deadline) throw new LimitedTurn("RUN_DEADLINE_EXCEEDED");
  };
  const ctx = {
    request_id: request.request_id,
    conversation_id: request.conversation_id,
    user_id: request.user_id,
    scope_paper_ids: request.scope.paper_ids,
  };
  const state = newRunState(ctx, cancelCheck);

  const runtime = await modelRuntime(config.apiKey);
  const model = runtime.getModel(config.providerId, config.modelId);
  if (!model) {
    return {
      kind: "failure",
      status: "FAILED_TECHNICAL",
      reasonCode: "PROVIDER_UNCONFIGURED",
      usage: zeroUsage(),
      elapsedMs: Date.now() - startedAt,
      state,
    };
  }

  const tools = buildTools(state, corpus, request.limits.max_model_visible_tool_chars);
  const resourceLoader = new DefaultResourceLoader({
    cwd: process.cwd(),
    agentDir: new URL("../.pi-agent/", import.meta.url).pathname,
    systemPromptOverride: () => systemPrompt(),
    appendSystemPromptOverride: () => [],
  });
  await resourceLoader.reload();
  const { session } = await createAgentSession({
    model,
    thinkingLevel: config.thinkingLevel as "low" | "medium" | "high" | "off",
    resourceLoader,
    sessionManager: SessionManager.inMemory(),
    settingsManager: SettingsManager.inMemory(),
    tools: tools.map((tool) => tool.name) as string[],
    customTools: tools as ToolDefinition[],
  });

  const wallClockTimer = setTimeout(() => {
    session.abort().catch(() => {});
  }, request.limits.max_wall_clock_ms);
  const onExternalCancel = () => {
    session.abort().catch(() => {});
  };
  cancelSignal.addEventListener("abort", onExternalCancel, { once: true });

  let reminders = 0;
  try {
    let pendingFirstPrompt = true;
    while (true) {
      if (cancelSignal.aborted) {
        return { kind: "cancelled", usage: usageOf(session), elapsedMs: Date.now() - startedAt, state };
      }
      if (Date.now() > deadline) {
        return {
          kind: "limited",
          reasonCode: "RUN_DEADLINE_EXCEEDED",
          usage: usageOf(session),
          elapsedMs: Date.now() - startedAt,
          state,
        };
      }
      const promptText = pendingFirstPrompt ? userTurnMessage(seedOf(request)) : REMINDER_MESSAGE;
      pendingFirstPrompt = false;
      try {
        await session.prompt(promptText);
      } catch (error) {
        if (cancelSignal.aborted) {
          return { kind: "cancelled", usage: usageOf(session), elapsedMs: Date.now() - startedAt, state };
        }
        if (Date.now() > deadline) {
          return {
            kind: "limited",
            reasonCode: "RUN_DEADLINE_EXCEEDED",
            usage: usageOf(session),
            elapsedMs: Date.now() - startedAt,
            state,
          };
        }
        if (error instanceof CorpusSystemError) {
          return {
            kind: "failure",
            status: "FAILED_TECHNICAL",
            reasonCode: error.code,
            usage: usageOf(session),
            elapsedMs: Date.now() - startedAt,
            state,
          };
        }
        throw error;
      }
      if (state.submittedAnswer) {
        return { kind: "completed", state, usage: usageOf(session), elapsedMs: Date.now() - startedAt };
      }
      const lastAssistant = [...session.state.messages].reverse().find((m) => m.role === "assistant");
      const stopReason = (lastAssistant as { stopReason?: string } | undefined)?.stopReason;
      if (stopReason === "error" || stopReason === "aborted") {
        if (cancelSignal.aborted) {
          return { kind: "cancelled", usage: usageOf(session), elapsedMs: Date.now() - startedAt, state };
        }
        return {
          kind: "failure",
          status: "FAILED_TECHNICAL",
          reasonCode: "PROVIDER_ERROR",
          usage: usageOf(session),
          elapsedMs: Date.now() - startedAt,
          state,
        };
      }
      if (reminders < config.reminderMax) {
        reminders += 1;
        continue;
      }
      return {
        kind: "failure",
        status: "FAILED_TECHNICAL",
        reasonCode: "PROVIDER_SUBMISSION_PROTOCOL_VIOLATION",
        usage: usageOf(session),
        elapsedMs: Date.now() - startedAt,
        state,
      };
    }
  } finally {
    clearTimeout(wallClockTimer);
    cancelSignal.removeEventListener("abort", onExternalCancel);
    session.dispose();
  }
}

export function buildResult(
  request: ResearchRequest,
  outcome: TurnOutcome,
): Record<string, unknown> {
  const usage = {
    model_calls: outcome.usage.modelCalls,
    prompt_tokens: outcome.usage.promptTokens,
    completion_tokens: outcome.usage.completionTokens,
    total_tokens: outcome.usage.promptTokens + outcome.usage.completionTokens,
    elapsed_ms: outcome.elapsedMs,
  };
  const control = { reason_code: outcome.kind === "failure" || outcome.kind === "limited" ? outcome.reasonCode : null, usage };
  const submitted = outcome.kind === "completed" ? outcome.state.submittedAnswer : null;
  let status: string;
  let answer: Record<string, unknown> | null = null;
  let citations: Record<string, unknown>[] = [];
  if (outcome.kind === "completed" && submitted) {
    const outcomeValue = submitted.payload.outcome;
    status = outcomeValue === "answered" ? "COMPLETED" : outcomeValue === "needs_clarification" ? "NEEDS_CLARIFICATION" : "INCOMPLETE_PRECISE";
    answer = {
      outcome: outcomeValue,
      answer_contract: outcomeValue === "needs_clarification" ? "DIRECT" : submitted.rendered.citations.length ? "RESEARCH" : "DIRECT",
      markdown: submitted.rendered.markdown,
      summary: submitted.rendered.markdown.slice(0, 400),
      fields: submitted.payload.fields,
      cited_source_quote_refs: submitted.rendered.cited_refs,
      language: submitted.payload.language,
    };
    citations = submitted.rendered.citations;
  } else if (outcome.kind === "limited") {
    status = "LIMITED";
  } else if (outcome.kind === "cancelled") {
    status = "CANCELLED";
  } else {
    status = "FAILED_TECHNICAL";
  }
  const selectedPaperIds = [
    ...new Set([
      ...(request.research_memory.selected_paper_ids ?? []),
      ...citations.map((item) => String(item.paper_id ?? "")).filter(Boolean),
    ]),
  ];
  const previousEvidenceValues = Object.entries(request.research_memory.previous_evidence);
  const response: Record<string, unknown> = {
    request_id: request.request_id,
    conversation_id: request.conversation_id,
    status,
    answer,
    citations,
    research_memory: {
      selected_paper_ids: selectedPaperIds,
      selected_evidence_ids: selectedPaperIds.length ? request.research_memory.selected_source_quote_refs : [],
      previous_evidence: previousEvidenceValues.map(([, item]) => item),
    },
    usage: {
      prompt_tokens: usage.prompt_tokens,
      completion_tokens: usage.completion_tokens,
      total_tokens: usage.total_tokens,
    },
    control,
  };
  if (request.includeTrace) {
    const state = outcome.kind === "completed" ? outcome.state : outcome.state ?? null;
    response.trace = {
      skills_used: [],
      tool_calls: state ? state.trace : [],
      evidence_ledger: state ? { items: [...state.observations.values()] } : { items: [] },
      finish_reason: outcome.kind,
    };
  }
  return response;
}

function seedOf(request: ResearchRequest): TurnSeed {
  return {
    history: request.history,
    userMessage: request.user_message,
    previousEvidence: request.research_memory.previous_evidence,
    selectedPaperIds: request.research_memory.selected_paper_ids,
    maxHistoryChars: request.limits.max_history_chars,
  };
}

function usageOf(session: { state: { messages: { role: string; usage?: { input: number; output: number } }[] } }): TurnUsage {
  let modelCalls = 0;
  let promptTokens = 0;
  let completionTokens = 0;
  for (const message of session.state.messages) {
    if (message.role !== "assistant") continue;
    modelCalls += 1;
    promptTokens += message.usage?.input ?? 0;
    completionTokens += message.usage?.output ?? 0;
  }
  return { modelCalls, promptTokens, completionTokens };
}

function zeroUsage(): TurnUsage {
  return { modelCalls: 0, promptTokens: 0, completionTokens: 0 };
}
