// Java Corpus API client. The only data plane: InternalCorpusController.

export class CorpusSystemError extends Error {
  readonly code: string;
  readonly safeMessage: string;
  constructor(code: string, safeMessage: string) {
    super(safeMessage);
    this.code = code;
    this.safeMessage = safeMessage;
  }
}

export class RecoverableCorpusError extends Error {
  readonly code: string;
  readonly safeMessage: string;
  constructor(code: string, safeMessage: string) {
    super(safeMessage);
    this.code = code;
    this.safeMessage = safeMessage;
  }
}

const RECOVERABLE_CODES = new Set(["READING_ARGUMENTS_INVALID", "PAPER_NOT_AVAILABLE"]);
const SYSTEM_CODES = new Set(["READING_MODEL_NOT_READY", "RETRIEVAL_UNAVAILABLE"]);

export type TurnContext = {
  request_id: string;
  conversation_id: string;
  user_id: number;
  scope_paper_ids: string[];
};

export class CorpusClient {
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly maxBytes: number;
  private readonly timeoutMs: number;
  private readonly signal: AbortSignal;

  constructor(
    baseUrl: string,
    token: string,
    maxBytes: number,
    timeoutMs: number,
    signal: AbortSignal,
  ) {
    this.baseUrl = baseUrl;
    this.token = token;
    this.maxBytes = maxBytes;
    this.timeoutMs = timeoutMs;
    this.signal = signal;
  }

  async post(path: string, payload: Record<string, unknown>): Promise<Record<string, unknown>> {
    if (this.signal.aborted) throw new Error("research job cancelled");
    const response = await fetch(this.baseUrl + path, {
      method: "POST",
      headers: {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "Authorization": `Bearer ${this.token}`,
      },
      body: JSON.stringify({ ...payload }),
      signal: AbortSignal.any([this.signal, AbortSignal.timeout(this.timeoutMs)]),
    });
    const contentLength = Number(response.headers.get("content-length") || 0);
    if (contentLength > this.maxBytes) {
      throw new CorpusSystemError("RETRIEVAL_UNAVAILABLE", "Corpus response exceeded the size limit");
    }
    let body: Record<string, unknown>;
    try {
      body = (await response.json()) as Record<string, unknown>;
    } catch {
      throw new CorpusSystemError("RETRIEVAL_UNAVAILABLE", "Corpus returned an unreadable response");
    }
    if (response.status === 401 || response.status === 403) {
      throw new CorpusSystemError("RETRIEVAL_UNAUTHORIZED", "Corpus rejected the internal token");
    }
    if (!response.ok) {
      const errorCode = String(body.error_code || "RETRIEVAL_UNAVAILABLE");
      const safeMessage = String(body.message || body.safe_message || "Corpus request failed");
      if (RECOVERABLE_CODES.has(errorCode)) throw new RecoverableCorpusError(errorCode, safeMessage);
      throw new CorpusSystemError(SYSTEM_CODES.has(errorCode) ? errorCode : "RETRIEVAL_UNAVAILABLE", safeMessage);
    }
    if (contentLength > 0 && contentLength > this.maxBytes) {
      throw new CorpusSystemError("RETRIEVAL_UNAVAILABLE", "Corpus response exceeded the size limit");
    }
    return body;
  }

  // Tool transport methods; context fields are injected so the model can never widen scope.
  searchPapers(ctx: TurnContext, args: Record<string, unknown>) {
    return this.post("/internal/v1/corpus/papers/search", { ...ctx, ...pick(args, [
      "query_text", "paper_ids", "authors", "venues", "year_from", "year_to", "offset", "limit",
    ]) });
  }

  findPapersByIdentity(ctx: TurnContext, args: Record<string, unknown>) {
    return this.post("/internal/v1/corpus/papers/search", {
      ...ctx,
      identity: pick(args, ["paper_id", "title", "filename", "doi", "arxiv_id", "authors", "year"]),
    });
  }

  searchLocations(ctx: TurnContext, args: Record<string, unknown>) {
    return this.post("/internal/v1/corpus/locations/search", { ...ctx, ...pick(args, [
      "paper_ids", "query_text", "section_query", "element_types", "page_from", "page_to", "top_k", "section_refs",
    ]) });
  }

  readLocations(ctx: TurnContext, args: Record<string, unknown>) {
    return this.post("/internal/v1/corpus/locations/read", { ...ctx, ...pick(args, ["location_refs"]) });
  }

  getStructure(ctx: TurnContext, args: Record<string, unknown>) {
    return this.post("/internal/v1/corpus/locations/structure", { ...ctx, ...pick(args, [
      "paper_ids", "structure_type", "section_query", "page_from", "page_to",
    ]) });
  }
}

function pick(source: Record<string, unknown>, keys: string[]): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const key of keys) {
    if (source[key] !== undefined) result[key] = source[key];
  }
  return result;
}
