// HTTP shell: same contract as harness_py transport/service.py, on the gateway's own port.

import http from "node:http";
import { loadConfig, parseRunLimits, DEFAULT_RUN_LIMITS, type GatewayConfig } from "./config.ts";
import {
  buildResult,
  runResearchTurn,
  parseRequest,
  CancelledTurn,
  LimitedTurn,
} from "./run.ts";
import { CorpusSystemError } from "./corpus.ts";

type InFlight = { requestId: string; conversationId: string; userId: number; startedAt: number };
const inFlight = new Map<string, InFlight>();
let shuttingDown = false;

function main(): void {
  const config = loadConfig();
  const server = http.createServer((req, res) => {
    void handle(config, req, res);
  });
  server.listen(config.port, "127.0.0.1");
  console.log(`agent-gateway listening on http://127.0.0.1:${config.port}`);

  let draining: Promise<void> | null = null;
  const drain = () => {
    if (shuttingDown) return;
    shuttingDown = true;
    const deadline = Date.now() + config.drainSeconds * 1000;
    draining = new Promise<void>((resolve) => {
      server.close();
      const wait = () => {
        if (!inFlight.size || Date.now() > deadline) {
          if (inFlight.size) {
            console.warn(`drain timed out with ${inFlight.size} in-flight turn(s)`);
          }
          resolve();
          return;
        }
        setTimeout(wait, 50);
      };
      wait();
    });
  };
  process.on("SIGTERM", () => {
    drain();
    void draining?.then(() => process.exit(0));
  });
  process.on("SIGINT", () => {
    drain();
    void draining?.then(() => process.exit(0));
  });
}

async function handle(config: GatewayConfig, req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
  const url = req.url ?? "";
  if (req.method === "GET" && (url === "/health" || url === "/ready")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "ok",
      harness: "pi_agent",
      provider: { provider: config.providerId, model: config.modelId, has_api_key: !!config.apiKey },
      in_flight: inFlight.size,
      shutting_down: shuttingDown,
    }));
    return;
  }
  if (req.method === "POST" && (url === "/v1/research/stream" || url === "/v1/research/turn")) {
    if (shuttingDown) return json(res, 503, { error: "draining" });
    if (!authorized(config, req)) return json(res, 401, { error: "unauthorized" });
    let raw: Record<string, unknown>;
    try {
      raw = (await readBody(req)) as Record<string, unknown>;
      if (!raw || typeof raw !== "object") throw new Error("request body must be a JSON object");
    } catch (error) {
      return json(res, 400, { error: "invalid_request", message: String((error as Error).message) });
    }
    let limits = { ...DEFAULT_RUN_LIMITS };
    try {
      const options = (raw.options ?? {}) as Record<string, unknown>;
      limits = parseRunLimits(options);
    } catch (error) {
      return json(res, 400, { error: "invalid_request", message: String((error as Error).message) });
    }
    let request: ReturnType<typeof parseRequest>;
    try {
      request = parseRequest(raw, limits);
    } catch (error) {
      return json(res, 400, { error: "invalid_request", message: String((error as Error).message) });
    }
    if (url === "/v1/research/turn") {
      const entry = register(request);
      const controller = new AbortController();
      try {
        const outcome = await runResearchTurn(config, request, controller.signal);
        return json(res, 200, buildResult(request, outcome));
      } catch (error) {
        return json(res, 500, technicalFailure(error, entry.startedAt));
      } finally {
        unregister(request.request_id);
      }
    }
    return streamTurn(config, req, res, request);
  }
  json(res, 404, { error: "not_found" });
}

async function streamTurn(
  config: GatewayConfig,
  req: http.IncomingMessage,
  res: http.ServerResponse,
  request: ReturnType<typeof parseRequest>,
): Promise<void> {
  const controller = new AbortController();
  res.writeHead(200, {
    "Content-Type": "application/x-ndjson",
    "Cache-Control": "no-cache",
  });
  let sequence = 0;
  const emit = (event: Record<string, unknown>) => {
    if (res.writableEnded || res.destroyed) return; // fired only on disconnect; terminal state is internal
    sequence += 1;
    try {
      res.write(
        JSON.stringify({
          generationId: request.request_id,
          sequence,
          timestamp: Date.now(),
          ...event,
        }) + "\n",
      );
    } catch {
      // The client is gone; the turn keeps running so in-flight accounting and cancel stay correct.
    }
  };
  res.on("close", () => {
    // res-close fires both at normal end (writableEnded) and on client disconnect.
    if (!res.writableEnded) controller.abort();
  });
  emit({ type: "job_started" });
  register(request);
  try {
    const outcome = await runResearchTurn(config, request, controller.signal);
    const response = buildResult(request, outcome);
    const status = String(response.status);
    const terminal = terminalEvent(response);
    if (status === "LIMITED") {
      emit({ type: "run_limited", ...terminal });
      emit({ type: "job_completed", ...terminal });
    } else if (status === "CANCELLED") {
      emit({ type: "job_cancelled", ...terminal });
    } else if (status === "FAILED_TECHNICAL") {
      emit({ type: "job_failed", ...terminal });
    } else {
      emit({ type: "answer_completed" });
      emit({ type: "job_completed", ...terminal });
    }
    emit({ type: "result", payload: response });
  } catch (error) {
    emit(technicalFailure(error, Date.now()));
    emit({ type: "error", errorType: (error as Error).name ?? "Error", message: String((error as Error).message) });
  } finally {
    unregister(request.request_id);
    res.end();
  }
}

function terminalEvent(response: Record<string, unknown>): Record<string, unknown> {
  const control = (response.control ?? {}) as Record<string, unknown>;
  const usage = (control.usage ?? {}) as Record<string, unknown>;
  return {
    status: response.status,
    reasonCode: (control.reason_code as string | null) ?? null,
    usage,
    elapsedMs: usage.elapsed_ms,
  };
}

function technicalFailure(error: unknown, startedAt: number): Record<string, unknown> {
  const elapsedMs = Math.max(0, Date.now() - startedAt);
  const reasonCode =
    error instanceof CorpusSystemError
      ? error.code
      : error instanceof LimitedTurn
        ? error.reasonCode
        : error instanceof CancelledTurn
          ? "CANCELLED"
          : "INTERNAL_UNEXPECTED";
  return {
    type: "job_failed",
    status: "FAILED_TECHNICAL",
    reasonCode,
    usage: { model_calls: 0, prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, elapsed_ms: elapsedMs },
    elapsedMs,
    errorType: (error as Error).name ?? "Error",
    message: "The research service stopped unexpectedly.",
  };
}

function register(request: ReturnType<typeof parseRequest>): InFlight {
  const entry = {
    requestId: request.request_id,
    conversationId: request.conversation_id,
    userId: request.user_id,
    startedAt: Date.now(),
  };
  if (request.request_id) inFlight.set(request.request_id, entry);
  return entry;
}

function unregister(requestId: string) {
  if (requestId) inFlight.delete(requestId);
}

function authorized(config: GatewayConfig, req: http.IncomingMessage): boolean {
  const header = req.headers.authorization ?? "";
  return header.trim() === `Bearer ${config.authToken}`;
}

function json(res: http.ServerResponse, status: number, body: Record<string, unknown>) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function readBody(req: http.IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", () => {
      try {
        const body = Buffer.concat(chunks).toString("utf8").trim();
        resolve(body ? JSON.parse(body) : null);
      } catch (error) {
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
    req.on("error", reject);
  });
}

main();
