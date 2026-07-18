package io.github.chzarles.paperloom.eval;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProductLaunchRuntimePreflightRunner {

    private static final String ROUTE = "PRODUCT_LAUNCH_RUNTIME_PREFLIGHT";

    private final RuntimeProbe probe;

    public ProductLaunchRuntimePreflightRunner(RuntimeProbe probe) {
        this.probe = probe;
    }

    public Path run(Options options) throws IOException {
        if (probe == null) {
            throw new IllegalStateException("RuntimeProbe is required");
        }
        Options safeOptions = options == null ? Options.defaults() : options;
        Map<String, String> env = loadEnv(safeOptions.envPath());
        List<ProbeRequest> requests = requests(env, safeOptions);
        List<CaseResult> results = new ArrayList<>();
        for (ProbeRequest request : requests) {
            results.add(runRequest(request));
        }
        Path runDir = RagEvalRunWriter.write(
                safeOptions.runsRoot(),
                safeOptions.runId(),
                safeOptions.startedAt(),
                safeOptions.harnessId(),
                safeOptions.datasetId(),
                safeOptions.envPath() == null ? "" : safeOptions.envPath().toString(),
                new RagBenchmarkRun(
                        results.stream().map(this::benchmarkCase).toList(),
                        results.stream().map(this::actual).toList(),
                        results.stream().map(this::verdict).toList()
                ),
                metrics(results)
        );
        Files.writeString(runDir.resolve("remediation.md"), remediationMarkdown(safeOptions, results));
        return runDir;
    }

    static Map<String, String> loadEnv(Path envPath) throws IOException {
        if (envPath == null || !Files.isRegularFile(envPath)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(envPath)) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = stripQuotes(line.substring(equals + 1).trim());
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private List<ProbeRequest> requests(Map<String, String> env, Options options) {
        List<ProbeRequest> requests = new ArrayList<>();
        requests.add(ProbeRequest.login(options.apiBase(), options.username(), options.password()));
        requests.add(ProbeRequest.frontendHttp(value(env, options, "PAPERLOOM_FRONTEND_BASE_URL",
                "http://127.0.0.1:9527")));
        requests.add(tcpFromJdbc("mysql_tcp", value(env, options, "SPRING_DATASOURCE_URL",
                "jdbc:mysql://localhost:3306/paperloom")));
        requests.add(ProbeRequest.tcp(
                "redis_tcp",
                value(env, options, "SPRING_DATA_REDIS_HOST", "localhost"),
                intValue(value(env, options, "SPRING_DATA_REDIS_PORT", "6379"), 6379)
        ));
        requests.add(tcpFromHostPort(
                "kafka_tcp",
                firstHostPort(value(env, options, "SPRING_KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092")),
                "127.0.0.1",
                9092
        ));
        String minioEndpoint = value(env, options, "MINIO_ENDPOINT", "http://localhost:9000");
        requests.add(ProbeRequest.http(
                "minio_health",
                trimTrailingSlash(minioEndpoint) + "/minio/health/live",
                List.of(200)
        ));
        requests.add(ProbeRequest.http(
                "qdrant_health",
                qdrantUrl(env, options),
                List.of(200)
        ));
        String mineruBase = value(env, options, "PAPER_PARSING_MINERU_BASE_URL", "http://localhost:8000");
        String mineruHealth = value(env, options, "PAPER_PARSING_MINERU_HEALTH_PATH", "/health");
        requests.add(ProbeRequest.http("mineru_health", trimTrailingSlash(mineruBase) + ensureLeadingSlash(mineruHealth), List.of(200)));
        requests.add(ProbeRequest.traceConfig(
                value(env, options, "PAPERLOOM_TRACE_ENABLED", "true"),
                value(env, options, "PAPERLOOM_TRACE_ROOT", "data/traces/product-react")
        ));
        return requests;
    }

    private ProbeRequest tcpFromJdbc(String caseId, String jdbcUrl) {
        try {
            String withoutPrefix = jdbcUrl.replaceFirst("^jdbc:mysql://", "");
            String authority = withoutPrefix.split("/", 2)[0];
            return tcpFromHostPort(caseId, authority, "localhost", 3306);
        } catch (Exception exception) {
            return ProbeRequest.invalid(caseId, "SPRING_DATASOURCE_URL", jdbcUrl);
        }
    }

    private ProbeRequest tcpFromHostPort(String caseId, String hostPort, String fallbackHost, int fallbackPort) {
        String safeHostPort = blankToDefault(hostPort, fallbackHost + ":" + fallbackPort);
        String[] parts = safeHostPort.split(":", 2);
        String host = blankToDefault(parts[0], fallbackHost);
        int port = parts.length > 1 ? intValue(parts[1], fallbackPort) : fallbackPort;
        return ProbeRequest.tcp(caseId, host, port);
    }

    private String qdrantUrl(Map<String, String> env, Options options) {
        String baseUrl = value(env, options, "QDRANT_BASE_URL", "http://127.0.0.1:6333");
        return trimTrailingSlash(baseUrl) + "/healthz";
    }

    private CaseResult runRequest(ProbeRequest request) {
        ProbeResult result;
        try {
            result = probe.check(request);
        } catch (Exception exception) {
            result = ProbeResult.fail(
                    List.of("preflight_probe_failed(" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + ")"),
                    List.of("RUNTIME_UNAVAILABLE"),
                    Map.of("kind", request.kind())
            );
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>(request.params());
        if ("NONBLANK".equals(request.kind())) {
            diagnostics.remove("value");
        }
        diagnostics.put("kind", request.kind());
        diagnostics.putAll(result.diagnostics());
        return new CaseResult(
                request.caseId(),
                result.passed(),
                result.failures(),
                result.failureClass(),
                diagnostics
        );
    }

    private RagBenchmarkCase benchmarkCase(CaseResult result) {
        return new RagBenchmarkCase(
                result.caseId(),
                "Product launch runtime preflight: " + result.caseId(),
                "zh",
                "PRODUCT_LAUNCH_RUNTIME_PREFLIGHT",
                "RUNTIME",
                new RagBenchmarkCase.Scope(List.of(), List.of()),
                ROUTE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false
        );
    }

    private RagBenchmarkActual actual(CaseResult result) {
        return new RagBenchmarkActual(
                ROUTE,
                result.passed() ? "ready" : "not ready",
                Map.of(),
                result.diagnostics()
        );
    }

    private RagBenchmarkVerdict verdict(CaseResult result) {
        return new RagBenchmarkVerdict(
                result.caseId(),
                result.passed(),
                result.failures(),
                result.failureClass()
        );
    }

    private Map<String, Double> metrics(List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("runtimePreflightPassRate", fraction(passed, results.size()));
        metrics.put("runtimePreflightCaseCount", (double) results.size());
        metrics.put("runtimePreflightPassedCount", (double) passed);
        return metrics;
    }

    private static String value(Map<String, String> env, Options options, String key, String fallback) {
        String processValue = options == null || options.processEnv() == null ? null : options.processEnv().get(key);
        if (processValue != null && !processValue.isBlank()) {
            return processValue;
        }
        String value = env == null ? null : env.get(key);
        return value == null ? fallback : value;
    }

    private static String remediationMarkdown(Options options, List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        List<CaseResult> failures = results.stream().filter(result -> !result.passed()).toList();
        StringBuilder builder = new StringBuilder()
                .append("# Product Launch Runtime Remediation\n\n")
                .append("Run: `").append(options.runId()).append("`\n\n")
                .append("Status: ").append(failures.isEmpty() ? "launch preflight passed" : "not launch-ready")
                .append(" (").append(passed).append("/").append(results.size()).append(")\n\n");
        if (failures.isEmpty()) {
            builder.append("No runtime preflight blockers were detected. Continue with the launch gates below.\n\n");
        } else {
            builder.append("Do not run the 30-PDF seed, live Product Reading smoke, trace eval, or parser smoke ")
                    .append("until this preflight is ").append(results.size()).append("/")
                    .append(results.size()).append(" on the active runtime.\n\n")
                    .append("## Fix First\n\n");
            for (CaseResult failure : failures) {
                builder.append(remediationBullet(failure)).append("\n");
            }
            builder.append("\n");
        }
        builder.append("## Next Gate Order\n\n")
                .append("1. `ProductLaunchRuntimePreflightCli`\n")
                .append("2. `ProductPdfLaunchDataSeedCli`\n")
                .append("3. `ProductReadingLiveLaunchSmokeCli`\n")
                .append("4. `ProductReadingLaunchTraceEvalCli`\n")
                .append("5. `ProductPdfParserSmokeCli --manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl`\n");
        return builder.toString();
    }

    private static String remediationBullet(CaseResult failure) {
        String target = target(failure.diagnostics());
        return switch (failure.caseId()) {
            case "backend_login" -> "- `backend_login`: start the backend at `" + target
                    + "` and verify the eval admin login credentials. Do not print or store the password in artifacts.";
            case "frontend_http" -> "- `frontend_http`: start the frontend and set `PAPERLOOM_FRONTEND_BASE_URL` "
                    + "to the served PaperLoom app" + targetSuffix(target)
                    + ". The response must be the SPA shell containing `id=\"app\"`.";
            case "mysql_tcp" -> "- `mysql_tcp`: make `SPRING_DATASOURCE_URL` point to a reachable MySQL host/port"
                    + targetSuffix(target) + ", or expose the Docker MySQL container on the configured port.";
            case "redis_tcp" -> "- `redis_tcp`: start Redis or align `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT`"
                    + targetSuffix(target) + ".";
            case "kafka_tcp" -> "- `kafka_tcp`: start Kafka or align `SPRING_KAFKA_BOOTSTRAP_SERVERS`"
                    + targetSuffix(target) + ".";
            case "minio_health" -> "- `minio_health`: start MinIO, initialize the upload bucket, or align `MINIO_ENDPOINT`"
                    + targetSuffix(target) + ".";
            case "qdrant_health" -> "- `qdrant_health`: start Qdrant or align `QDRANT_BASE_URL` and its API key"
                    + targetSuffix(target) + ".";
            case "mineru_health" -> "- `mineru_health`: start the self-hosted MinerU sidecar or align "
                    + "`PAPER_PARSING_MINERU_BASE_URL` and `PAPER_PARSING_MINERU_HEALTH_PATH`" + targetSuffix(target)
                    + ". Do not switch to the OpenDataLoader fallback for launch evidence.";
            case "trace_config" -> "- `trace_config`: set `PAPERLOOM_TRACE_ENABLED=true` and a writable "
                    + "`PAPERLOOM_TRACE_ROOT` before running the live smoke.";
            default -> "- `" + failure.caseId() + "`: inspect `run.json` diagnostics and fix the reported "
                    + String.join("/", failure.failureClass()) + " blocker.";
        };
    }

    private static String target(Map<String, Object> diagnostics) {
        Object apiBase = diagnostics.get("apiBase");
        if (apiBase != null && !String.valueOf(apiBase).isBlank()) {
            return String.valueOf(apiBase);
        }
        Object url = diagnostics.get("url");
        if (url != null && !String.valueOf(url).isBlank()) {
            return String.valueOf(url);
        }
        Object apiBaseUrl = diagnostics.get("apiBaseUrl");
        if (apiBaseUrl != null && !String.valueOf(apiBaseUrl).isBlank()) {
            return String.valueOf(apiBaseUrl);
        }
        Object host = diagnostics.get("host");
        Object port = diagnostics.get("port");
        if (host != null && port != null) {
            return host + ":" + port;
        }
        Object key = diagnostics.get("key");
        if (key != null && !String.valueOf(key).isBlank()) {
            return String.valueOf(key);
        }
        return "";
    }

    private static String targetSuffix(String target) {
        return target == null || target.isBlank() ? "" : " (current target `" + target + "`)";
    }

    private static String firstHostPort(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "";
        }
        return normalized.split(",", 2)[0].trim();
    }

    private static int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(blankToDefault(value, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String ensureLeadingSlash(String value) {
        String normalized = blankToDefault(value, "/");
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private double fraction(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    public interface RuntimeProbe {
        ProbeResult check(ProbeRequest request);
    }

    public record ProbeRequest(
            String caseId,
            String kind,
            String target,
            String secret,
            Map<String, Object> params
    ) {
        public ProbeRequest {
            caseId = blankToDefault(caseId, "runtime_preflight");
            kind = blankToDefault(kind, "UNKNOWN");
            target = blankToDefault(target, "");
            secret = blankToDefault(secret, "");
            params = params == null ? Map.of() : new LinkedHashMap<>(params);
        }

        static ProbeRequest login(String apiBase, String username, String password) {
            return new ProbeRequest("backend_login", "LOGIN", trimTrailingSlash(apiBase), blankToDefault(password, ""), Map.of(
                    "apiBase", trimTrailingSlash(apiBase),
                    "username", blankToDefault(username, "")
            ));
        }

        static ProbeRequest tcp(String caseId, String host, int port) {
            return new ProbeRequest(caseId, "TCP", host + ":" + port, "", Map.of(
                    "host", blankToDefault(host, ""),
                    "port", port
            ));
        }

        static ProbeRequest http(String caseId, String url, List<Integer> acceptedStatuses) {
            return new ProbeRequest(caseId, "HTTP", url, "", Map.of(
                    "url", blankToDefault(url, ""),
                    "acceptedStatuses", acceptedStatuses == null ? List.of(200) : List.copyOf(acceptedStatuses)
            ));
        }

        static ProbeRequest frontendHttp(String frontendBaseUrl) {
            return new ProbeRequest("frontend_http", "HTTP", trimTrailingSlash(frontendBaseUrl), "", Map.of(
                    "url", trimTrailingSlash(frontendBaseUrl),
                    "acceptedStatuses", List.of(200),
                    "requiredBodyContains", "id=\"app\""
            ));
        }

        static ProbeRequest nonBlank(String caseId, String key, String value) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("key", blankToDefault(key, ""));
            params.put("value", blankToDefault(value, ""));
            params.put("present", blankToNull(value) != null);
            return new ProbeRequest(caseId, "NONBLANK", blankToDefault(key, ""), "", params);
        }

        static ProbeRequest llmApiSmoke(String apiBaseUrl, String model, String apiKey) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("apiBaseUrl", trimTrailingSlash(apiBaseUrl));
            params.put("model", blankToDefault(model, "deepseek-chat"));
            params.put("path", "/chat/completions");
            params.put("keyName", "DEEPSEEK_API_KEY");
            return new ProbeRequest(
                    "llm_api_smoke",
                    "LLM_API_SMOKE",
                    trimTrailingSlash(apiBaseUrl),
                    blankToDefault(apiKey, ""),
                    params
            );
        }

        static ProbeRequest traceConfig(String enabled, String root) {
            return new ProbeRequest("trace_config", "TRACE_CONFIG", blankToDefault(root, ""), "", Map.of(
                    "enabled", blankToDefault(enabled, "true"),
                    "root", blankToDefault(root, "data/traces/product-react")
            ));
        }

        static ProbeRequest invalid(String caseId, String key, String value) {
            return new ProbeRequest(caseId, "INVALID_CONFIG", blankToDefault(key, ""), "", Map.of(
                    "key", blankToDefault(key, ""),
                    "value", blankToDefault(value, "")
            ));
        }
    }

    public record ProbeResult(
            boolean passed,
            List<String> failures,
            List<String> failureClass,
            Map<String, Object> diagnostics
    ) {
        public ProbeResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
            diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        }

        static ProbeResult pass(Map<String, Object> diagnostics) {
            return new ProbeResult(true, List.of(), List.of(), diagnostics);
        }

        static ProbeResult fail(List<String> failures, List<String> failureClass, Map<String, Object> diagnostics) {
            return new ProbeResult(false, failures, failureClass, diagnostics);
        }
    }

    private record CaseResult(
            String caseId,
            boolean passed,
            List<String> failures,
            List<String> failureClass,
            Map<String, Object> diagnostics
    ) {
        private CaseResult {
            caseId = blankToDefault(caseId, "runtime_preflight");
            failures = failures == null ? List.of() : List.copyOf(failures);
            failureClass = failureClass == null ? List.of() : List.copyOf(failureClass);
            diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        }
    }

    public record Options(
            Path envPath,
            Path runsRoot,
            String runId,
            String startedAt,
            String harnessId,
            String datasetId,
            String apiBase,
            String username,
            String password,
            Integer timeoutSeconds,
            Map<String, String> processEnv
    ) {
        public Options(Path envPath,
                       Path runsRoot,
                       String runId,
                       String startedAt,
                       String harnessId,
                       String datasetId,
                       String apiBase,
                       String username,
                       String password,
                       Integer timeoutSeconds) {
            this(envPath, runsRoot, runId, startedAt, harnessId, datasetId, apiBase, username, password,
                    timeoutSeconds, System.getenv());
        }

        public Options {
            envPath = envPath == null ? Path.of(".env") : envPath;
            runsRoot = runsRoot == null ? Path.of("eval/rag/runs") : runsRoot;
            startedAt = blankToDefault(startedAt, Instant.now().toString());
            harnessId = blankToDefault(harnessId, "product-launch-runtime-preflight");
            datasetId = blankToDefault(datasetId, "product-launch-runtime-preflight");
            runId = blankToDefault(runId, defaultRunId(startedAt, harnessId, datasetId));
            apiBase = blankToDefault(apiBase, "http://127.0.0.1:8081/api/v1");
            username = blankToDefault(username, "admin");
            password = blankToDefault(password, "");
            timeoutSeconds = Math.max(1, timeoutSeconds == null ? 5 : timeoutSeconds);
            processEnv = processEnv == null ? Map.of() : Map.copyOf(processEnv);
        }

        static Options defaults() {
            return new Options(
                    Path.of(".env"),
                    Path.of("eval/rag/runs"),
                    null,
                    Instant.now().toString(),
                    "product-launch-runtime-preflight",
                    "product-launch-runtime-preflight",
                    "http://127.0.0.1:8081/api/v1",
                    "admin",
                    "",
                    5,
                    System.getenv()
            );
        }

        private static String defaultRunId(String startedAt, String harnessId, String datasetId) {
            return startedAt
                    .replace(":", "")
                    .replace(".", "")
                    .replace("Z", "Z")
                    + "-" + harnessId + "-" + datasetId;
        }
    }
}
