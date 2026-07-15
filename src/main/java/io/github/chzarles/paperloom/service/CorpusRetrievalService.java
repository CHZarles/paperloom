package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.client.EmbeddingClient;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CorpusRetrievalService {

    private static final int MAX_PAPER_LIMIT = 100;
    private static final int MAX_LOCATION_LIMIT = 20;
    private static final int RRF_K = 60;

    private final PaperService paperService;
    private final PaperReadingModelRepository modelRepository;
    private final PaperLocationRepository locationRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final CanonicalReadingLocationService canonicalReadService;

    public CorpusRetrievalService(PaperService paperService,
                                  PaperReadingModelRepository modelRepository,
                                  PaperLocationRepository locationRepository,
                                  EmbeddingClient embeddingClient,
                                  QdrantClient qdrantClient,
                                  CanonicalReadingLocationService canonicalReadService) {
        this.paperService = paperService;
        this.modelRepository = modelRepository;
        this.locationRepository = locationRepository;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.canonicalReadService = canonicalReadService;
    }

    @Transactional(readOnly = true)
    public PaperSearchResult searchPapers(PaperSearchQuery query) {
        List<Paper> papers = authorizedPapers(query.userId(), query.scopePaperIds());
        Set<String> scope = normalizedSet(query.scopePaperIds());
        Set<String> requestedIds = normalizedSet(query.paperIds());
        if (!requestedIds.isEmpty() && !scope.containsAll(requestedIds)) {
            throw new IllegalArgumentException("paper_ids must be a subset of scope_paper_ids");
        }
        Set<String> authors = normalizedSet(query.authors());
        Set<String> venues = normalizedSet(query.venues());
        List<ScoredPaper> matches = new ArrayList<>();
        for (Paper paper : papers) {
            if (!requestedIds.isEmpty() && !requestedIds.contains(paper.getPaperId())) {
                continue;
            }
            if (!authors.isEmpty() && authors.stream().noneMatch(author -> normalize(paper.getAuthors()).contains(author))) {
                continue;
            }
            if (!venues.isEmpty() && !venues.contains(normalize(paper.getVenue()))) {
                continue;
            }
            Integer year = paper.getPublicationYear();
            if (query.yearFrom() != null && (year == null || year < query.yearFrom())) {
                continue;
            }
            if (query.yearTo() != null && (year == null || year > query.yearTo())) {
                continue;
            }
            double score = paperScore(query.queryText(), paper);
            if (!SearchText.tokens(query.queryText()).isEmpty() && score <= 0) {
                continue;
            }
            matches.add(new ScoredPaper(score, paper));
        }
        matches.sort(Comparator
                .comparingDouble(ScoredPaper::score).reversed()
                .thenComparing((ScoredPaper item) -> item.paper().getPublicationYear(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.paper().getPaperId()));
        int offset = Math.max(0, query.offset());
        int limit = Math.max(1, Math.min(query.limit(), MAX_PAPER_LIMIT));
        List<PaperCard> cards = matches.stream().skip(offset).limit(limit).map(item -> card(item.paper())).toList();
        int returnedThrough = offset + cards.size();
        return new PaperSearchResult(
                query.queryText(),
                cards,
                matches.size(),
                cards.size(),
                returnedThrough >= matches.size() ? "complete" : "truncated",
                returnedThrough < matches.size() ? returnedThrough : null
        );
    }

    @Transactional(readOnly = true)
    public IdentitySearchResult findPapersByIdentity(IdentitySearchQuery query) {
        if (query.hints() == null || query.hints().isEmpty()) {
            return new IdentitySearchResult("not_found", List.of(), "identity_hints_required");
        }
        List<PaperCard> matches = authorizedPapers(query.userId(), query.scopePaperIds()).stream()
                .filter(paper -> matchesIdentity(paper, query.hints()))
                .sorted(Comparator.comparing(Paper::getPaperId))
                .map(this::card)
                .toList();
        String status = matches.size() == 1 ? "resolved" : matches.isEmpty() ? "not_found" : "ambiguous";
        return new IdentitySearchResult(status, matches, null);
    }

    public LocationSearchResult searchLocations(LocationSearchQuery query) {
        Set<String> scope = normalizedSet(query.scopePaperIds());
        Set<String> requested = normalizedSet(query.paperIds());
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("paper_ids is required");
        }
        if (!scope.containsAll(requested)) {
            throw new IllegalArgumentException("paper_ids must be a subset of scope_paper_ids");
        }
        List<String> authorizedIds = authorizedPapers(query.userId(), query.scopePaperIds()).stream()
                .map(Paper::getPaperId)
                .filter(requested::contains)
                .distinct()
                .toList();
        if (authorizedIds.size() != requested.size()) {
            throw new IllegalArgumentException("paper_ids contains an unavailable paper");
        }
        String retrievalQuery = String.join(" ", List.of(safe(query.queryText()), safe(query.sectionQuery()))).trim();
        if (retrievalQuery.isBlank()) {
            return new LocationSearchResult(List.of(), 0, 0, qdrantClient.indexVersion());
        }

        float[] dense = embeddingClient.embed(
                List.of(retrievalQuery),
                String.valueOf(query.userId()),
                EmbeddingClient.UsageType.QUERY,
                Duration.ofSeconds(5)
        ).stream().findFirst().orElseThrow(() -> new IllegalStateException("Query embedding was empty"));
        qdrantClient.ensureCollection(dense.length);
        QdrantSparseVector sparse = ReadingModelQdrantIndexService.sparseVector(retrievalQuery);
        int topK = Math.max(1, Math.min(query.topK(), MAX_LOCATION_LIMIT));
        int candidateLimit = Math.min(100, Math.max(40, topK * 4));
        Map<String, Object> filter = qdrantClient.filter(
                authorizedIds,
                normalizeElementTypes(query.elementTypes()),
                query.pageFrom(),
                query.pageTo()
        );
        List<QdrantSearchHit> denseHits = qdrantClient.searchDense(dense, filter, candidateLimit);
        List<QdrantSearchHit> sparseHits = sparse.indices().isEmpty()
                ? List.of()
                : qdrantClient.searchSparse(sparse, filter, candidateLimit);

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        addHits(fused, denseHits, true);
        addHits(fused, sparseHits, false);
        List<FusedHit> ranked = fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedHit::fusedScore).reversed()
                        .thenComparing(FusedHit::locationRef))
                .toList();
        Map<String, PaperLocation> locationsByRef = locationRepository.findByLocationRefIn(
                        ranked.stream().map(FusedHit::locationRef).toList())
                .stream()
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<String, PaperReadingModel> currentModels = currentModels(authorizedIds);

        List<FusedHit> valid = ranked.stream()
                .filter(hit -> validCurrentHit(hit, locationsByRef.get(hit.locationRef()), currentModels, requested))
                .toList();
        List<FusedHit> selected = selectPaperCoverage(valid, authorizedIds, topK);
        CanonicalReadingLocationService.ReadBatch hydrated = canonicalReadService.read(
                selected.stream().map(FusedHit::locationRef).toList(),
                authorizedIds
        );
        Map<String, CanonicalReadingLocationService.CanonicalLocation> contentByRef = hydrated.items().stream()
                .collect(Collectors.toMap(
                        CanonicalReadingLocationService.CanonicalLocation::locationRef,
                        Function.identity(),
                        (left, right) -> left
                ));
        List<LocationCandidate> candidates = new ArrayList<>();
        for (FusedHit hit : selected) {
            CanonicalReadingLocationService.CanonicalLocation content = contentByRef.get(hit.locationRef());
            if (content == null) {
                continue;
            }
            String elementType = stringPayload(hit.payload(), "element_type", content.elementType());
            candidates.add(new LocationCandidate(
                    content.paperId(),
                    content.title(),
                    content.paperVersion(),
                    content.locationRef(),
                    content.section(),
                    content.page(),
                    elementType,
                    SearchText.preview(content.spanText(), SearchText.tokens(retrievalQuery), 500),
                    hit.denseScore(),
                    hit.sparseScore(),
                    hit.fusedScore()
            ));
        }
        return new LocationSearchResult(
                candidates,
                valid.size(),
                candidates.size(),
                qdrantClient.indexVersion()
        );
    }

    @Transactional(readOnly = true)
    public CanonicalReadingLocationService.ReadBatch readLocations(LocationReadQuery query) {
        List<String> locationRefs = query.locationRefs().stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (locationRefs.size() > MAX_LOCATION_LIMIT) {
            throw new IllegalArgumentException("location_refs cannot contain more than " + MAX_LOCATION_LIMIT + " items");
        }
        List<String> authorizedIds = authorizedPapers(query.userId(), query.scopePaperIds()).stream()
                .map(Paper::getPaperId)
                .distinct()
                .toList();
        return canonicalReadService.read(locationRefs, authorizedIds);
    }

    private List<Paper> authorizedPapers(Long userId, List<String> scopePaperIds) {
        if (userId == null) {
            throw new IllegalArgumentException("user_id is required");
        }
        Set<String> scope = normalizedSet(scopePaperIds);
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("scope_paper_ids is required");
        }
        Map<String, Paper> accessible = paperService.getAccessiblePapers(String.valueOf(userId), null).stream()
                .filter(paper -> paper != null && !safe(paper.getPaperId()).isBlank())
                .sorted(Comparator.comparing(Paper::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toMap(Paper::getPaperId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, PaperReadingModel> currentModels = currentModels(new ArrayList<>(scope));
        return scope.stream()
                .filter(accessible::containsKey)
                .filter(currentModels::containsKey)
                .map(accessible::get)
                .toList();
    }

    private Map<String, PaperReadingModel> currentModels(List<String> paperIds) {
        Map<String, PaperReadingModel> models = new LinkedHashMap<>();
        for (String paperId : paperIds) {
            modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                    .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                    .ifPresent(model -> models.put(paperId, model));
        }
        return models;
    }

    private boolean validCurrentHit(FusedHit hit,
                                    PaperLocation location,
                                    Map<String, PaperReadingModel> currentModels,
                                    Set<String> requested) {
        if (location == null || !requested.contains(location.getPaperId())) {
            return false;
        }
        PaperReadingModel current = currentModels.get(location.getPaperId());
        if (current == null || !current.getModelVersion().equals(location.getModelVersion())) {
            return false;
        }
        return current.getModelVersion().equals(stringPayload(hit.payload(), "model_version", ""))
                && location.getPaperId().equals(stringPayload(hit.payload(), "paper_id", ""));
    }

    private void addHits(Map<String, FusedHit> fused, List<QdrantSearchHit> hits, boolean dense) {
        for (int index = 0; index < hits.size(); index++) {
            QdrantSearchHit hit = hits.get(index);
            String locationRef = stringPayload(hit.payload(), "location_ref", "");
            if (locationRef.isBlank()) {
                continue;
            }
            FusedHit current = fused.getOrDefault(locationRef, FusedHit.empty(locationRef, hit.payload()));
            double contribution = 1.0 / (RRF_K + index + 1.0);
            fused.put(locationRef, dense
                    ? current.withDense(hit.score(), contribution)
                    : current.withSparse(hit.score(), contribution));
        }
    }

    private List<FusedHit> selectPaperCoverage(List<FusedHit> ranked, List<String> paperOrder, int topK) {
        List<FusedHit> selected = new ArrayList<>();
        Set<String> selectedRefs = new LinkedHashSet<>();
        for (String paperId : paperOrder) {
            ranked.stream()
                    .filter(hit -> paperId.equals(stringPayload(hit.payload(), "paper_id", "")))
                    .filter(hit -> !selectedRefs.contains(hit.locationRef()))
                    .findFirst()
                    .ifPresent(hit -> {
                        if (selected.size() < topK) {
                            selected.add(hit);
                            selectedRefs.add(hit.locationRef());
                        }
                    });
        }
        for (FusedHit hit : ranked) {
            if (selected.size() >= topK) {
                break;
            }
            if (selectedRefs.add(hit.locationRef())) {
                selected.add(hit);
            }
        }
        selected.sort(Comparator.comparingDouble(FusedHit::fusedScore).reversed()
                .thenComparing(FusedHit::locationRef));
        return selected;
    }

    private boolean matchesIdentity(Paper paper, Map<String, Object> hints) {
        for (Map.Entry<String, Object> entry : hints.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            if ("authors".equals(key)) {
                List<?> requested = value instanceof List<?> list ? list : List.of(value);
                if (requested.stream().map(Object::toString).map(this::normalize)
                        .noneMatch(author -> normalize(paper.getAuthors()).contains(author))) {
                    return false;
                }
                continue;
            }
            String actual = switch (key) {
                case "paper_id" -> paper.getPaperId();
                case "title" -> paper.getPaperTitle();
                case "filename" -> paper.getOriginalFilename();
                case "doi" -> paper.getDoi();
                case "arxiv_id" -> paper.getArxivId();
                case "year" -> paper.getPublicationYear() == null ? "" : paper.getPublicationYear().toString();
                default -> "";
            };
            if (!normalize(actual).equals(normalize(value.toString()))) {
                return false;
            }
        }
        return true;
    }

    private double paperScore(String query, Paper paper) {
        List<String> tokens = SearchText.tokens(query);
        if (tokens.isEmpty()) {
            return 1.0;
        }
        Set<String> titleTokens = new LinkedHashSet<>(SearchText.tokens(paper.getPaperTitle()));
        Set<String> abstractTokens = new LinkedHashSet<>(SearchText.tokens(paper.getAbstractText()));
        Set<String> metadataTokens = new LinkedHashSet<>(SearchText.tokens(String.join(" ", List.of(
                safe(paper.getPaperId()), safe(paper.getAuthors()), safe(paper.getVenue()),
                paper.getPublicationYear() == null ? "" : paper.getPublicationYear().toString(),
                safe(paper.getDoi()), safe(paper.getArxivId()), safe(paper.getOriginalFilename())
        ))));
        double score = 3.0 * overlap(tokens, titleTokens) + overlap(tokens, abstractTokens) + overlap(tokens, metadataTokens);
        String normalizedQuery = normalize(query);
        String haystack = normalize(String.join(" ", List.of(
                safe(paper.getPaperTitle()), safe(paper.getAbstractText()), safe(paper.getAuthors()),
                safe(paper.getVenue()), safe(paper.getDoi()), safe(paper.getArxivId()), safe(paper.getOriginalFilename())
        )));
        return !normalizedQuery.isBlank() && haystack.contains(normalizedQuery) ? score + 2.0 : score;
    }

    private double overlap(List<String> queryTokens, Set<String> valueTokens) {
        long matched = queryTokens.stream().filter(valueTokens::contains).count();
        return queryTokens.isEmpty() ? 0 : (double) matched / queryTokens.size();
    }

    private PaperCard card(Paper paper) {
        return new PaperCard(
                paper.getPaperId(),
                firstNonBlank(paper.getPaperTitle(), paper.getOriginalFilename(), paper.getPaperId()),
                splitAuthors(paper.getAuthors()),
                paper.getPublicationYear(),
                paper.getVenue(),
                paper.getDoi(),
                paper.getArxivId(),
                paper.getOriginalFilename(),
                safe(paper.getAbstractText()).substring(0, Math.min(safe(paper.getAbstractText()).length(), 500))
        );
    }

    private List<String> splitAuthors(String authors) {
        if (safe(authors).isBlank()) {
            return List.of();
        }
        return List.of(authors.split("\\s*(?:;|\\n|\\|)\\s*")).stream()
                .filter(author -> !author.isBlank())
                .toList();
    }

    private List<String> normalizeElementTypes(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private Set<String> normalizedSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String stringPayload(Map<String, Object> payload, String key, String fallback) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? fallback : value.toString();
    }

    private String normalize(String value) {
        return SearchText.normalize(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record PaperSearchQuery(Long userId, List<String> scopePaperIds, String queryText,
                                   List<String> paperIds, List<String> authors, List<String> venues,
                                   Integer yearFrom, Integer yearTo, int offset, int limit) {
        public PaperSearchQuery {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            queryText = queryText == null ? "" : queryText.trim();
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            authors = authors == null ? List.of() : List.copyOf(authors);
            venues = venues == null ? List.of() : List.copyOf(venues);
        }
    }

    public record IdentitySearchQuery(Long userId, List<String> scopePaperIds, Map<String, Object> hints) {
        public IdentitySearchQuery {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            hints = hints == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(hints));
        }
    }

    public record LocationSearchQuery(Long userId, List<String> scopePaperIds, List<String> paperIds,
                                      String queryText, String sectionQuery, List<String> elementTypes,
                                      Integer pageFrom, Integer pageTo, int topK) {
        public LocationSearchQuery {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            paperIds = paperIds == null ? List.of() : List.copyOf(paperIds);
            queryText = queryText == null ? "" : queryText.trim();
            sectionQuery = sectionQuery == null ? "" : sectionQuery.trim();
            elementTypes = elementTypes == null ? List.of() : List.copyOf(elementTypes);
        }
    }

    public record LocationReadQuery(Long userId, List<String> scopePaperIds, List<String> locationRefs) {
        public LocationReadQuery {
            scopePaperIds = scopePaperIds == null ? List.of() : List.copyOf(scopePaperIds);
            locationRefs = locationRefs == null ? List.of() : List.copyOf(locationRefs);
        }
    }

    public record PaperCard(String paperId, String title, List<String> authors, Integer year,
                            String venue, String doi, String arxivId, String filename, String preview) {
    }

    public record PaperSearchResult(String queryText, List<PaperCard> candidates, int matchedCount,
                                    int returnedCount, String coverage, Integer nextOffset) {
    }

    public record IdentitySearchResult(String status, List<PaperCard> matches, String reason) {
    }

    public record LocationCandidate(String paperId, String title, String paperVersion, String locationRef,
                                    String section, Integer page, String elementType, String preview,
                                    double denseScore, double sparseScore, double fusedScore) {
    }

    public record LocationSearchResult(List<LocationCandidate> locations, int matchedCount,
                                       int returnedCount, String indexVersion) {
    }

    private record ScoredPaper(double score, Paper paper) {
    }

    private record FusedHit(String locationRef, Map<String, Object> payload,
                            double denseScore, double sparseScore, double fusedScore) {
        static FusedHit empty(String locationRef, Map<String, Object> payload) {
            return new FusedHit(locationRef, payload == null ? Map.of() : payload, 0, 0, 0);
        }

        FusedHit withDense(double score, double contribution) {
            return new FusedHit(locationRef, payload, score, sparseScore, fusedScore + contribution);
        }

        FusedHit withSparse(double score, double contribution) {
            return new FusedHit(locationRef, payload, denseScore, score, fusedScore + contribution);
        }
    }
}
