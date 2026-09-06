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
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/arxiv")
public class ArxivSearchController {

    private final ArxivClient arxivClient;

    public ArxivSearchController(ArxivClient arxivClient) {
        this.arxivClient = arxivClient;
    }

    @PostMapping("/search")
    public Map<String, Object> search(
            Authentication authentication,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            @Valid @RequestBody ArxivSearchRequest request) {
        if (authentication == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        if (request.queryText().isBlank() && request.authors().isEmpty() && request.categories().isEmpty()
                && request.yearFrom() == null && request.yearTo() == null) {
            throw new IllegalArgumentException("at least one arXiv search condition is required");
        }
        ArxivClient.SearchResult result = arxivClient.search(new ArxivClient.SearchRequest(
                request.queryText(), request.authors(), request.categories(), request.yearFrom(), request.yearTo()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", result.papers().stream().map(this::paper).toList());
        response.put("returned_count", result.returnedCount());
        response.put("coverage", result.coverage());
        return response;
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

    @ExceptionHandler(ArxivClientException.class)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ARXIV_UNAVAILABLE",
                "message", "arXiv is temporarily unavailable"));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
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
            @JsonAnySetter Map<String, Object> unexpected) {
        public ArxivSearchRequest {
            queryText = queryText == null ? "" : queryText.trim();
            authors = authors == null ? List.of() : List.copyOf(authors);
            categories = categories == null ? List.of() : List.copyOf(categories);
            if (unexpected != null && !unexpected.isEmpty()) {
                throw new IllegalArgumentException("unexpected arXiv search fields");
            }
        }
    }
}
