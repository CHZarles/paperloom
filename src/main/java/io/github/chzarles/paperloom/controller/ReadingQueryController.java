package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.config.ReadingQueryAudit;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import io.github.chzarles.paperloom.service.PaperAccessService;
import io.github.chzarles.paperloom.service.PaperReadingException;
import io.github.chzarles.paperloom.service.PaperReadingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/reading")
public class ReadingQueryController {

    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final PaperReadingService readingService;
    private final UserRepository userRepository;
    private final PaperAccessService paperAccessService;
    private final ObjectMapper objectMapper;
    private final ReadingQueryAudit audit;

    public ReadingQueryController(PaperReadingService readingService,
                                  UserRepository userRepository,
                                  PaperAccessService paperAccessService,
                                  ObjectMapper objectMapper,
                                  ReadingQueryAudit audit) {
        this.readingService = readingService;
        this.userRepository = userRepository;
        this.paperAccessService = paperAccessService;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @PostMapping("/papers/search")
    public Map<String, Object> searchPapers(Authentication authentication,
                                            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
                                            @Valid @RequestBody PaperSearchRequest request) {
        return query(authentication, requestId, "search_papers", userId -> {
            PaperReadingService.PaperSearchResult result = readingService.searchPaperCandidates(
                    new PaperReadingService.PaperSearchQuery(
                            userId, List.of(), request.queryText(), request.paperIds(), request.authors(),
                            request.venues(), request.yearFrom(), request.yearTo(),
                            request.offset() == null ? 0 : request.offset(),
                            request.limit() == null ? 20 : request.limit()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query_text", result.queryText());
            response.put("matched_count", result.matchedCount());
            List<Map<String, Object>> papers = new ArrayList<>();
            response.put("papers", papers);
            int offset = request.offset() == null ? 0 : request.offset();
            updatePage(response, offset, 0, result.matchedCount());
            for (PaperReadingService.PaperCard card : result.candidates()) {
                papers.add(paperCard(card));
                updatePage(response, offset, papers.size(), result.matchedCount());
                if (!fits(response)) {
                    papers.remove(papers.size() - 1);
                    updatePage(response, offset, papers.size(), result.matchedCount());
                    break;
                }
            }
            return bounded(response);
        });
    }

    @PostMapping("/papers/get")
    public Map<String, Object> getPaper(Authentication authentication,
                                        @RequestHeader(value = "X-Request-ID", required = false) String requestId,
                                        @Valid @RequestBody PaperRequest request) {
        return query(authentication, requestId, "get_paper", userId ->
                bounded(paperCard(requirePaper(userId, request.paperId()))));
    }

    @PostMapping("/structure")
    public Map<String, Object> getStructure(Authentication authentication,
                                            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
                                            @Valid @RequestBody StructureRequest request) {
        return query(authentication, requestId, "get_paper_structure", userId -> {
            String paperId = requiredPaperId(request.paperId());
            PaperReadingService.StructureResult result = readingService.getPaperStructure(
                    new PaperReadingService.StructureQuery(
                            userId, List.of(paperId), List.of(paperId), request.structureType(),
                            request.sectionQuery(), request.pageFrom(), request.pageTo()));
            int offset = request.offset() == null ? 0 : request.offset();
            int limit = request.limit() == null ? 100 : request.limit();
            int from = Math.min(offset, result.items().size());
            int to = Math.min(from + limit, result.items().size());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("structure_type", result.structureType());
            response.put("matched_count", result.items().size());
            List<Map<String, Object>> items = new ArrayList<>();
            response.put("items", items);
            updatePage(response, offset, 0, result.items().size());
            for (PaperReadingService.StructureItem item : result.items().subList(from, to)) {
                items.add(structureItem(item));
                updatePage(response, offset, items.size(), result.items().size());
                if (!fits(response)) {
                    items.remove(items.size() - 1);
                    updatePage(response, offset, items.size(), result.items().size());
                    break;
                }
            }
            return bounded(response);
        });
    }

    @PostMapping("/content/search")
    public Map<String, Object> searchContent(Authentication authentication,
                                             @RequestHeader(value = "X-Request-ID", required = false) String requestId,
                                             @Valid @RequestBody ContentSearchRequest request) {
        return query(authentication, requestId, "search_paper_content", userId -> {
            String paperId = requiredPaperId(request.paperId());
            PaperReadingService.ContentSearchResult result = readingService.searchPaperContent(
                    new PaperReadingService.ContentSearchQuery(
                            userId, List.of(paperId), List.of(paperId), request.queryText(),
                            request.sectionQuery(), request.elementTypes(), request.pageFrom(), request.pageTo(),
                            request.topK() == null ? 8 : request.topK(), request.sectionRefs()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query_text", request.queryText());
            response.put("matched_count", result.matchedCount());
            List<Map<String, Object>> locations = new ArrayList<>();
            response.put("locations", locations);
            updateCoverage(response, 0, result.matchedCount());
            for (PaperReadingService.LocationCandidate candidate : result.locations()) {
                locations.add(location(candidate));
                updateCoverage(response, locations.size(), result.matchedCount());
                if (!fits(response)) {
                    locations.remove(locations.size() - 1);
                    updateCoverage(response, locations.size(), result.matchedCount());
                    break;
                }
            }
            return bounded(response);
        });
    }

    @PostMapping("/content/read")
    public Map<String, Object> readContent(Authentication authentication,
                                           @RequestHeader(value = "X-Request-ID", required = false) String requestId,
                                           @Valid @RequestBody ContentReadRequest request) {
        return query(authentication, requestId, "read_paper_content", userId -> {
            String paperId = requiredPaperId(request.paperId());
            if (!paperAccessService.canAccess(String.valueOf(userId), paperId)) {
                throw new PaperReadingException("PAPER_NOT_AVAILABLE", "The requested paper is not available");
            }
            PaperReadingService.ContentReadResult result = readingService.readPaperContent(
                    new PaperReadingService.ContentReadQuery(
                            userId, List.of(paperId), request.locationRefs()));
            List<Map<String, Object>> items = new ArrayList<>();
            LinkedHashSet<String> missing = new LinkedHashSet<>(result.missingLocationRefs());
            for (CanonicalReadingLocationService.CanonicalLocation item : result.items()) {
                if (paperId.equals(item.paperId())) {
                    items.add(content(item));
                } else {
                    missing.add(item.locationRef());
                }
            }
            return bounded(Map.of(
                    "items", items,
                    "missing_location_refs", List.copyOf(missing)
            ));
        });
    }

    private PaperReadingService.PaperCard requirePaper(Long userId, String rawPaperId) {
        String paperId = requiredPaperId(rawPaperId);
        PaperReadingService.IdentitySearchResult result = readingService.findPapersByIdentity(
                new PaperReadingService.IdentitySearchQuery(
                        userId, List.of(paperId), Map.of("paper_id", paperId)));
        if (!"resolved".equals(result.status()) || result.matches().size() != 1) {
            throw new PaperReadingException("PAPER_NOT_AVAILABLE", "The requested paper is not available");
        }
        return result.matches().get(0);
    }

    private String requiredPaperId(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            throw new PaperReadingException("READING_ARGUMENTS_INVALID", "paper_id is required");
        }
        return paperId.trim();
    }

    private <T> T query(Authentication authentication, String rawRequestId,
                        String toolName, Function<Long, T> operation) {
        Long principalId = null;
        try {
            User user = authenticatedUser(authentication);
            principalId = user.getId();
            T result = operation.apply(user.getId());
            audit.success(principalId, toolName, rawRequestId);
            return result;
        } catch (RuntimeException error) {
            audit.failure(principalId, toolName, rawRequestId, errorCode(error));
            throw error;
        }
    }

    private void updatePage(Map<String, Object> response, int offset, int returned, int matched) {
        response.put("returned_count", returned);
        boolean complete = offset + returned >= matched;
        response.put("coverage", complete ? "complete" : "truncated");
        if (complete) {
            response.remove("next_offset");
        } else {
            response.put("next_offset", offset + returned);
        }
    }

    private void updateCoverage(Map<String, Object> response, int returned, int matched) {
        response.put("returned_count", returned);
        response.put("coverage", returned >= matched ? "complete" : "truncated");
    }

    private boolean fits(Map<String, Object> response) {
        try {
            return objectMapper.writeValueAsBytes(response).length <= MAX_RESPONSE_BYTES;
        } catch (JsonProcessingException error) {
            throw new PaperReadingException("RETRIEVAL_UNAVAILABLE", "Paper reading is temporarily unavailable");
        }
    }

    private Map<String, Object> bounded(Map<String, Object> response) {
        if (!fits(response)) {
            throw new PaperReadingException(
                    "READING_RESULT_TOO_LARGE", "The reading result exceeds the public size limit");
        }
        return response;
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private String errorCode(RuntimeException error) {
        if (error instanceof PaperReadingException readingError) {
            return readingError.code();
        }
        if (error instanceof ResponseStatusException statusError
                && statusError.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return "UNAUTHORIZED";
        }
        return error instanceof QuotaExceededException
                ? "READING_QUOTA_EXHAUSTED"
                : "INTERNAL_UNEXPECTED";
    }

    @ExceptionHandler(PaperReadingException.class)
    public ResponseEntity<Map<String, Object>> readingFailure(PaperReadingException error) {
        HttpStatus status = switch (error.code()) {
            case "READING_ARGUMENTS_INVALID" -> HttpStatus.BAD_REQUEST;
            case "PAPER_NOT_AVAILABLE" -> HttpStatus.NOT_FOUND;
            case "READING_MODEL_NOT_READY" -> HttpStatus.CONFLICT;
            case "READING_RESULT_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", error.code(),
                "message", error.safeMessage()
        ));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quotaUnavailable() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "READING_QUOTA_EXHAUSTED",
                "message", "Embedding quota is unavailable"
        ));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest(HttpServletRequest request) {
        auditRejected(request, "READING_ARGUMENTS_INVALID");
        return ResponseEntity.badRequest().body(Map.of(
                "error", "READING_ARGUMENTS_INVALID",
                "message", "The reading query arguments are invalid"
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> authenticationFailure(ResponseStatusException error) {
        if (error.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHORIZED",
                    "message", "Authentication is required"
            ));
        }
        return unavailable();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "RETRIEVAL_UNAVAILABLE",
                "message", "Paper reading is temporarily unavailable"
        ));
    }

    private void auditRejected(HttpServletRequest request, String outcome) {
        audit.rejection(request,
                request.getUserPrincipal() instanceof Authentication authentication ? authentication : null,
                outcome);
    }

    private Map<String, Object> paperCard(PaperReadingService.PaperCard card) {
        Map<String, Object> item = new LinkedHashMap<>();
        put(item, "paper_id", card.paperId());
        put(item, "title", card.title());
        put(item, "authors", card.authors());
        put(item, "year", card.year());
        put(item, "venue", card.venue());
        put(item, "doi", card.doi());
        put(item, "arxiv_id", card.arxivId());
        put(item, "filename", card.filename());
        put(item, "preview", card.preview());
        return item;
    }

    private Map<String, Object> structureItem(PaperReadingService.StructureItem source) {
        Map<String, Object> item = new LinkedHashMap<>();
        put(item, "paper_id", source.paperId());
        put(item, "title", source.title());
        put(item, "location_ref", source.locationRef());
        put(item, "structure_type", source.structureType());
        put(item, "section_title", source.sectionTitle());
        put(item, "section_level", source.sectionLevel());
        put(item, "section_path", source.sectionPath());
        put(item, "display_order", source.displayOrder());
        put(item, "page_from", source.pageFrom());
        put(item, "page_to", source.pageTo());
        return item;
    }

    private Map<String, Object> location(PaperReadingService.LocationCandidate source) {
        Map<String, Object> item = new LinkedHashMap<>();
        put(item, "paper_id", source.paperId());
        put(item, "title", source.title());
        put(item, "location_ref", source.locationRef());
        put(item, "section", source.section());
        put(item, "page", source.page());
        put(item, "element_type", source.elementType());
        put(item, "preview", source.preview());
        return item;
    }

    private Map<String, Object> content(CanonicalReadingLocationService.CanonicalLocation source) {
        Map<String, Object> item = new LinkedHashMap<>();
        put(item, "paper_id", source.paperId());
        put(item, "location_ref", source.locationRef());
        put(item, "section", source.section());
        put(item, "page", source.page());
        put(item, "page_end", source.pageEnd());
        put(item, "content_kind", source.elementType().toUpperCase());
        put(item, "content", source.spanText());
        return item;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> rejectUnexpected(Map<String, Object> values) {
        if (values != null && !values.isEmpty()) {
            throw new PaperReadingException("READING_ARGUMENTS_INVALID", "Unexpected reading query fields");
        }
        return Map.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PaperSearchRequest(
            @Size(max = 4000) @JsonProperty("query_text") String queryText,
            @Size(max = 100) @JsonProperty("paper_ids") List<@NotBlank @Size(max = 64) String> paperIds,
            @Size(max = 100) List<@NotBlank @Size(max = 500) String> authors,
            @Size(max = 100) List<@NotBlank @Size(max = 500) String> venues,
            @PositiveOrZero @JsonProperty("year_from") Integer yearFrom,
            @PositiveOrZero @JsonProperty("year_to") Integer yearTo,
            @PositiveOrZero Integer offset,
            @Min(1) @Max(100) Integer limit,
            @JsonAnySetter Map<String, Object> unexpected
    ) {
        public PaperSearchRequest {
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            authors = authors == null ? List.of() : List.copyOf(authors);
            venues = venues == null ? List.of() : List.copyOf(venues);
            unexpected = rejectUnexpected(unexpected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PaperRequest(@NotBlank @Size(max = 64) @JsonProperty("paper_id") String paperId,
                               @JsonAnySetter Map<String, Object> unexpected) {
        public PaperRequest {
            unexpected = rejectUnexpected(unexpected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StructureRequest(
            @NotBlank @Size(max = 64) @JsonProperty("paper_id") String paperId,
            @Size(max = 16) @JsonProperty("structure_type") String structureType,
            @Size(max = 4000) @JsonProperty("section_query") String sectionQuery,
            @Min(1) @JsonProperty("page_from") Integer pageFrom,
            @Min(1) @JsonProperty("page_to") Integer pageTo,
            @PositiveOrZero Integer offset,
            @Min(1) @Max(100) Integer limit,
            @JsonAnySetter Map<String, Object> unexpected
    ) {
        public StructureRequest {
            structureType = structureType == null ? "SECTION" : structureType;
            unexpected = rejectUnexpected(unexpected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ContentSearchRequest(
            @NotBlank @Size(max = 64) @JsonProperty("paper_id") String paperId,
            @Size(max = 4000) @JsonProperty("query_text") String queryText,
            @Size(max = 4000) @JsonProperty("section_query") String sectionQuery,
            @Size(max = 20) @JsonProperty("section_refs") List<@NotBlank @Size(max = 96) String> sectionRefs,
            @Size(max = 3) @JsonProperty("element_types")
            List<@NotBlank @Size(max = 16) String> elementTypes,
            @Min(1) @JsonProperty("page_from") Integer pageFrom,
            @Min(1) @JsonProperty("page_to") Integer pageTo,
            @Min(1) @Max(20) @JsonProperty("top_k") Integer topK,
            @JsonAnySetter Map<String, Object> unexpected
    ) {
        public ContentSearchRequest {
            queryText = queryText == null ? "" : queryText;
            sectionQuery = sectionQuery == null ? "" : sectionQuery;
            sectionRefs = sectionRefs == null ? List.of() : List.copyOf(sectionRefs);
            elementTypes = elementTypes == null ? List.of() : List.copyOf(elementTypes);
            unexpected = rejectUnexpected(unexpected);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ContentReadRequest(
            @NotBlank @Size(max = 64) @JsonProperty("paper_id") String paperId,
            @Size(min = 1, max = 20) @JsonProperty("location_refs")
            List<@NotBlank @Size(max = 96) String> locationRefs,
            @JsonAnySetter Map<String, Object> unexpected
    ) {
        public ContentReadRequest {
            locationRefs = locationRefs == null ? List.of() : List.copyOf(locationRefs);
            unexpected = rejectUnexpected(unexpected);
        }
    }
}
