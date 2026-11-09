// Real-model wire probe: two-step tool loop via pi against MiniMax, ~1-2k tokens.

import {
  createAgentSession,
  DefaultResourceLoader,
  ModelRuntime,
  SessionManager,
  SettingsManager,
  type ToolDefinition,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { loadConfig } from "./config.ts";
import { systemPrompt, REMINDER_MESSAGE } from "./prompt.ts";

async function main(): Promise<void> {
  const config = loadConfig();
  const runtime = await ModelRuntime.create();
  await runtime.setRuntimeApiKey(config.providerId, config.apiKey);
  const model = runtime.getModel(config.providerId, config.modelId);
  if (!model) throw new Error(`${config.providerId}/${config.modelId} unavailable in the pi catalog`);

  let submitted: string | null = null;
  const echo: ToolDefinition = {
    name: "echo_probe",
    label: "Echo probe",
    description: "Echoes the input back. Use it once before submitting.",
    parameters: Type.Object({ input: Type.String() }),
    executionMode: "sequential",
    async execute(_id, params) {
      return { content: [{ type: "text", text: `echo: ${params.input}` }], details: {} };
    },
  };
  const submitProbe: ToolDefinition = {
    name: "submit_probe",
    label: "Submit probe",
    description: "Submit the final marker. The only way to end the turn.",
    parameters: Type.Object({ marker: Type.String() }),
    executionMode: "sequential",
    async execute(_id, params) {
      submitted = params.marker;
      return {
        content: [{ type: "text", text: "Submission accepted. End the turn now without further output." }],
        details: {},
      };
    },
  };

  const resourceLoader = new DefaultResourceLoader({
    cwd: process.cwd(),
    agentDir: new URL("../.pi-agent/", import.meta.url).pathname,
    systemPromptOverride: () =>
      systemPrompt() +
      "\n\nPROBE ADDENDUM: In this probe, use echo_probe (with the exact text 'hello') exactly once, then call submit_probe with marker 'done'. Do not output plain text; the only ending action is submit_probe.",
    appendSystemPromptOverride: () => [],
  });
  await resourceLoader.reload();

  const { session } = await createAgentSession({
    model,
    thinkingLevel: config.thinkingLevel as "low" | "medium" | "high" | "off",
    resourceLoader,
    sessionManager: SessionManager.inMemory(),
    settingsManager: SettingsManager.inMemory(),
    tools: ["echo_probe", "submit_probe"],
    customTools: [echo, submitProbe],
  });

  const startedAt = Date.now();
  let prompts = 1;
  await session.prompt(
    "Follow the probe protocol: call echo_probe with input 'hello', then submit_probe with marker 'done'.",
  );
  let stopReasons: string[] = [];
  while (!submitted && prompts <= config.reminderMax + 1) {
    const lastAssistant = [...session.state.messages].reverse().find((m) => m.role === "assistant");
    stopReasons.push(String((lastAssistant as { stopReason?: string } | undefined)?.stopReason ?? "?"));
    prompts += 1;
    await session.prompt(REMINDER_MESSAGE);
  }

  const toolNames = session.state.messages
    .flatMap((m) =>
      m.role === "assistant"
        ? ((m as unknown as { content?: { type: string; name?: string }[] }).content ?? [])
            .filter((c) => c.type === "toolCall")
            .map((c) => c.name)
        : [],
    );

  const usage = { input: 0, output: 0, calls: 0 };
  for (const message of session.state.messages) {
    if (message.role !== "assistant") continue;
    usage.calls += 1;
    usage.input += (message as { usage?: { input: number; output: number } }).usage?.input ?? 0;
    usage.output += (message as { usage?: { input: number; output: number } }).usage?.output ?? 0;
  }

  session.dispose();
  console.log(
    JSON.stringify(
      {
        provider: config.providerId,
        model: config.modelId,
        api: "anthropic-messages (pi catalog)",
        submittedMarker: submitted,
        prompts,
        naturalSubmit: prompts === 1,
        toolCalls: toolNames,
        stopReasons,
        modelCalls: usage.calls,
        promptTokens: usage.input,
        completionTokens: usage.output,
        elapsedMs: Date.now() - startedAt,
      },
      null,
      2,
    ),
  );
}

void main();
