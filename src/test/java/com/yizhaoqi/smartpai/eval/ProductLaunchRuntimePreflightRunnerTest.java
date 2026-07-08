package com.yizhaoqi.smartpai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductLaunchRuntimePreflightRunnerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void emitsAllLaunchCriticalChecksAndWritesPassingScorecard() throws Exception {
        Path env = env("""
                SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/paismart
                SPRING_DATA_REDIS_HOST=localhost
                SPRING_DATA_REDIS_PORT=16379
                SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
                MINIO_ENDPOINT=http://localhost:9000
                ELASTICSEARCH_HOST=localhost
                ELASTICSEARCH_PORT=9200
                PAPER_PARSING_MINERU_BASE_URL=http://localhost:8000
                PAPERLOOM_TRACE_ENABLED=true
                PAPERLOOM_TRACE_ROOT=data/traces/product-react
                """);
        FakeProbe probe = FakeProbe.passAll();

        Path runDir = runner(probe).run(options(env));

        Set<String> caseIds = probe.requests.stream()
                .map(ProductLaunchRuntimePreflightRunner.ProbeRequest::caseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(
                "backend_login",
                "frontend_http",
                "mysql_tcp",
                "redis_tcp",
                "kafka_tcp",
                "minio_health",
                "elasticsearch_health",
                "mineru_health",
                "llm_active_provider_smoke",
                "embedding_active_provider_smoke",
                "trace_config"
        ), caseIds);

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(11, scorecard.path("caseCount").asInt());
        assertEquals(11, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("launch preflight passed"));
        assertTrue(remediation.contains("ProductPdfLaunchDataSeedCli"));

        ProductLaunchRuntimePreflightRunner.ProbeRequest frontend = probe.requests.stream()
                .filter(request -> "frontend_http".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("HTTP", frontend.kind());
        assertEquals("http://127.0.0.1:9527", frontend.params().get("url"));
        assertEquals("id=\"app\"", frontend.params().get("requiredBodyContains"));
        ProductLaunchRuntimePreflightRunner.ProbeRequest mysql = probe.requests.stream()
                .filter(request -> "mysql_tcp".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("localhost", mysql.params().get("host"));
        assertEquals(13306, mysql.params().get("port"));
        ProductLaunchRuntimePreflightRunner.ProbeRequest llmProviderSmoke = probe.requests.stream()
                .filter(request -> "llm_active_provider_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER_SMOKE", llmProviderSmoke.kind());
        assertEquals("secret", llmProviderSmoke.secret());
        assertEquals("llm", llmProviderSmoke.params().get("scope"));
        ProductLaunchRuntimePreflightRunner.ProbeRequest embeddingProviderSmoke = probe.requests.stream()
                .filter(request -> "embedding_active_provider_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER_SMOKE", embeddingProviderSmoke.kind());
        assertEquals("embedding", embeddingProviderSmoke.params().get("scope"));
    }

    @Test
    void blankLegacyModelKeysDoNotBlockBackendProviderConfigChecks() throws Exception {
        Path env = env("""
                SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/paismart
                SPRING_DATA_REDIS_HOST=localhost
                SPRING_DATA_REDIS_PORT=16379
                SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
                MINIO_ENDPOINT=http://localhost:9000
                ELASTICSEARCH_HOST=localhost
                ELASTICSEARCH_PORT=9200
                PAPER_PARSING_MINERU_BASE_URL=http://localhost:8000
                DEEPSEEK_API_KEY=
                EMBEDDING_API_KEY=
                """);
        FakeProbe probe = FakeProbe.configAware();

        Path runDir = runner(probe).run(options(env));

        JsonNode rows = OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases");
        assertTrue(row(rows, "llm_active_provider_smoke").path("passed").asBoolean());
        assertTrue(row(rows, "embedding_active_provider_smoke").path("passed").asBoolean());
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("launch preflight passed"));
        assertFalse(remediation.contains("DEEPSEEK_API_KEY"));
        assertFalse(remediation.contains("EMBEDDING_API_KEY"));
        assertFalse(remediation.contains("secret"));
        assertFalse(remediation.contains("llm-key"));
        assertFalse(remediation.contains("Do not run the 30-PDF seed"));
        assertTrue(remediation.contains("11/11"));
    }

    @Test
    void processEnvironmentOverridesEnvFileValues() throws Exception {
        Path env = env("""
                SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/paismart
                DEEPSEEK_API_KEY=
                EMBEDDING_API_KEY=
                """);
        FakeProbe probe = FakeProbe.configAware();

        runner(probe).run(options(env, Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:13306/paismart"
        )));

        ProductLaunchRuntimePreflightRunner.ProbeRequest mysql = probe.requests.stream()
                .filter(request -> "mysql_tcp".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals(13306, mysql.params().get("port"));
        assertEquals("llm", probe.requests.stream()
                .filter(request -> "llm_active_provider_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow()
                .params()
                .get("scope"));
        assertEquals("embedding", probe.requests.stream()
                .filter(request -> "embedding_active_provider_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow()
                .params()
                .get("scope"));
    }

    @Test
    void failedProbeResultIsPreservedInDiagnostics() throws Exception {
        Path env = env("""
                SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/paismart
                DEEPSEEK_API_KEY=llm-key
                EMBEDDING_API_KEY=embedding-key
                """);
        FakeProbe probe = FakeProbe.failOnly("mysql_tcp", "tcp_unreachable(localhost:3306)", "RUNTIME_UNAVAILABLE");

        Path runDir = runner(probe).run(options(env));

        JsonNode mysql = row(OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases"), "mysql_tcp");
        assertFalse(mysql.path("passed").asBoolean());
        assertTrue(mysql.path("failures").toString().contains("tcp_unreachable"));
        assertTrue(mysql.path("failureClass").toString().contains("RUNTIME_UNAVAILABLE"));
        assertEquals("TCP", mysql.path("diagnostics").path("kind").asText());
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("SPRING_DATASOURCE_URL"));
        assertTrue(remediation.contains("localhost:3306"));
    }

    @Test
    void providerSmokeFailuresRemainSecretFreeInArtifacts() throws Exception {
        Path env = env("""
                SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13306/paismart
                DEEPSEEK_API_URL=https://api.deepseek.com/v1
                DEEPSEEK_API_MODEL=deepseek-chat
                DEEPSEEK_API_KEY=llm-key
                EMBEDDING_API_KEY=embedding-key
                """);
        FakeProbe probe = FakeProbe.failOnly("llm_active_provider_smoke", "llm_provider_rejected(status=401)", "CONFIG_INVALID");

        Path runDir = runner(probe).run(options(env));

        JsonNode llmSmoke = row(OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases"), "llm_active_provider_smoke");
        assertFalse(llmSmoke.path("passed").asBoolean());
        assertTrue(llmSmoke.path("failureClass").toString().contains("CONFIG_INVALID"));
        assertEquals("MODEL_PROVIDER_SMOKE", llmSmoke.path("diagnostics").path("kind").asText());
        String runJson = Files.readString(runDir.resolve("run.json"));
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertFalse(runJson.contains("llm-key"));
        assertFalse(runJson.contains("embedding-key"));
        assertFalse(remediation.contains("llm-key"));
        assertTrue(remediation.contains("llm_active_provider_smoke"));
    }

    @Test
    void frontendHttpFailureHasActionableRemediation() throws Exception {
        Path env = env("""
                PAPERLOOM_FRONTEND_BASE_URL=http://127.0.0.1:9527
                DEEPSEEK_API_KEY=llm-key
                EMBEDDING_API_KEY=embedding-key
                """);
        FakeProbe probe = FakeProbe.failOnly("frontend_http", "http_unreachable(http://127.0.0.1:9527)",
                "RUNTIME_UNAVAILABLE");

        Path runDir = runner(probe).run(options(env));

        JsonNode frontend = row(OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases"),
                "frontend_http");
        assertFalse(frontend.path("passed").asBoolean());
        assertTrue(frontend.path("failureClass").toString().contains("RUNTIME_UNAVAILABLE"));
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("PAPERLOOM_FRONTEND_BASE_URL"));
        assertTrue(remediation.contains("http://127.0.0.1:9527"));
    }

    private ProductLaunchRuntimePreflightRunner runner(FakeProbe probe) {
        return new ProductLaunchRuntimePreflightRunner(probe);
    }

    private ProductLaunchRuntimePreflightRunner.Options options(Path env) {
        return options(env, Map.of());
    }

    private ProductLaunchRuntimePreflightRunner.Options options(Path env, Map<String, String> processEnv) {
        return new ProductLaunchRuntimePreflightRunner.Options(
                env,
                tempDir.resolve("runs"),
                "runtime-preflight-test",
                "2026-07-07T14:00:00Z",
                "product-launch-runtime-preflight",
                "product-launch-runtime-preflight",
                "http://127.0.0.1:8081/api/v1",
                "admin",
                "secret",
                1,
                processEnv
        );
    }

    private Path env(String content) throws Exception {
        Path env = tempDir.resolve(".env-" + System.nanoTime());
        Files.writeString(env, content);
        return env;
    }

    private static JsonNode row(JsonNode rows, String caseId) {
        for (JsonNode row : rows) {
            if (caseId.equals(row.path("caseId").asText())) {
                return row;
            }
        }
        throw new AssertionError("missing row " + caseId);
    }

    private static final class FakeProbe implements ProductLaunchRuntimePreflightRunner.RuntimeProbe {
        private final List<ProductLaunchRuntimePreflightRunner.ProbeRequest> requests = new ArrayList<>();
        private final String failingCaseId;
        private final String failure;
        private final String failureClass;
        private final boolean configAware;

        private FakeProbe(String failingCaseId, String failure, String failureClass, boolean configAware) {
            this.failingCaseId = failingCaseId;
            this.failure = failure;
            this.failureClass = failureClass;
            this.configAware = configAware;
        }

        static FakeProbe passAll() {
            return new FakeProbe(null, null, null, false);
        }

        static FakeProbe configAware() {
            return new FakeProbe(null, null, null, true);
        }

        static FakeProbe failOnly(String caseId, String failure, String failureClass) {
            return new FakeProbe(caseId, failure, failureClass, true);
        }

        @Override
        public ProductLaunchRuntimePreflightRunner.ProbeResult check(
                ProductLaunchRuntimePreflightRunner.ProbeRequest request) {
            requests.add(request);
            if (request.caseId().equals(failingCaseId)) {
                return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                        List.of(failure),
                        List.of(failureClass),
                        Map.of("kind", request.kind())
                );
            }
            if (configAware && "NONBLANK".equals(request.kind())) {
                String value = String.valueOf(request.params().getOrDefault("value", ""));
                if (value.isBlank()) {
                    return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                            List.of("config_missing(" + request.params().get("key") + ")"),
                            List.of("CONFIG_MISSING"),
                            Map.of("kind", request.kind())
                    );
                }
            }
            if (configAware && (request.kind().equals("LLM_API_SMOKE") || request.kind().equals("EMBEDDING_API_SMOKE"))
                    && request.secret().isBlank()) {
                return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                        List.of(request.caseId() + "_missing_key"),
                        List.of("CONFIG_MISSING"),
                        Map.of("kind", request.kind())
                );
            }
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(Map.of("kind", request.kind()));
        }
    }
}
