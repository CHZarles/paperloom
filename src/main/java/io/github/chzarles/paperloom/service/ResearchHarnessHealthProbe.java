package io.github.chzarles.paperloom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes the Python research harness once after startup so an unreachable harness shows up as a
 * single explicit log line instead of surfacing only when a user's turn fails.
 */
@Component
public class ResearchHarnessHealthProbe {

    private static final Logger logger = LoggerFactory.getLogger(ResearchHarnessHealthProbe.class);

    private final URI healthUri;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public ResearchHarnessHealthProbe(
            @Value("${research-harness.base-url}") String baseUrl,
            @Value("${research-harness.health-probe.request-timeout-seconds:3}") long requestTimeoutSeconds) {
        this.healthUri = URI.create(stripTrailingSlash(baseUrl) + "/health");
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestTimeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        Thread thread = new Thread(() -> {
            boolean healthy = probe();
            if (healthy) {
                logger.info("Research harness is reachable at {}", healthUri);
            } else {
                logger.warn("Research harness is unreachable at {}; research turns will fail until it starts",
                        healthUri);
            }
        }, "research-harness-health-probe");
        thread.setDaemon(true);
        thread.start();
    }

    boolean probe() {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            logger.debug("Research harness health probe failed: {}", error.toString());
            return false;
        }
    }

    private static String stripTrailingSlash(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
