package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.chzarles.paperloom.service.ArxivClient;
import io.github.chzarles.paperloom.service.ArxivClientException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/arxiv")
public class InternalArxivController {

    private static final Logger logger = LoggerFactory.getLogger(InternalArxivController.class);
    private final ArxivClient arxivClient;
    private final String internalToken;

    public InternalArxivController(ArxivClient arxivClient,
                                   @Value("${research-harness.internal-token:}") String internalToken) {
        this.arxivClient = arxivClient;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/search")
    public Map<String, Object> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ArxivSearchRequest request) {
        authorize(authorization);
        if (request.queryText().isBlank() && request.authors().isEmpty() && request.categories().isEmpty()
                && request.yearFrom() == null && request.yearTo() == null) {
            throw new IllegalArgumentException("at least one arXiv search condition is required");
        }
        long started = System.nanoTime();
        try {
            ArxivClient.SearchResult result = arxivClient.search(new ArxivClient.SearchRequest(
                    request.queryText(), request.authors(), request.categories(), request.yearFrom(), request.yearTo()));
            logger.info("arXiv search requestId={} status=success returnedCount={} durationMs={}",
                    safe(request.requestId()), result.returnedCount(), elapsedMillis(started));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", result.papers().stream().map(this::paper).toList());
            response.put("returned_count", result.returnedCount());
            response.put("coverage", result.coverage());
            return response;
        } catch (ArxivClientException error) {
            logger.warn("arXiv search requestId={} status=unavailable durationMs={}",
                    safe(request.requestId()), elapsedMillis(started));
            throw error;
        }
    }

    private Map<String, Object> paper(ArxivClient.Paper paper) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("arxiv_id", paper.arxivId());
        result.put("title", paper.title());
        result.put("authors", paper.authors());
        result.put("published", paper.published());
        result.put("updated", paper.updated());
        result.put("abstract_preview", paper.abstractPreview());
        result.put("pdf_url", paper.pdfUrl());
        result.put("metadata_only", true);
        return result;
    }

    private void authorize(String authorization) {
        if (internalToken.isBlank()) {
            throw new UnauthorizedArxivRequest();
        }
        String supplied = authorization == null ? "" : authorization.trim();
        String expected = "Bearer " + internalToken;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedArxivRequest();
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.:-]", "").substring(0,
                Math.min(80, value.replaceAll("[^A-Za-z0-9_.:-]", "").length()));
    }

    @ExceptionHandler(ArxivClientException.class)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ARXIV_UNAVAILABLE",
                "message", "arXiv is temporarily unavailable"));
    }

    @ExceptionHandler(UnauthorizedArxivRequest.class)
    public ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "UNAUTHORIZED"));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "ARXIV_ARGUMENTS_INVALID",
                "message", "The arXiv search arguments are invalid"));
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ArxivSearchRequest(
            @Size(max = 200) @JsonProperty("query_text") String queryText,
            @Size(max = 5) List<@NotBlank @Size(max = 100) String> authors,
            @Size(max = 5) List<@NotBlank @Size(max = 32) String> categories,
            @Min(1900) @Max(2100) @JsonProperty("year_from") Integer yearFrom,
            @Min(1900) @Max(2100) @JsonProperty("year_to") Integer yearTo,
            @Positive @NotNull @JsonProperty("user_id") Integer userId,
            @Size(max = 100) @JsonProperty("request_id") String requestId,
            @Size(max = 100) @JsonProperty("conversation_id") String conversationId,
            @JsonAnySetter Map<String, Object> unexpected) {
        public ArxivSearchRequest {
            queryText = queryText == null ? "" : queryText.trim();
            authors = authors == null ? List.of() : List.copyOf(authors);
            categories = categories == null ? List.of() : List.copyOf(categories);
            unexpected = unexpected == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(unexpected));
            if (!unexpected.isEmpty()) {
                throw new IllegalArgumentException("unexpected arXiv search fields");
            }
        }
    }

    private static final class UnauthorizedArxivRequest extends RuntimeException {
    }
}
