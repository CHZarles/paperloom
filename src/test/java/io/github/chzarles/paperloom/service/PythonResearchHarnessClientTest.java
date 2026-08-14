package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonResearchHarnessClientTest {

    @Test
    void totalTimeoutFailsAConnectedButSilentStream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/v1/research/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        UsageQuotaService quotaService = mock(UsageQuotaService.class);
        UsageQuotaService.TokenReservation reservation = UsageQuotaService.TokenReservation.noop("llm", "1");
        when(quotaService.reserveLlmTokens("1", 0, 1)).thenReturn(reservation);
        ResearchHarnessPayloadFactory payloadFactory = mock(ResearchHarnessPayloadFactory.class);
        when(payloadFactory.requestBody(any())).thenReturn(Map.of("question", "test"));
        PythonResearchHarnessClient client = new PythonResearchHarnessClient(
                new ObjectMapper(),
                quotaService,
                payloadFactory,
                mock(ResearchHarnessResultMapper.class),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                1,
                executor
        );

        try {
            CompletableFuture<ProductTurnResult> future = client.submit(
                    new ProductTurnRequest(
                            1L,
                            "conversation-1",
                            "generation-1",
                            "question",
                            SourceScope.manual(List.of("paper-1")),
                            List.of(),
                            Map.of(),
                            ProductModelContext.defaults()
                    ),
                    event -> { }
            );

            ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, error.getCause());
            verify(quotaService, timeout(1000)).abortReservation(reservation);
        } finally {
            client.shutdown();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            } finally {
                releaseResponse.countDown();
                server.stop(0);
            }
        }
    }
}
