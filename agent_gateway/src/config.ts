// Runtime configuration for the agent gateway.
// Reuses the same env var names as harness_py so the deployment story matches.

export type RunLimits = {
  max_wall_clock_ms: number;
  max_model_visible_tool_chars: number;
  max_history_chars: number;
};

export const RUN_LIMITS_SCHEMA = "paperloom-run-limits/v1";
export const DEFAULT_RUN_LIMITS: RunLimits = {
  max_wall_clock_ms: 600_000,
  max_model_visible_tool_chars: 16_000,
  max_history_chars: 32_000,
};

export type GatewayConfig = {
  port: number;
  authToken: string;
  corpusBaseUrl: string;
  corpusMaxBytes: number;
  corpusTimeoutMs: number;
  apiKey: string;
  modelId: string;
  providerId: string;
  thinkingLevel: string;
  reminderMax: number;
  drainSeconds: number;
};

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name]?.trim();
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be a positive number`);
  }
  return Math.round(value);
}

export function loadConfig(): GatewayConfig {
  const authToken = process.env.RESEARCH_HARNESS_INTERNAL_TOKEN?.trim() ?? "";
  const apiKey =
    process.env.MINIMAX_CN_API_KEY?.trim() || process.env.MINIMAX_API_KEY?.trim() || "";
  if (!authToken) throw new Error("RESEARCH_HARNESS_INTERNAL_TOKEN is required");
  if (!apiKey) throw new Error("MINIMAX_API_KEY (or MINIMAX_CN_API_KEY) is required");
  return {
    port: intEnv("AGENT_GATEWAY_PORT", 8092),
    authToken,
    corpusBaseUrl:
      process.env.JAVA_CORPUS_BASE_URL?.trim() || "http://127.0.0.1:8081",
    corpusMaxBytes: intEnv("JAVA_CORPUS_MAX_RESPONSE_BYTES", 4_000_000),
    corpusTimeoutMs: intEnv("JAVA_CORPUS_TIMEOUT_MS", 30_000),
    apiKey,
    modelId: process.env.MINIMAX_MODEL?.trim() || "MiniMax-M3",
    providerId: "minimax-cn",
    thinkingLevel: process.env.AGENT_GATEWAY_THINKING?.trim() || "medium",
    reminderMax: intEnv("AGENT_GATEWAY_REMINDER_MAX", 1),
    drainSeconds: Number(process.env.RESEARCH_HARNESS_DRAIN_SECONDS || 30),
  };
}

// Mirrors harness_py _run_limits: strict schema, reject unknown/missing fields.
export function parseRunLimits(options: unknown): RunLimits {
  const raw = (options as Record<string, unknown> | undefined)?.run_limits;
  if (!raw || typeof raw !== "object") return { ...DEFAULT_RUN_LIMITS };
  const record = raw as Record<string, unknown>;
  if (record.schema_version !== RUN_LIMITS_SCHEMA) {
    throw new Error(`options.run_limits must use ${RUN_LIMITS_SCHEMA}`);
  }
  const names = Object.keys(DEFAULT_RUN_LIMITS) as (keyof RunLimits)[];
  const unknownFields = Object.keys(record).filter(
    (key) => key !== "schema_version" && !names.includes(key as keyof RunLimits),
  );
  const missing = names.filter((name) => record[name] === undefined);
  if (missing.length || unknownFields.length) {
    throw new Error(
      "options.run_limits fields are invalid: " +
        [...missing.map((name) => `missing ${name}`), ...unknownFields.map((name) => `unknown ${name}`)].join(", "),
    );
  }
  const limits = {} as RunLimits;
  for (const name of names) {
    const value = Number(record[name]);
    if (!Number.isInteger(value) || value <= 0) {
      throw new Error("options.run_limits values must be positive integers");
    }
    limits[name] = value;
  }
  return limits;
}
