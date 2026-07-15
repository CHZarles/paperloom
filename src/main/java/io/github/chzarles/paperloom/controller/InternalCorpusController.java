package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import io.github.chzarles.paperloom.service.CorpusRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/internal/v1/corpus")
public class InternalCorpusController {

    private static final Logger logger = LoggerFactory.getLogger(InternalCorpusController.class);

    private final CorpusRetrievalService retrievalService;
    private final String internalToken;

    public InternalCorpusController(CorpusRetrievalService retrievalService,
                                    @Value("${research-harness.internal-token:}") String internalToken) {
        this.retrievalService = retrievalService;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/papers/search")
    public Map<String, Object> searchPapers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody PaperSearchRequest request) {
        authorize(authorization);
        if (request.identity() != null && !request.identity().isEmpty()) {
            CorpusRetrievalService.IdentitySearchResult result = retrievalService.findPapersByIdentity(
                    new CorpusRetrievalService.IdentitySearchQuery(
                            request.userId(), request.scopePaperIds(), request.identity())
            );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.status());
            response.put("matches", result.matches().stream().map(this::paperCard).toList());
            if (result.reason() != null) {
                response.put("reason", result.reason());
            }
            return response;
        }
        CorpusRetrievalService.PaperSearchResult result = retrievalService.searchPapers(
                new CorpusRetrievalService.PaperSearchQuery(
                        request.userId(),
                        request.scopePaperIds(),
                        request.queryText(),
                        request.paperIds(),
                        request.authors(),
                        request.venues(),
                        request.yearFrom(),
                        request.yearTo(),
                        request.offset() == null ? 0 : request.offset(),
                        request.limit() == null ? 20 : request.limit()
                )
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query_text", result.queryText());
        response.put("candidates", result.candidates().stream().map(this::paperCard).toList());
        response.put("matched_count", result.matchedCount());
        response.put("returned_count", result.returnedCount());
        response.put("coverage", result.coverage());
        response.put("next_offset", result.nextOffset());
        return response;
    }

    @PostMapping("/locations/search")
    public Map<String, Object> searchLocations(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody LocationSearchRequest request) {
        authorize(authorization);
        CorpusRetrievalService.LocationSearchResult result = retrievalService.searchLocations(
                new CorpusRetrievalService.LocationSearchQuery(
                        request.userId(),
                        request.scopePaperIds(),
                        request.paperIds(),
                        request.queryText(),
                        request.sectionQuery(),
                        request.elementTypes(),
                        request.pageFrom(),
                        request.pageTo(),
                        request.topK() == null ? 8 : request.topK()
                )
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query_text", request.queryText() == null ? "" : request.queryText().trim());
        response.put("locations", result.locations().stream().map(this::locationCandidate).toList());
        response.put("matched_count", result.matchedCount());
        response.put("returned_count", result.returnedCount());
        response.put("coverage", result.returnedCount() >= result.matchedCount() ? "complete" : "truncated");
        response.put("index_version", result.indexVersion());
        return response;
    }

    @PostMapping("/locations/read")
    public Map<String, Object> readLocations(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody LocationReadRequest request) {
        authorize(authorization);
        CanonicalReadingLocationService.ReadBatch result = retrievalService.readLocations(
                new CorpusRetrievalService.LocationReadQuery(
                        request.userId(), request.scopePaperIds(), request.locationRefs())
        );
        return Map.of(
                "items", result.items().stream().map(this::canonicalLocation).toList(),
                "missing_location_refs", result.missingLocationRefs()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_corpus_request",
                "message", safeMessage(error)
        ));
    }

    @ExceptionHandler(UnauthorizedCorpusRequest.class)
    public ResponseEntity<Map<String, Object>> unauthorized(UnauthorizedCorpusRequest error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> corpusUnavailable(Exception error) {
        logger.error("Internal Corpus API failed", error);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "corpus_unavailable",
                "message", "The corpus retrieval plane is temporarily unavailable"
        ));
    }

    private void authorize(String authorization) {
        if (internalToken.isBlank()) {
            throw new UnauthorizedCorpusRequest();
        }
        String supplied = authorization == null ? "" : authorization.trim();
        String expected = "Bearer " + internalToken;
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedCorpusRequest();
        }
    }

    private Map<String, Object> paperCard(CorpusRetrievalService.PaperCard card) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "paper_id", card.paperId());
        put(result, "title", card.title());
        put(result, "authors", card.authors());
        put(result, "year", card.year());
        put(result, "venue", card.venue());
        put(result, "doi", card.doi());
        put(result, "arxiv_id", card.arxivId());
        put(result, "filename", card.filename());
        put(result, "preview", card.preview());
        return result;
    }

    private Map<String, Object> locationCandidate(CorpusRetrievalService.LocationCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "paper_id", candidate.paperId());
        put(result, "title", candidate.title());
        put(result, "paper_version", candidate.paperVersion());
        put(result, "location_ref", candidate.locationRef());
        put(result, "section", candidate.section());
        put(result, "page", candidate.page());
        put(result, "element_type", candidate.elementType());
        put(result, "preview", candidate.preview());
        put(result, "dense_score", candidate.denseScore());
        put(result, "sparse_score", candidate.sparseScore());
        put(result, "fused_score", candidate.fusedScore());
        return result;
    }

    private Map<String, Object> canonicalLocation(CanonicalReadingLocationService.CanonicalLocation item) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "paper_id", item.paperId());
        put(result, "title", item.title());
        put(result, "paper_version", item.paperVersion());
        put(result, "location_ref", item.locationRef());
        put(result, "element_type", item.elementType());
        put(result, "page", item.page());
        put(result, "page_end", item.pageEnd());
        put(result, "section", item.section());
        put(result, "span_text", item.spanText());
        put(result, "bbox_json", item.bboxJson());
        put(result, "parser_name", item.parserName());
        put(result, "parser_version", item.parserVersion());
        put(result, "source_object_id", item.sourceObjectId());
        return result;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "The corpus request is invalid"
                : error.getMessage();
    }

    public record PaperSearchRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("scope_paper_ids") List<String> scopePaperIds,
            @JsonProperty("query_text") String queryText,
            @JsonProperty("paper_ids") List<String> paperIds,
            List<String> authors,
            List<String> venues,
            @JsonProperty("year_from") Integer yearFrom,
            @JsonProperty("year_to") Integer yearTo,
            Integer offset,
            Integer limit,
            Map<String, Object> identity
    ) {
        public PaperSearchRequest {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            authors = authors == null ? List.of() : List.copyOf(authors);
            venues = venues == null ? List.of() : List.copyOf(venues);
            identity = identity == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(identity));
        }
    }

    public record LocationSearchRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("scope_paper_ids") List<String> scopePaperIds,
            @JsonProperty("paper_ids") List<String> paperIds,
            @JsonProperty("query_text") String queryText,
            @JsonProperty("section_query") String sectionQuery,
            @JsonProperty("element_types") List<String> elementTypes,
            @JsonProperty("page_from") Integer pageFrom,
            @JsonProperty("page_to") Integer pageTo,
            @JsonProperty("top_k") Integer topK
    ) {
        public LocationSearchRequest {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            elementTypes = elementTypes == null ? List.of() : List.copyOf(elementTypes);
        }
    }

    public record LocationReadRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("user_id") Long userId,
            @JsonProperty("scope_paper_ids") List<String> scopePaperIds,
            @JsonProperty("location_refs") List<String> locationRefs
    ) {
        public LocationReadRequest {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            locationRefs = locationRefs == null ? List.of() : List.copyOf(locationRefs);
        }
    }

    private static final class UnauthorizedCorpusRequest extends RuntimeException {
    }
}
