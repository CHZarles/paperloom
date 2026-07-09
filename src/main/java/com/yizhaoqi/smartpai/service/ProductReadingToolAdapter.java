package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductReadingToolAdapter {

    private static final String SESSION_TOOL_NAME = "get_session_state";
    private static final String LIST_PAPERS_TOOL_NAME = "list_papers";
    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String IDENTITY_TOOL_NAME = "find_papers_by_identity";
    private static final String GET_OUTLINE_TOOL_NAME = "get_paper_outline";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_TOOL_NAME = "read_locations";
    private static final String TRACE_TOOL_NAME = "trace_source_quotes";

    private final PaperCandidateSearchService paperCandidateSearchService;
    private final ReadingModelGrepSearchService readingModelGrepSearchService;
    private final PaperService paperService;
    private final PaperRepository paperRepository;
    private final PaperReadingModelRepository modelRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperLocationRepository locationRepository;
    private final ProductPaperHandleService handleService;
    private final ReadingToolOutputMapper outputMapper;
    private final ProductReadingLocationReadService readService;
    private final ProductReadingSourceQuoteTraceService traceService;

    public ProductReadingToolAdapter(PaperCandidateSearchService paperCandidateSearchService,
                                     ReadingModelGrepSearchService readingModelGrepSearchService,
                                     PaperService paperService,
                                     PaperRepository paperRepository,
                                     PaperReadingModelRepository modelRepository,
                                     PaperSectionRepository sectionRepository,
                                     PaperLocationRepository locationRepository,
                                     ProductPaperHandleService handleService,
                                     ReadingToolOutputMapper outputMapper,
                                     ProductReadingLocationReadService readService,
                                     ProductReadingSourceQuoteTraceService traceService) {
        this.paperCandidateSearchService = paperCandidateSearchService;
        this.readingModelGrepSearchService = readingModelGrepSearchService;
        this.paperService = paperService;
        this.paperRepository = paperRepository;
        this.modelRepository = modelRepository;
        this.sectionRepository = sectionRepository;
        this.locationRepository = locationRepository;
        this.handleService = handleService;
        this.outputMapper = outputMapper;
        this.readService = readService;
        this.traceService = traceService;
    }

    @Transactional(readOnly = true)
    public ProductToolResult getSessionState(ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<Paper> readyPapers = readyScopedPapers(safeContext);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "OK");
        data.put("searchScope", outputMapper.sessionSearchScope(
                safeContext.lockedScope().mode(),
                searchScopeLabel(safeContext.lockedScope()),
                readyPapers.size()
        ));
        data.put("constraints", sessionStateConstraints());
        return new ProductToolResult(SESSION_TOOL_NAME, true, data, ProductToolEffect.PRODUCT_STATE);
    }

    @Transactional(readOnly = true)
    public ProductToolResult listPapers(ReadingToolArgumentValidator.ListPaperFilters filters,
                                        boolean includeFacets,
                                        ReadingToolArgumentValidator.ListPaperSort sort,
                                        ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        ReadingToolArgumentValidator.ListPaperFilters safeFilters = filters == null
                ? ReadingToolArgumentValidator.ListPaperFilters.empty()
                : filters;
        ReadingToolArgumentValidator.ListPaperSort safeSort = sort == null
                ? ReadingToolArgumentValidator.ListPaperSort.RECENT
                : sort;
        List<Paper> filtered = readyScopedPapers(safeContext).stream()
                .filter(paper -> matchesListPaperFilters(paper, safeFilters))
                .toList();
        List<Paper> sorted = sortPapers(filtered, safeSort);
        int returned = Math.min(sorted.size(), PaperCandidateSearchRequest.DEFAULT_PAPER_LIMIT);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < returned; index++) {
            Paper paper = sorted.get(index);
            Optional<Map<String, Object>> card = validatedPaperCard(
                    paper,
                    index + 1,
                    listPaperMatchReasons(safeFilters),
                    null
            );
            if (card.isEmpty()) {
                return paperCardIdentityInvalid(LIST_PAPERS_TOOL_NAME, ProductToolEffect.PAPER_LIST);
            }
            items.add(card.get());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", items.isEmpty() ? "NO_MATCH" : "OK");
        data.put("total", filtered.size());
        data.put("returned", items.size());
        data.put("items", items);
        data.put("facets", includeFacets ? outputMapper.paperBrowseFacets(facets(filtered)) : Map.of());
        data.put("constraints", listPapersConstraints());
        return new ProductToolResult(LIST_PAPERS_TOOL_NAME, true, data, ProductToolEffect.PAPER_LIST);
    }

    @Transactional(readOnly = true)
    public ProductToolResult findPapersByIdentity(ReadingToolArgumentValidator.IdentityHints hints,
                                                 ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        ReadingToolArgumentValidator.IdentityHints safeHints = hints == null
                ? ReadingToolArgumentValidator.IdentityHints.empty()
                : hints;
        if (!safeHints.hasTextualOrExternalHint()) {
            return invalidArgument(IDENTITY_TOOL_NAME, "identityHints");
        }

        List<IdentityMatch> sorted = readyScopedPapers(safeContext).stream()
                .filter(paper -> matchesIdentityHints(paper, safeHints))
                .map(paper -> new IdentityMatch(paper, identityMatchReasons(paper, safeHints)))
                .sorted(identityMatchComparator())
                .toList();
        int returned = Math.min(sorted.size(), PaperCandidateSearchRequest.DEFAULT_PAPER_LIMIT);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (int index = 0; index < returned; index++) {
            IdentityMatch match = sorted.get(index);
            Paper paper = match.paper();
            Optional<Map<String, Object>> card = validatedIdentityPaperCard(paper, index + 1, match.matchReasons(), safeHints);
            if (card.isEmpty()) {
                return paperCardIdentityInvalid(IDENTITY_TOOL_NAME, ProductToolEffect.PAPER_RESOLUTION);
            }
            matches.add(card.get());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", identityStatus(sorted.size()));
        data.put("ambiguous", sorted.size() > 1);
        data.put("total", sorted.size());
        data.put("returned", matches.size());
        data.put("matches", matches);
        data.put("constraints", identityConstraints());
        return new ProductToolResult(IDENTITY_TOOL_NAME, true, data, ProductToolEffect.PAPER_RESOLUTION);
    }

    @Transactional
    public ProductToolResult readLocations(List<String> locationRefs, ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<String> safeLocationRefs = sanitizeLocationRefs(locationRefs);
        if (safeLocationRefs.isEmpty()) {
            return invalidArgument(READ_TOOL_NAME, "locationRefs");
        }
        ProductReadingLocationReadService.ReadResult result = readService.readLocations(safeLocationRefs, safeContext);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceQuotes", result.sourceQuotes());
        data.put("readStatus", result.readStatus());
        return new ProductToolResult(READ_TOOL_NAME, true, data, ProductToolEffect.EVIDENCE);
    }

    @Transactional(readOnly = true)
    public ProductToolResult traceSourceQuotes(List<String> sourceQuoteRefs, ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<String> safeSourceQuoteRefs = sanitizeSourceQuoteRefs(sourceQuoteRefs);
        if (safeSourceQuoteRefs.isEmpty()) {
            return invalidArgument(TRACE_TOOL_NAME, "sourceQuoteRefs");
        }
        ProductReadingSourceQuoteTraceService.TraceResult result =
                traceService.traceSourceQuotes(safeSourceQuoteRefs, safeContext);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceQuotes", result.sourceQuotes());
        data.put("traceStatus", result.traceStatus());
        return new ProductToolResult(TRACE_TOOL_NAME, true, data, ProductToolEffect.EVIDENCE);
    }

    @Transactional(readOnly = true)
    public ProductToolResult searchPaperCandidates(String queryText, ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        if (SearchText.tokens(queryText).isEmpty()) {
            return invalidArgument(SEARCH_TOOL_NAME, "queryText");
        }

        List<PaperCandidate> rawCandidates = paperCandidateSearchService.search(new PaperCandidateSearchRequest(
                queryText,
                userId(safeContext.userId()),
                null,
                PaperCandidateSearchRequest.DEFAULT_PAPER_LIMIT
        ));
        List<Map<String, Object>> items = new ArrayList<>();
        int ordinal = 1;
        for (PaperCandidate candidate : rawCandidates) {
            if (!handleService.isPaperVisibleToUser(candidate.paperId(), safeContext.userId(), safeContext.lockedScope())) {
                continue;
            }
            if (!handleService.hasCurrentReadyReadingModel(candidate.paperId())) {
                continue;
            }
            Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(candidate.paperId());
            if (paper.isEmpty()) {
                return paperCardIdentityInvalid(SEARCH_TOOL_NAME, ProductToolEffect.PAPER_DISCOVERY);
            }
            Optional<Map<String, Object>> card = validatedPaperCard(
                    paper.get(),
                    ordinal,
                    paperCandidateMatchReasons(candidate),
                    candidate.abstractPreview()
            );
            if (card.isEmpty()) {
                return paperCardIdentityInvalid(SEARCH_TOOL_NAME, ProductToolEffect.PAPER_DISCOVERY);
            }
            items.add(card.get());
            ordinal += 1;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", items.isEmpty() ? "NO_MATCH" : "OK");
        data.put("items", items);
        data.put("constraints", paperCandidateConstraints());
        return new ProductToolResult(SEARCH_TOOL_NAME, true, data, ProductToolEffect.PAPER_DISCOVERY);
    }

    @Transactional(readOnly = true)
    public ProductToolResult getPaperOutline(List<String> paperHandles, ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<String> safePaperHandles = sanitizePaperHandles(paperHandles);
        if (safePaperHandles.isEmpty()) {
            return invalidArgument(GET_OUTLINE_TOOL_NAME, "paperHandles");
        }

        List<Map<String, Object>> papers = new ArrayList<>();
        boolean anySectionOutline = false;
        for (String paperHandle : safePaperHandles) {
            Optional<String> paperId = handleService.resolvePaperHandle(paperHandle);
            if (paperId.isEmpty()
                    || !handleService.isPaperVisibleToUser(paperId.get(), safeContext.userId(), safeContext.lockedScope())) {
                return outlineStatus(safePaperHandles, "PAPER_HANDLE_UNAVAILABLE", List.of());
            }
            if (!handleService.hasCurrentReadyReadingModel(paperId.get())) {
                return outlineStatus(safePaperHandles, "READING_MODEL_UNAVAILABLE", List.of());
            }
            Optional<PaperReadingModel> currentModel = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId.get())
                    .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY);
            if (currentModel.isEmpty()) {
                return outlineStatus(safePaperHandles, "READING_MODEL_UNAVAILABLE", List.of());
            }
            Optional<Paper> paper = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId.get());
            if (paper.isEmpty()) {
                return outlineStatus(safePaperHandles, "PAPER_HANDLE_UNAVAILABLE", List.of());
            }

            String modelVersion = currentModel.get().getModelVersion();
            List<PaperSection> currentSections =
                    sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(
                            paperId.get(),
                            modelVersion
                    );
            List<PaperLocation> currentLocations =
                    locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                            paperId.get(),
                            modelVersion
                    );
            List<Map<String, Object>> sectionCards = sectionOutlineCards(currentSections, currentLocations);
            boolean hasSectionOutline = !sectionCards.isEmpty();
            anySectionOutline = anySectionOutline || hasSectionOutline;
            papers.add(outputMapper.paperOutline(
                    paperHandle,
                    paper.get(),
                    currentModel.get(),
                    supportedLocationTypes(currentLocations),
                    parserQuality(currentModel.get(), hasSectionOutline),
                    sectionCards
            ));
        }

        return outlineStatus(
                safePaperHandles,
                anySectionOutline ? "OK" : "OUTLINE_UNAVAILABLE",
                papers
        );
    }

    @Transactional(readOnly = true)
    public ProductToolResult listPaperLocations(List<String> paperHandles,
                                                ReadingToolArgumentValidator.PageRange pageRange,
                                                List<PaperLocationType> locationTypes,
                                                ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<String> safePaperHandles = sanitizePaperHandles(paperHandles);
        List<PaperLocationType> safeLocationTypes = locationTypes == null ? List.of() : List.copyOf(locationTypes);
        if (safePaperHandles.isEmpty()) {
            return invalidArgument(LIST_LOCATIONS_TOOL_NAME, "paperHandles");
        }

        Map<String, String> paperIdByHandle = new LinkedHashMap<>();
        Map<String, PaperReadingModel> modelByHandle = new LinkedHashMap<>();
        for (String paperHandle : safePaperHandles) {
            Optional<String> paperId = handleService.resolvePaperHandle(paperHandle);
            if (paperId.isEmpty()
                    || !handleService.isPaperVisibleToUser(paperId.get(), safeContext.userId(), safeContext.lockedScope())
                    || !handleService.hasCurrentReadyReadingModel(paperId.get())) {
                return listLocationStatus(safePaperHandles, "CURRENT_LOCATION_NOT_FOUND", List.of(), Map.of());
            }
            Optional<PaperReadingModel> currentModel = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId.get())
                    .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY);
            if (currentModel.isEmpty()) {
                return listLocationStatus(safePaperHandles, "CURRENT_LOCATION_NOT_FOUND", List.of(), Map.of());
            }
            if (pageRangeOutsideModel(pageRange, currentModel.get())) {
                return invalidArgument(LIST_LOCATIONS_TOOL_NAME, "pageRange");
            }
            paperIdByHandle.put(paperHandle, paperId.get());
            modelByHandle.put(paperHandle, currentModel.get());
        }

        Map<String, List<String>> supportedLocationTypesByHandle = new LinkedHashMap<>();
        List<Map<String, Object>> locations = new ArrayList<>();
        int ordinal = 1;
        for (String paperHandle : safePaperHandles) {
            String paperId = paperIdByHandle.get(paperHandle);
            PaperReadingModel model = modelByHandle.get(paperHandle);
            List<PaperLocation> currentLocations =
                    locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                            paperId,
                            model.getModelVersion()
                    );
            supportedLocationTypesByHandle.put(paperHandle, supportedLocationTypes(currentLocations));
            for (PaperLocation location : currentLocations == null ? List.<PaperLocation>of() : currentLocations) {
                if (!matchesLocationType(location, safeLocationTypes) || !matchesPageRange(location, pageRange)) {
                    continue;
                }
                if (SearchText.isBlank(location.getLocationRef())) {
                    continue;
                }
                locations.add(outputMapper.listedLocationCard(location, paperHandle, ordinal++));
            }
        }

        return listLocationStatus(
                safePaperHandles,
                locations.isEmpty() ? "CURRENT_LOCATION_NOT_FOUND" : "OK",
                locations,
                supportedLocationTypesByHandle
        );
    }

    @Transactional(readOnly = true)
    public ProductToolResult findReadingLocations(List<String> paperHandles,
                                                  String queryText,
                                                  List<PaperLocationType> locationTypes,
                                                  ProductToolContext context) {
        ProductToolContext safeContext = safeContext(context);
        List<String> safePaperHandles = sanitizePaperHandles(paperHandles);
        List<PaperLocationType> safeLocationTypes = locationTypes == null ? List.of() : List.copyOf(locationTypes);
        if (safePaperHandles.isEmpty()) {
            return invalidArgument(LOCATION_TOOL_NAME, "paperHandles");
        }
        if (SearchText.tokens(queryText).isEmpty()) {
            return invalidArgument(LOCATION_TOOL_NAME, "queryText");
        }

        Map<String, String> paperIdByHandle = new LinkedHashMap<>();
        Map<String, String> handleByPaperId = new LinkedHashMap<>();
        for (String paperHandle : safePaperHandles) {
            Optional<String> paperId = handleService.resolvePaperHandle(paperHandle);
            if (paperId.isEmpty()) {
                return locationStatus("PAPER_HANDLE_UNAVAILABLE", List.of(), Map.of());
            }
            if (!handleService.isPaperVisibleToUser(paperId.get(), safeContext.userId(), safeContext.lockedScope())) {
                return locationStatus("PAPER_HANDLE_UNAVAILABLE", List.of(), Map.of());
            }
            if (!handleService.hasCurrentReadyReadingModel(paperId.get())) {
                return locationStatus("READING_MODEL_UNAVAILABLE", List.of(), Map.of());
            }
            paperIdByHandle.put(paperHandle, paperId.get());
            handleByPaperId.put(paperId.get(), paperHandle);
        }

        Map<String, List<String>> supportedLocationTypesByHandle = supportedLocationTypesByHandle(paperIdByHandle);
        if (supportedLocationTypesByHandle.isEmpty()) {
            return locationStatus("READING_MODEL_UNAVAILABLE", List.of(), Map.of());
        }
        if (!safeLocationTypes.isEmpty() && noSupportedRequestedLocationTypes(supportedLocationTypesByHandle, safeLocationTypes)) {
            return locationStatus("NO_MATCHING_LOCATION_TYPE", List.of(), supportedLocationTypesByHandle);
        }

        List<String> paperIds = new ArrayList<>(paperIdByHandle.values());
        List<ReadingLocationCandidate> rawCandidates = readingModelGrepSearchService.search(new ReadingModelGrepSearchRequest(
                paperIds,
                queryText,
                safeLocationTypes,
                null,
                null,
                ReadingModelGrepSearchRequest.DEFAULT_LOCATION_LIMIT
        ));
        List<Map<String, Object>> candidates = new ArrayList<>();
        int ordinal = 1;
        for (ReadingLocationCandidate candidate : rawCandidates) {
            String paperHandle = handleByPaperId.get(candidate.paperId());
            if (paperHandle == null || SearchText.isBlank(candidate.locationRef())) {
                continue;
            }
            candidates.add(outputMapper.locationCard(candidate, paperHandle, ordinal++));
        }

        return locationStatus(
                candidates.isEmpty() ? "NO_MATCH" : "OK",
                candidates,
                supportedLocationTypesByHandle
        );
    }

    private boolean pageRangeOutsideModel(ReadingToolArgumentValidator.PageRange pageRange,
                                          PaperReadingModel model) {
        if (pageRange == null || model == null || model.getPageCount() == null || model.getPageCount() < 1) {
            return false;
        }
        return pageRange.from() > model.getPageCount() || pageRange.to() > model.getPageCount();
    }

    private boolean matchesLocationType(PaperLocation location, List<PaperLocationType> locationTypes) {
        if (locationTypes == null || locationTypes.isEmpty()) {
            return true;
        }
        return location != null && locationTypes.contains(location.getLocationType());
    }

    private boolean matchesPageRange(PaperLocation location, ReadingToolArgumentValidator.PageRange pageRange) {
        if (pageRange == null) {
            return true;
        }
        if (location == null || location.getPageNumber() == null) {
            return false;
        }
        int start = location.getPageNumber();
        int end = location.getPageEndNumber() == null ? start : location.getPageEndNumber();
        return start <= pageRange.to() && end >= pageRange.from();
    }

    private List<String> supportedLocationTypes(List<PaperLocation> locations) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (PaperLocation location : locations == null ? List.<PaperLocation>of() : locations) {
            if (location != null && location.getLocationType() != null) {
                types.add(location.getLocationType().name());
            }
        }
        return List.copyOf(types);
    }

    private Map<String, List<String>> supportedLocationTypesByHandle(Map<String, String> paperIdByHandle) {
        Map<String, List<String>> supported = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : paperIdByHandle.entrySet()) {
            Optional<PaperReadingModel> model = modelRepository.findFirstByPaperIdAndIsCurrentTrue(entry.getValue())
                    .filter(current -> current.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY);
            if (model.isEmpty()) {
                return Map.of();
            }
            List<PaperLocation> locations = locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                    entry.getValue(),
                    model.get().getModelVersion()
            );
            supported.put(entry.getKey(), supportedLocationTypes(locations));
        }
        return supported;
    }

    private boolean noSupportedRequestedLocationTypes(Map<String, List<String>> supportedTypesByHandle,
                                                     List<PaperLocationType> requestedTypes) {
        LinkedHashSet<String> requestedTypeNames = new LinkedHashSet<>();
        for (PaperLocationType requestedType : requestedTypes) {
            if (requestedType != null) {
                requestedTypeNames.add(requestedType.name());
            }
        }
        if (requestedTypeNames.isEmpty()) {
            return false;
        }
        return supportedTypesByHandle.values().stream()
                .flatMap(List::stream)
                .noneMatch(requestedTypeNames::contains);
    }

    private List<Paper> readyScopedPapers(ProductToolContext context) {
        if (paperService == null) {
            return List.of();
        }
        ProductToolContext safeContext = safeContext(context);
        List<Paper> accessible = paperService.getAccessiblePapers(userId(safeContext.userId()), null);
        LinkedHashSet<String> requested = new LinkedHashSet<>(safeContext.lockedScope().paperIds());
        Map<String, Paper> byPaperId = new LinkedHashMap<>();
        for (Paper paper : accessible == null ? List.<Paper>of() : accessible) {
            if (paper == null || SearchText.isBlank(paper.getPaperId())) {
                continue;
            }
            String paperId = paper.getPaperId().trim();
            if (!requested.isEmpty() && !requested.contains(paperId)) {
                continue;
            }
            if (modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                    .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                    .isEmpty()) {
                continue;
            }
            byPaperId.putIfAbsent(paperId, paper);
        }
        return List.copyOf(byPaperId.values());
    }

    private boolean matchesListPaperFilters(Paper paper, ReadingToolArgumentValidator.ListPaperFilters filters) {
        return matchesContains(displayTitle(paper), filters.titleContains())
                && matchesExact(displayTitle(paper), filters.titleExact())
                && matchesContains(paper.getOriginalFilename(), filters.filenameContains())
                && matchesExact(paper.getOriginalFilename(), filters.filenameExact())
                && matchesAuthor(paper, filters.authorName())
                && matchesExact(paper.getDoi(), filters.doiExact())
                && matchesExact(paper.getArxivId(), filters.arxivIdExact())
                && matchesYearRange(paper.getPublicationYear(), filters.yearRange())
                && matchesContains(paper.getVenue(), filters.venue());
    }

    private Optional<Map<String, Object>> validatedPaperCard(Paper paper,
                                                            int ordinal,
                                                            List<String> matchReasons,
                                                            String preview) {
        if (paper == null || SearchText.isBlank(paper.getPaperId())) {
            return Optional.empty();
        }
        String paperHandle = handleService.handleForPaperId(paper.getPaperId());
        if (!paperHandleResolvesToPaper(paperHandle, paper.getPaperId())) {
            return Optional.empty();
        }
        Map<String, Object> card = preview == null
                ? outputMapper.browsedPaperCard(paper, paperHandle, ordinal, authors(paper), matchReasons)
                : outputMapper.searchPaperCard(paper, paperHandle, ordinal, authors(paper), matchReasons, preview);
        return canonicalPaperCard(card, paper, paperHandle) ? Optional.of(card) : Optional.empty();
    }

    private Optional<Map<String, Object>> validatedIdentityPaperCard(Paper paper,
                                                                    int ordinal,
                                                                    List<String> identityReasonCodes,
                                                                    ReadingToolArgumentValidator.IdentityHints hints) {
        return validatedPaperCard(
                paper,
                ordinal,
                identityMatchReasonLabels(identityReasonCodes, hints),
                null
        );
    }

    private boolean paperHandleResolvesToPaper(String paperHandle, String paperId) {
        if (SearchText.isBlank(paperHandle) || SearchText.isBlank(paperId)) {
            return false;
        }
        return handleService.resolvePaperHandle(paperHandle)
                .filter(resolvedPaperId -> paperId.equals(resolvedPaperId))
                .isPresent();
    }

    private boolean canonicalPaperCard(Map<String, Object> card, Paper paper, String paperHandle) {
        if (card == null || paper == null) {
            return false;
        }
        String paperId = stringValue(card.get("paperId"));
        String title = stringValue(card.get("title"));
        String filename = stringValue(card.get("originalFilename"));
        return paper.getPaperId().equals(paperId)
                && paperHandle.equals(stringValue(card.get("paperHandle")))
                && title.equals(stringValue(paper.getPaperTitle()))
                && filename.equals(stringValue(paper.getOriginalFilename()))
                && !stringList(card.get("matchReasons")).isEmpty();
    }

    private List<String> listPaperMatchReasons(ReadingToolArgumentValidator.ListPaperFilters filters) {
        ReadingToolArgumentValidator.ListPaperFilters safeFilters = filters == null
                ? ReadingToolArgumentValidator.ListPaperFilters.empty()
                : filters;
        List<String> reasons = new ArrayList<>();
        if (!SearchText.isBlank(safeFilters.titleExact())) {
            reasons.add("Title exactly matches \"" + safeFilters.titleExact() + "\".");
        }
        if (!SearchText.isBlank(safeFilters.titleContains())) {
            reasons.add("Title contains \"" + safeFilters.titleContains() + "\".");
        }
        if (!SearchText.isBlank(safeFilters.filenameExact())) {
            reasons.add("Filename exactly matches \"" + safeFilters.filenameExact() + "\".");
        }
        if (!SearchText.isBlank(safeFilters.filenameContains())) {
            reasons.add("Filename contains \"" + safeFilters.filenameContains() + "\".");
        }
        if (!SearchText.isBlank(safeFilters.authorName())) {
            reasons.add("Author metadata matches \"" + safeFilters.authorName() + "\".");
        }
        if (!SearchText.isBlank(safeFilters.doiExact())) {
            reasons.add("DOI exactly matches the requested identifier.");
        }
        if (!SearchText.isBlank(safeFilters.arxivIdExact())) {
            reasons.add("arXiv id exactly matches the requested identifier.");
        }
        if (safeFilters.yearRange() != null) {
            reasons.add("Publication year is within the requested range.");
        }
        if (!SearchText.isBlank(safeFilters.venue())) {
            reasons.add("Venue metadata contains \"" + safeFilters.venue() + "\".");
        }
        if (reasons.isEmpty()) {
            reasons.add("Readable paper in the current locked scope.");
        }
        return List.copyOf(reasons);
    }

    private List<String> paperCandidateMatchReasons(PaperCandidate candidate) {
        List<String> reasons = new ArrayList<>();
        String matchReason = candidate == null ? "" : stringValue(candidate.matchReason());
        if (!matchReason.isBlank()) {
            reasons.add(matchReason);
        }
        if (candidate != null && !candidate.matchedFields().isEmpty()) {
            reasons.add("Matched paper metadata fields: " + String.join(", ", candidate.matchedFields()) + ".");
        }
        if (reasons.isEmpty()) {
            reasons.add("Paper candidate search matched metadata in the current locked scope.");
        }
        return List.copyOf(reasons);
    }

    private List<String> identityMatchReasonLabels(List<String> reasonCodes,
                                                   ReadingToolArgumentValidator.IdentityHints hints) {
        List<String> reasons = new ArrayList<>();
        for (String reasonCode : reasonCodes == null ? List.<String>of() : reasonCodes) {
            switch (reasonCode) {
                case "TITLE_CONTAINS" -> reasons.add("Title contains \"" + hints.titleContains() + "\".");
                case "TITLE_EXACT" -> reasons.add("Title exactly matches \"" + hints.titleExact() + "\".");
                case "FILENAME_CONTAINS" -> reasons.add("Filename contains \"" + hints.filenameContains() + "\".");
                case "FILENAME_EXACT" -> reasons.add("Filename exactly matches \"" + hints.filenameExact() + "\".");
                case "DOI_EXACT" -> reasons.add("DOI exactly matches the requested identifier.");
                case "ARXIV_ID_EXACT" -> reasons.add("arXiv id exactly matches the requested identifier.");
                case "AUTHOR_NAME" -> reasons.add("Author metadata matches \"" + hints.authorName() + "\".");
                case "YEAR" -> reasons.add("Publication year matches " + hints.year() + ".");
                default -> {
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("Paper identity metadata matched the request.");
        }
        return List.copyOf(reasons);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rawValues)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object rawValue : rawValues) {
            String text = stringValue(rawValue);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private boolean matchesIdentityHints(Paper paper, ReadingToolArgumentValidator.IdentityHints hints) {
        if (!matchesRequiredIdentityExactHints(paper, hints)) {
            return false;
        }
        return !identityMatchReasons(paper, hints).isEmpty();
    }

    private boolean matchesRequiredIdentityExactHints(Paper paper, ReadingToolArgumentValidator.IdentityHints hints) {
        if (!SearchText.isBlank(hints.titleExact()) && !matchesExact(displayTitle(paper), hints.titleExact())) {
            return false;
        }
        if (!SearchText.isBlank(hints.filenameExact()) && !matchesExact(paper.getOriginalFilename(), hints.filenameExact())) {
            return false;
        }
        if (!SearchText.isBlank(hints.doiExact())
                && !SearchText.isBlank(paper.getDoi())
                && !matchesCanonicalDoi(paper.getDoi(), hints.doiExact())) {
            return false;
        }
        return SearchText.isBlank(hints.arxivIdExact())
                || SearchText.isBlank(paper.getArxivId())
                || matchesCanonicalArxivId(paper.getArxivId(), hints.arxivIdExact());
    }

    private List<String> identityMatchReasons(Paper paper, ReadingToolArgumentValidator.IdentityHints hints) {
        List<String> reasons = new ArrayList<>();
        if (!SearchText.isBlank(hints.titleContains()) && matchesContains(displayTitle(paper), hints.titleContains())) {
            reasons.add("TITLE_CONTAINS");
        }
        if (!SearchText.isBlank(hints.titleExact()) && matchesExact(displayTitle(paper), hints.titleExact())) {
            reasons.add("TITLE_EXACT");
        }
        if (!SearchText.isBlank(hints.filenameContains()) && matchesContains(paper.getOriginalFilename(), hints.filenameContains())) {
            reasons.add("FILENAME_CONTAINS");
        }
        if (!SearchText.isBlank(hints.filenameExact()) && matchesExact(paper.getOriginalFilename(), hints.filenameExact())) {
            reasons.add("FILENAME_EXACT");
        }
        if (!SearchText.isBlank(hints.doiExact()) && matchesCanonicalDoi(paper.getDoi(), hints.doiExact())) {
            reasons.add("DOI_EXACT");
        }
        if (!SearchText.isBlank(hints.arxivIdExact()) && matchesCanonicalArxivId(paper.getArxivId(), hints.arxivIdExact())) {
            reasons.add("ARXIV_ID_EXACT");
        }
        if (!SearchText.isBlank(hints.authorName()) && matchesAuthor(paper, hints.authorName())) {
            reasons.add("AUTHOR_NAME");
        }
        if (hints.year() != null && matchesYear(paper.getPublicationYear(), hints.year())) {
            reasons.add("YEAR");
        }
        return reasons;
    }

    private Comparator<IdentityMatch> identityMatchComparator() {
        return Comparator
                .comparingInt((IdentityMatch match) -> identityStrength(match.matchReasons()))
                .thenComparing(match -> displayTitle(match.paper()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.paper().getPublicationYear(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(match -> match.paper().getId(), Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int identityStrength(List<String> matchReasons) {
        int strongest = Integer.MAX_VALUE;
        for (String reason : matchReasons == null ? List.<String>of() : matchReasons) {
            strongest = Math.min(strongest, switch (reason) {
                case "DOI_EXACT" -> 0;
                case "ARXIV_ID_EXACT" -> 1;
                case "FILENAME_EXACT" -> 2;
                case "TITLE_EXACT" -> 3;
                case "FILENAME_CONTAINS" -> 4;
                case "TITLE_CONTAINS" -> 5;
                case "AUTHOR_NAME" -> 6;
                case "YEAR" -> 7;
                default -> 100;
            });
        }
        return strongest;
    }

    private boolean matchesAuthor(Paper paper, String authorName) {
        if (SearchText.isBlank(authorName)) {
            return true;
        }
        String normalizedNeedle = normalized(authorName);
        return authors(paper).stream()
                .map(this::normalized)
                .anyMatch(author -> author.contains(normalizedNeedle));
    }

    private boolean matchesContains(String value, String expectedSubstring) {
        return SearchText.isBlank(expectedSubstring) || normalized(value).contains(normalized(expectedSubstring));
    }

    private boolean matchesExact(String value, String expectedValue) {
        return SearchText.isBlank(expectedValue) || normalized(value).equals(normalized(expectedValue));
    }

    private boolean matchesCanonicalDoi(String value, String expectedValue) {
        return SearchText.isBlank(expectedValue)
                || ReadingToolArgumentValidator.canonicalDoiExact(value)
                .equals(ReadingToolArgumentValidator.canonicalDoiExact(expectedValue));
    }

    private boolean matchesCanonicalArxivId(String value, String expectedValue) {
        return SearchText.isBlank(expectedValue)
                || ReadingToolArgumentValidator.canonicalArxivIdExact(value)
                .equals(ReadingToolArgumentValidator.canonicalArxivIdExact(expectedValue));
    }

    private boolean matchesYear(Integer year, Integer expectedYear) {
        return expectedYear == null || expectedYear.equals(year);
    }

    private boolean matchesYearRange(Integer year, ReadingToolArgumentValidator.YearRange yearRange) {
        if (yearRange == null) {
            return true;
        }
        return year != null && year >= yearRange.from() && year <= yearRange.to();
    }

    private List<Paper> sortPapers(List<Paper> papers, ReadingToolArgumentValidator.ListPaperSort sort) {
        Comparator<Paper> comparator = switch (sort) {
            case TITLE -> Comparator
                    .comparing(this::displayTitle, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(paper -> stringValue(paper.getOriginalFilename()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Paper::getPublicationYear, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Paper::getId, Comparator.nullsLast(Comparator.naturalOrder()));
            case YEAR -> Comparator
                    .comparing(Paper::getPublicationYear, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(this::displayTitle, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Paper::getId, Comparator.nullsLast(Comparator.naturalOrder()));
            case RECENT -> Comparator
                    .comparing(Paper::getMergedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Paper::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(this::displayTitle, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Paper::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return (papers == null ? List.<Paper>of() : papers).stream()
                .sorted(comparator)
                .toList();
    }

    private Map<String, List<Map<String, Object>>> facets(List<Paper> papers) {
        Map<String, List<Map<String, Object>>> facets = new LinkedHashMap<>();
        facets.put("years", yearBuckets(papers));
        facets.put("authors", stringBuckets((papers == null ? List.<Paper>of() : papers).stream()
                .flatMap(paper -> authors(paper).stream())
                .toList()));
        facets.put("venues", stringBuckets((papers == null ? List.<Paper>of() : papers).stream()
                .map(Paper::getVenue)
                .toList()));
        facets.put("catalogTopics", List.of());
        facets.put("paperTypes", List.of());
        return facets;
    }

    private List<Map<String, Object>> yearBuckets(List<Paper> papers) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Paper paper : papers == null ? List.<Paper>of() : papers) {
            if (paper.getPublicationYear() != null) {
                counts.merge(paper.getPublicationYear(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed())
                .map(entry -> outputMapper.facetBucket(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> stringBuckets(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values == null ? List.<String>of() : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                counts.merge(normalized, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> outputMapper.facetBucket(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<String> authors(Paper paper) {
        if (paper == null || SearchText.isBlank(paper.getAuthors())) {
            return List.of();
        }
        LinkedHashSet<String> authors = new LinkedHashSet<>();
        for (String author : paper.getAuthors().split("[,;]")) {
            String safeAuthor = stringValue(author);
            if (!safeAuthor.isBlank()) {
                authors.add(safeAuthor);
            }
        }
        return List.copyOf(authors);
    }

    private String displayTitle(Paper paper) {
        return paper == null ? "" : stringValue(paper.getPaperTitle());
    }

    private String searchScopeLabel(SourceScope scope) {
        SourceScope safeScope = scope == null ? SourceScope.auto() : scope;
        return switch (safeScope.mode()) {
            case MANUAL_SOURCE -> "Selected readable papers";
            case REFERENCE_SOURCE -> "Referenced readable papers";
            case AUTO_SOURCE -> "All readable papers";
        };
    }

    private String normalized(String value) {
        return stringValue(value).toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> sectionOutlineCards(List<PaperSection> sections, List<PaperLocation> locations) {
        Map<String, PaperLocation> sectionLocationBySectionId = new LinkedHashMap<>();
        for (PaperLocation location : locations == null ? List.<PaperLocation>of() : locations) {
            if (location == null
                    || location.getLocationType() != PaperLocationType.SECTION
                    || SearchText.isBlank(location.getSourceObjectId())
                    || SearchText.isBlank(location.getLocationRef())) {
                continue;
            }
            sectionLocationBySectionId.putIfAbsent(location.getSourceObjectId(), location);
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        for (PaperSection section : sections == null ? List.<PaperSection>of() : sections) {
            if (section == null || SearchText.isBlank(section.getSectionId())) {
                continue;
            }
            PaperLocation sectionLocation = sectionLocationBySectionId.get(section.getSectionId());
            if (sectionLocation != null) {
                cards.add(outputMapper.sectionOutlineCard(section, sectionLocation));
            }
        }
        return cards;
    }

    private Map<String, Object> parserQuality(PaperReadingModel model, boolean hasSectionOutline) {
        Map<String, Object> quality = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        double pageTextCoverage = 0.0;
        Integer pageCount = model == null ? null : model.getPageCount();
        Integer readablePageCount = model == null ? null : model.getReadablePageCount();
        if (pageCount == null || pageCount < 1 || readablePageCount == null || readablePageCount < 0) {
            warnings.add("PAGE_COVERAGE_UNKNOWN");
        } else {
            pageTextCoverage = Math.max(0.0, Math.min(1.0, (double) readablePageCount / pageCount));
            if (pageTextCoverage < 1.0) {
                warnings.add("PARTIAL_PAGE_TEXT_COVERAGE");
            }
        }
        if (!hasSectionOutline) {
            warnings.add("NO_SECTION_OUTLINE");
        }
        quality.put("pageTextCoverage", pageTextCoverage);
        quality.put("outlineConfidence", hasSectionOutline ? "HIGH" : "LOW");
        quality.put("warnings", warnings);
        return quality;
    }

    private ProductToolResult locationStatus(String status,
                                             List<Map<String, Object>> candidates,
                                             Map<String, List<String>> supportedLocationTypesByHandle) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("candidates", candidates == null ? List.of() : candidates);
        data.put("supportedLocationTypesByPaper", supportedLocationTypesByHandle == null
                ? Map.of()
                : supportedLocationTypesByHandle);
        data.put("constraints", locationConstraints(status));
        return new ProductToolResult(LOCATION_TOOL_NAME, true, data, ProductToolEffect.PAPER_DISCOVERY);
    }

    private ProductToolResult listLocationStatus(List<String> paperHandles,
                                                 String status,
                                                 List<Map<String, Object>> locations,
                                                 Map<String, List<String>> supportedLocationTypesByHandle) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperHandles", paperHandles == null ? List.of() : paperHandles);
        data.put("status", status);
        data.put("supportedLocationTypesByPaper", supportedLocationTypesByHandle == null
                ? Map.of()
                : supportedLocationTypesByHandle);
        data.put("locations", locations == null ? List.of() : locations);
        data.put("constraints", locationConstraints(status));
        return new ProductToolResult(LIST_LOCATIONS_TOOL_NAME, true, data, ProductToolEffect.PAPER_DISCOVERY);
    }

    private ProductToolResult outlineStatus(List<String> paperHandles,
                                            String status,
                                            List<Map<String, Object>> papers) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperHandles", paperHandles == null ? List.of() : paperHandles);
        data.put("status", status);
        data.put("papers", papers == null ? List.of() : papers);
        data.put("constraints", outlineConstraints());
        return new ProductToolResult(GET_OUTLINE_TOOL_NAME, true, data, ProductToolEffect.PAPER_DISCOVERY);
    }

    private ProductToolResult paperCardIdentityInvalid(String toolName, ProductToolEffect effect) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "PAPER_CARD_IDENTITY_INVALID");
        data.put("error", "paper_card_identity_invalid");
        if (LIST_PAPERS_TOOL_NAME.equals(toolName) || SEARCH_TOOL_NAME.equals(toolName)) {
            data.put("total", 0);
            data.put("returned", 0);
            data.put("items", List.of());
        }
        if (IDENTITY_TOOL_NAME.equals(toolName)) {
            data.put("ambiguous", false);
            data.put("total", 0);
            data.put("returned", 0);
            data.put("matches", List.of());
        }
        data.put("constraints", switch (toolName) {
            case LIST_PAPERS_TOOL_NAME -> listPapersConstraints();
            case IDENTITY_TOOL_NAME -> identityConstraints();
            default -> paperCandidateConstraints();
        });
        return new ProductToolResult(toolName, false, data, ProductToolEffect.ERROR);
    }

    private Map<String, Object> sessionStateConstraints() {
        return Map.of(
                "stateIsSourceQuote", false,
                "paperContentClaimsAllowed", false
        );
    }

    private Map<String, Object> listPapersConstraints() {
        return Map.of(
                "paperCardIsSourceQuote", false,
                "paperContentClaimsAllowed", false
        );
    }

    private Map<String, Object> paperCandidateConstraints() {
        return Map.of(
                "previewIsSourceQuote", false,
                "paperContentClaimsAllowed", false
        );
    }

    private String identityStatus(int total) {
        if (total == 0) {
            return "NO_MATCH";
        }
        return total == 1 ? "OK" : "AMBIGUOUS";
    }

    private Map<String, Object> identityConstraints() {
        return Map.of(
                "paperCardIsSourceQuote", false,
                "paperContentClaimsAllowed", false,
                "ambiguousMatchesAuthorizeReading", false
        );
    }

    private Map<String, Object> locationConstraints(String status) {
        if ("OK".equals(status)) {
            return Map.of(
                    "previewIsSourceQuote", false,
                    "locationRefIsSourceQuote", false,
                    "paperContentClaimsAllowed", false
            );
        }
        return Map.of(
                "previewIsSourceQuote", false,
                "locationRefIsSourceQuote", false,
                "paperContentClaimsAllowed", false,
                "paperContentAbsenceClaimAllowed", false
        );
    }

    private Map<String, Object> outlineConstraints() {
        return Map.of(
                "outlineIsSourceQuote", false,
                "paperContentClaimsAllowed", false
        );
    }

    private ProductToolResult invalidArgument(String toolName, String argument) {
        return new ProductToolResult(
                toolName,
                false,
                Map.of("status", "INVALID_ARGUMENT", "error", "invalid_argument", "argument", argument),
                ProductToolEffect.ERROR
        );
    }

    private ProductToolContext safeContext(ProductToolContext context) {
        return context == null ? new ProductToolContext(null, "", "", SourceScope.auto()) : context;
    }

    private List<String> sanitizePaperHandles(List<String> paperHandles) {
        if (paperHandles == null) {
            return List.of();
        }
        return paperHandles.stream()
                .filter(handle -> handle != null && !handle.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> sanitizeLocationRefs(List<String> locationRefs) {
        if (locationRefs == null) {
            return List.of();
        }
        return locationRefs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> sanitizeSourceQuoteRefs(List<String> sourceQuoteRefs) {
        if (sourceQuoteRefs == null) {
            return List.of();
        }
        return sourceQuoteRefs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String userId(Long userId) {
        return userId == null ? "" : String.valueOf(userId);
    }

    private record IdentityMatch(Paper paper, List<String> matchReasons) {
    }
}
