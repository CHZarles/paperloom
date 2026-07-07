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
                DEEPSEEK_API_KEY=llm-key
                EMBEDDING_API_KEY=embedding-key
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
                "mysql_tcp",
                "redis_tcp",
                "kafka_tcp",
                "minio_health",
                "elasticsearch_health",
                "mineru_health",
                "llm_key",
                "embedding_key",
                "trace_config"
        ), caseIds);

        JsonNode scorecard = OBJECT_MAPPER.readTree(runDir.resolve("scorecard.json").toFile());
        assertEquals(10, scorecard.path("caseCount").asInt());
        assertEquals(10, scorecard.path("passed").asInt());
        assertEquals(1.0d, scorecard.path("passRate").asDouble());

        ProductLaunchRuntimePreflightRunner.ProbeRequest mysql = probe.requests.stream()
                .filter(request -> "mysql_tcp".equals(request.caseId()))
                .findFirst()
                .orElseThrow();
        assertEquals("localhost", mysql.params().get("host"));
        assertEquals(13306, mysql.params().get("port"));
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
        JsonNode embedding = row(rows, "embedding_key");
        assertFalse(llm.path("passed").asBoolean());
        assertTrue(llm.path("failureClass").toString().contains("CONFIG_MISSING"));
        assertFalse(embedding.path("passed").asBoolean());
        assertTrue(embedding.path("failureClass").toString().contains("CONFIG_MISSING"));
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
    }

    private ProductLaunchRuntimePreflightRunner runner(FakeProbe probe) {
        return new ProductLaunchRuntimePreflightRunner(probe);
    }

    private ProductLaunchRuntimePreflightRunner.Options options(Path env) {
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
                1
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
            return ProductLaunchRuntimePreflightRunner.ProbeResult.pass(Map.of("kind", request.kind()));
        }
    }
}
