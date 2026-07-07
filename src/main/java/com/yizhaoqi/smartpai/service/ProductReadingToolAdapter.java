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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProductReadingToolAdapter {

    private static final String SEARCH_TOOL_NAME = "search_paper_candidates";
    private static final String GET_OUTLINE_TOOL_NAME = "get_paper_outline";
    private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
    private static final String LOCATION_TOOL_NAME = "find_reading_locations";
    private static final String READ_TOOL_NAME = "read_locations";
    private static final String TRACE_TOOL_NAME = "trace_source_quotes";

    private final PaperCandidateSearchService paperCandidateSearchService;
    private final ReadingModelGrepSearchService readingModelGrepSearchService;
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
        this.paperRepository = paperRepository;
        this.modelRepository = modelRepository;
        this.sectionRepository = sectionRepository;
        this.locationRepository = locationRepository;
        this.handleService = handleService;
        this.outputMapper = outputMapper;
        this.readService = readService;
        this.traceService = traceService;
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
            String paperHandle = handleService.handleForPaperId(candidate.paperId());
            items.add(outputMapper.paperCard(candidate, paperHandle, ordinal++));
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

    private Map<String, Object> paperCandidateConstraints() {
        return Map.of(
                "previewIsSourceQuote", false,
                "paperContentClaimsAllowed", false
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
}
