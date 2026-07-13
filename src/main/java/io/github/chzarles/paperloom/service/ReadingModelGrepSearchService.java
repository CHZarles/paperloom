package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReadingModelGrepSearchService {

    private static final int RANK_ELEMENT_OWN_LOCATION = 10;
    private static final int RANK_ELEMENT_PARENT_LOCATION = 20;
    private static final int RANK_ELEMENT_PAGE_LOCATION = 25;
    private static final int RANK_SECTION = 30;
    private static final int RANK_PAGE = 40;
    private static final int PREVIEW_LENGTH = 180;

    private final PaperReadingModelRepository modelRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperPageRepository pageRepository;
    private final PaperSectionRepository sectionRepository;

    public ReadingModelGrepSearchService(PaperReadingModelRepository modelRepository,
                                         PaperReadingElementRepository elementRepository,
                                         PaperLocationRepository locationRepository,
                                         PaperPageRepository pageRepository,
                                         PaperSectionRepository sectionRepository) {
        this.modelRepository = modelRepository;
        this.elementRepository = elementRepository;
        this.locationRepository = locationRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
    }

    @Transactional(readOnly = true)
    public List<ReadingLocationCandidate> search(ReadingModelGrepSearchRequest request) {
        if (request == null || request.paperIds().isEmpty()) {
            return List.of();
        }
        List<String> tokens = SearchText.tokens(request.queryText());
        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<String, RankedLocationCandidate> candidatesByLocation = new LinkedHashMap<>();
        for (String paperId : request.paperIds()) {
            Optional<PaperReadingModel> model = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId);
            if (model.isEmpty()) {
                continue;
            }
            ReadingModelData data = loadModelData(paperId, model.get().getModelVersion());
            collectElementHits(tokens, data, candidatesByLocation, request);
            collectSectionHits(tokens, data, candidatesByLocation, request);
            collectPageHits(tokens, data, candidatesByLocation, request);
        }

        return candidatesByLocation.values().stream()
                .sorted(Comparator
                        .comparingInt(RankedLocationCandidate::rank)
                        .thenComparing(candidate -> nullLast(candidate.candidate().pageNumber()))
                        .thenComparing(candidate -> candidate.candidate().locationRef()))
                .limit(request.limit())
                .map(RankedLocationCandidate::candidate)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasCurrentModel(String paperId) {
        if (SearchText.isBlank(paperId)) {
            return false;
        }
        return modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId).isPresent();
    }

    private ReadingModelData loadModelData(String paperId, String modelVersion) {
        List<PaperReadingElement> elements = elementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, modelVersion);
        List<PaperSection> sections = sectionRepository
                .findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(paperId, modelVersion);
        List<PaperPage> pages = pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc(paperId, modelVersion);
        List<PaperLocation> locations = locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                paperId,
                modelVersion
        );

        Map<String, PaperLocation> locationsByRef = locations.stream()
                .filter(location -> !SearchText.isBlank(location.getLocationRef()))
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<Integer, PaperLocation> pageLocationsByPage = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .filter(location -> location.getPageNumber() != null)
                .collect(Collectors.toMap(PaperLocation::getPageNumber, Function.identity(), (left, right) -> left));
        Map<String, PaperLocation> sectionLocationsBySectionId = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.SECTION)
                .filter(location -> !SearchText.isBlank(location.getSourceObjectId()))
                .collect(Collectors.toMap(PaperLocation::getSourceObjectId, Function.identity(), (left, right) -> left));
        Map<String, PaperReadingElement> elementsByReadingId = elements.stream()
                .filter(element -> !SearchText.isBlank(element.getReadingElementId()))
                .collect(Collectors.toMap(PaperReadingElement::getReadingElementId, Function.identity(), (left, right) -> left));

        return new ReadingModelData(
                paperId,
                modelVersion,
                elements,
                sections,
                pages,
                locationsByRef,
                pageLocationsByPage,
                sectionLocationsBySectionId,
                elementsByReadingId
        );
    }

    private void collectElementHits(List<String> tokens,
                                    ReadingModelData data,
                                    Map<String, RankedLocationCandidate> candidatesByLocation,
                                    ReadingModelGrepSearchRequest request) {
        for (PaperReadingElement element : data.elements()) {
            List<SearchText.FieldText> fields = List.of(
                    new SearchText.FieldText("searchableText", element.getSearchableText()),
                    new SearchText.FieldText("captionText", element.getCaptionText()),
                    new SearchText.FieldText("bodyText", element.getBodyText())
            );
            List<String> matchedFields = SearchText.matchedFields(tokens, fields);
            if (matchedFields.isEmpty()) {
                continue;
            }

            RoutedLocation routedLocation = routeElement(element, data);
            if (routedLocation == null || !passesFilters(routedLocation.location(), request)) {
                continue;
            }
            PaperLocation location = routedLocation.location();
            ReadingLocationCandidate candidate = new ReadingLocationCandidate(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    location.getLocationType(),
                    location.getPageNumber(),
                    location.getPageEndNumber(),
                    firstText(location.getSectionTitle(), element.getSectionTitle()),
                    element.getReadingElementId(),
                    SearchText.preview(SearchText.firstMatchedText(tokens, fields), tokens, PREVIEW_LENGTH),
                    "ELEMENT",
                    routedLocation.routingSource(),
                    matchedFields,
                    SearchText.isBlank(element.getReadingElementId()) ? List.of() : List.of(element.getReadingElementId())
            );
            keepBest(candidatesByLocation, candidate, routedLocation.rank());
        }
    }

    private void collectSectionHits(List<String> tokens,
                                    ReadingModelData data,
                                    Map<String, RankedLocationCandidate> candidatesByLocation,
                                    ReadingModelGrepSearchRequest request) {
        for (PaperSection section : data.sections()) {
            List<SearchText.FieldText> fields = List.of(
                    new SearchText.FieldText("sectionTitle", section.getSectionTitle()),
                    new SearchText.FieldText("sectionText", section.getSectionText())
            );
            List<String> matchedFields = SearchText.matchedFields(tokens, fields);
            if (matchedFields.isEmpty()) {
                continue;
            }

            RoutedLocation routedLocation = routeSection(section, data);
            if (routedLocation == null || !passesFilters(routedLocation.location(), request)) {
                continue;
            }
            PaperLocation location = routedLocation.location();
            ReadingLocationCandidate candidate = new ReadingLocationCandidate(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    location.getLocationType(),
                    location.getPageNumber(),
                    location.getPageEndNumber(),
                    firstText(location.getSectionTitle(), section.getSectionTitle()),
                    null,
                    SearchText.preview(SearchText.firstMatchedText(tokens, fields), tokens, PREVIEW_LENGTH),
                    "SECTION",
                    routedLocation.routingSource(),
                    matchedFields,
                    List.of()
            );
            keepBest(candidatesByLocation, candidate, routedLocation.rank());
        }
    }

    private void collectPageHits(List<String> tokens,
                                 ReadingModelData data,
                                 Map<String, RankedLocationCandidate> candidatesByLocation,
                                 ReadingModelGrepSearchRequest request) {
        for (PaperPage page : data.pages()) {
            List<SearchText.FieldText> fields = List.of(new SearchText.FieldText("pageText", page.getPageText()));
            List<String> matchedFields = SearchText.matchedFields(tokens, fields);
            if (matchedFields.isEmpty()) {
                continue;
            }

            PaperLocation location = data.pageLocationsByPage().get(page.getPageNumber());
            if (location == null || !passesFilters(location, request)) {
                continue;
            }
            ReadingLocationCandidate candidate = new ReadingLocationCandidate(
                    location.getPaperId(),
                    location.getModelVersion(),
                    location.getLocationRef(),
                    location.getLocationType(),
                    location.getPageNumber(),
                    location.getPageEndNumber(),
                    location.getSectionTitle(),
                    null,
                    SearchText.preview(page.getPageText(), tokens, PREVIEW_LENGTH),
                    "PAGE",
                    "PAGE_LOCATION",
                    matchedFields,
                    List.of()
            );
            keepBest(candidatesByLocation, candidate, RANK_PAGE);
        }
    }

    private RoutedLocation routeElement(PaperReadingElement element, ReadingModelData data) {
        PaperLocation ownLocation = locationForRef(element.getLocationRef(), data.locationsByRef());
        if (ownLocation != null) {
            return new RoutedLocation(ownLocation, "OWN_LOCATION", RANK_ELEMENT_OWN_LOCATION);
        }

        PaperReadingElement parent = SearchText.isBlank(element.getParentReadingElementId())
                ? null
                : data.elementsByReadingId().get(element.getParentReadingElementId());
        PaperLocation parentLocation = parent == null ? null : locationForRef(parent.getLocationRef(), data.locationsByRef());
        if (parentLocation != null) {
            return new RoutedLocation(parentLocation, "PARENT_LOCATION", RANK_ELEMENT_PARENT_LOCATION);
        }

        PaperLocation pageLocation = element.getPageNumber() == null
                ? null
                : data.pageLocationsByPage().get(element.getPageNumber());
        if (pageLocation != null) {
            return new RoutedLocation(pageLocation, "PAGE_LOCATION", RANK_ELEMENT_PAGE_LOCATION);
        }
        return null;
    }

    private RoutedLocation routeSection(PaperSection section, ReadingModelData data) {
        PaperLocation sectionLocation = data.sectionLocationsBySectionId().get(section.getSectionId());
        if (sectionLocation != null) {
            return new RoutedLocation(sectionLocation, "SECTION_LOCATION", RANK_SECTION);
        }

        PaperLocation pageLocation = section.getPageNumberFrom() == null
                ? null
                : data.pageLocationsByPage().get(section.getPageNumberFrom());
        if (pageLocation != null) {
            return new RoutedLocation(pageLocation, "PAGE_LOCATION", RANK_SECTION);
        }
        return null;
    }

    private PaperLocation locationForRef(String locationRef, Map<String, PaperLocation> locationsByRef) {
        if (SearchText.isBlank(locationRef)) {
            return null;
        }
        return locationsByRef.get(locationRef);
    }

    private boolean passesFilters(PaperLocation location, ReadingModelGrepSearchRequest request) {
        if (SearchText.isBlank(location.getLocationRef())) {
            return false;
        }
        if (!request.locationTypes().isEmpty() && !request.locationTypes().contains(location.getLocationType())) {
            return false;
        }
        Integer pageNumber = location.getPageNumber();
        Integer pageEndNumber = location.getPageEndNumber() == null ? pageNumber : location.getPageEndNumber();
        if (request.pageFrom() != null && pageEndNumber != null && pageEndNumber < request.pageFrom()) {
            return false;
        }
        return request.pageTo() == null || pageNumber == null || pageNumber <= request.pageTo();
    }

    private void keepBest(Map<String, RankedLocationCandidate> candidatesByLocation,
                          ReadingLocationCandidate candidate,
                          int rank) {
        String key = candidate.paperId() + "|" + candidate.modelVersion() + "|" + candidate.locationRef();
        RankedLocationCandidate existing = candidatesByLocation.get(key);
        if (existing == null || rank < existing.rank()) {
            candidatesByLocation.put(key, new RankedLocationCandidate(candidate, rank));
        }
    }

    private String firstText(String first, String second) {
        return SearchText.isBlank(first) ? second : first;
    }

    private int nullLast(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    private record ReadingModelData(
            String paperId,
            String modelVersion,
            List<PaperReadingElement> elements,
            List<PaperSection> sections,
            List<PaperPage> pages,
            Map<String, PaperLocation> locationsByRef,
            Map<Integer, PaperLocation> pageLocationsByPage,
            Map<String, PaperLocation> sectionLocationsBySectionId,
            Map<String, PaperReadingElement> elementsByReadingId
    ) {
    }

    private record RoutedLocation(PaperLocation location, String routingSource, int rank) {
    }

    private record RankedLocationCandidate(ReadingLocationCandidate candidate, int rank) {
    }
}
