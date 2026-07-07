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
                DEEPSEEK_API_URL=https://api.deepseek.com/v1
                DEEPSEEK_API_MODEL=deepseek-chat
                DEEPSEEK_API_KEY=llm-key
                EMBEDDING_API_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
                EMBEDDING_API_MODEL=text-embedding-v4
                EMBEDDING_API_KEY=embedding-key
                EMBEDDING_DIMENSION=2048
                PAPERLOOM_TRACE_ENABLED=true
                PAPERLOOM_TRACE_ROOT=data/traces/product-react
                PAPERLOOM_REACT_READING_PHASE1_ENABLED=true
                """);
        FakeProbe probe = FakeProbe.passAll();

        Path runDir = runner(probe).run(options(env));

        Set<String> caseIds = probe.requests.stream()
                .map(ProductLaunchRuntimePreflightRunner.ProbeRequest::caseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(
                "backend_login",
                "mysql_tcp",
                "redis_tcp",
                "kafka_tcp",
                "minio_health",
                "elasticsearch_health",
                "mineru_health",
                "llm_key",
                "llm_api_smoke",
                "embedding_key",
                "embedding_api_smoke",
                "trace_config",
                "reading_phase_flag"
        ), caseIds);

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(13, scorecard.path("caseCount").asInt());
        assertEquals(13, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("launch preflight passed"));
        assertTrue(remediation.contains("ProductPdfLaunchDataSeedCli"));

        ProductLaunchRuntimePreflightRunner.ProbeRequest mysql = probe.requests.stream()
                .filter(request -> "mysql_tcp".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("localhost", mysql.params().get("host"));
        assertEquals(13306, mysql.params().get("port"));
        ProductLaunchRuntimePreflightRunner.ProbeRequest llmSmoke = probe.requests.stream()
                .filter(request -> "llm_api_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("LLM_API_SMOKE", llmSmoke.kind());
        assertEquals("llm-key", llmSmoke.secret());
        assertEquals("https://api.deepseek.com/v1", llmSmoke.params().get("apiBaseUrl"));
        assertEquals("deepseek-chat", llmSmoke.params().get("model"));
        ProductLaunchRuntimePreflightRunner.ProbeRequest embeddingSmoke = probe.requests.stream()
                .filter(request -> "embedding_api_smoke".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("EMBEDDING_API_SMOKE", embeddingSmoke.kind());
        assertEquals("embedding-key", embeddingSmoke.secret());
        assertEquals(2048, embeddingSmoke.params().get("dimension"));
    }

    @Test
    void blankModelKeysFailAsConfigMissingCases() throws Exception {
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
        JsonNode llm = row(rows, "llm_key");
        JsonNode llmSmoke = row(rows, "llm_api_smoke");
        JsonNode embedding = row(rows, "embedding_key");
        JsonNode embeddingSmoke = row(rows, "embedding_api_smoke");
        assertFalse(llm.path("passed").asBoolean());
        assertTrue(llm.path("failureClass").toString().contains("CONFIG_MISSING"));
        assertFalse(llmSmoke.path("passed").asBoolean());
        assertTrue(llmSmoke.path("failureClass").toString().contains("CONFIG_MISSING"));
        assertFalse(embedding.path("passed").asBoolean());
        assertTrue(embedding.path("failureClass").toString().contains("CONFIG_MISSING"));
        assertFalse(embeddingSmoke.path("passed").asBoolean());
        assertTrue(embeddingSmoke.path("failureClass").toString().contains("CONFIG_MISSING"));
        JsonNode readingFlag = row(rows, "reading_phase_flag");
        assertFalse(readingFlag.path("passed").asBoolean());
        assertTrue(readingFlag.path("failureClass").toString().contains("CONFIG_MISSING"));
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertTrue(remediation.contains("not launch-ready"));
        assertTrue(remediation.contains("DEEPSEEK_API_KEY"));
        assertTrue(remediation.contains("EMBEDDING_API_KEY"));
        assertTrue(remediation.contains("PAPERLOOM_REACT_READING_PHASE1_ENABLED"));
        assertFalse(remediation.contains("secret"));
        assertFalse(remediation.contains("llm-key"));
        assertTrue(remediation.contains("Do not run the 30-PDF seed"));
        assertTrue(remediation.contains("13/13"));
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
                "SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:13306/paismart",
                "DEEPSEEK_API_KEY", "runtime-llm-key",
                "EMBEDDING_API_KEY", "runtime-embedding-key",
                "PAPERLOOM_REACT_READING_PHASE1_ENABLED", "true"
        )));

        ProductLaunchRuntimePreflightRunner.ProbeRequest mysql = probe.requests.stream()
                .filter(request -> "mysql_tcp".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals(13306, mysql.params().get("port"));
        assertTrue(probe.requests.stream()
                .filter(request -> "llm_key".equals(request.caseId()))
                .findFirst()
                .orElseThrow()
                .params()
                .get("present")
                .equals(true));
        assertTrue(probe.requests.stream()
                .filter(request -> "embedding_key".equals(request.caseId()))
                .findFirst()
                .orElseThrow()
                .params()
                .get("present")
                .equals(true));
        assertTrue(probe.requests.stream()
                .filter(request -> "reading_phase_flag".equals(request.caseId()))
                .findFirst()
                .orElseThrow()
                .params()
                .get("enabled")
                .equals("true"));
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
                PAPERLOOM_REACT_READING_PHASE1_ENABLED=true
                """);
        FakeProbe probe = FakeProbe.failOnly("llm_api_smoke", "llm_provider_rejected(status=401)", "CONFIG_INVALID");

        Path runDir = runner(probe).run(options(env));

        JsonNode llmSmoke = row(OBJECT_MAPPER.readTree(runDir.resolve("run.json").toFile()).path("cases"), "llm_api_smoke");
        assertFalse(llmSmoke.path("passed").asBoolean());
        assertTrue(llmSmoke.path("failureClass").toString().contains("CONFIG_INVALID"));
        assertEquals("LLM_API_SMOKE", llmSmoke.path("diagnostics").path("kind").asText());
        String runJson = Files.readString(runDir.resolve("run.json"));
        String remediation = Files.readString(runDir.resolve("remediation.md"));
        assertFalse(runJson.contains("llm-key"));
        assertFalse(runJson.contains("embedding-key"));
        assertFalse(remediation.contains("llm-key"));
        assertTrue(remediation.contains("llm_api_smoke"));
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
            if (configAware && "READING_FLAG".equals(request.kind())) {
                String enabled = String.valueOf(request.params().getOrDefault("enabled", ""));
                if (!Boolean.parseBoolean(enabled.trim())) {
                    return ProductLaunchRuntimePreflightRunner.ProbeResult.fail(
                            List.of("reading_phase_flag_disabled"),
                            List.of("CONFIG_MISSING"),
                            Map.of("kind", request.kind())
                    );
                }
            }
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(Map.of("kind", request.kind()));
        }
    }
}
