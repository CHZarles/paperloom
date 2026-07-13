package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaperReadingElementSearchService {

    private final PaperReadingModelRepository modelRepository;
    private final PaperReadingElementRepository readingElementRepository;
    private final PaperLocationRepository locationRepository;

    public PaperReadingElementSearchService(PaperReadingModelRepository modelRepository,
                                            PaperReadingElementRepository readingElementRepository,
                                            PaperLocationRepository locationRepository) {
        this.modelRepository = modelRepository;
        this.readingElementRepository = readingElementRepository;
        this.locationRepository = locationRepository;
    }

    @Transactional(readOnly = true)
    public List<PaperReadingElementSearchResult> searchCurrentModel(String paperId, String queryText) {
        return modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                .map(model -> search(paperId, model.getModelVersion(), queryText))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<PaperReadingElementSearchResult> search(String paperId, String modelVersion, String queryText) {
        if (isBlank(paperId) || isBlank(modelVersion) || isBlank(queryText)) {
            return List.of();
        }
        List<PaperReadingElement> matches = readingElementRepository
                .findByPaperIdAndModelVersionAndSearchableTextContainingOrderByPageNumberAscReadingOrderAscIdAsc(
                        paperId,
                        modelVersion,
                        queryText
                );
        List<PaperLocation> locations = locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                paperId,
                modelVersion
        );
        List<PaperReadingElement> allElements = readingElementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, modelVersion);
        Map<String, PaperReadingElement> elementsByReadingId = allElements.stream()
                .filter(element -> !isBlank(element.getReadingElementId()))
                .collect(Collectors.toMap(PaperReadingElement::getReadingElementId, Function.identity(), (left, right) -> left));
        Map<String, PaperLocation> locationsByRef = locations.stream()
                .filter(location -> !isBlank(location.getLocationRef()))
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<Integer, PaperLocation> pageLocationsByPage = locations.stream()
                .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                .filter(location -> location.getPageNumber() != null)
                .collect(Collectors.toMap(PaperLocation::getPageNumber, Function.identity(), (left, right) -> left));

        return matches.stream()
                .map(element -> route(element, elementsByReadingId, locationsByRef, pageLocationsByPage))
                .toList();
    }

    private PaperReadingElementSearchResult route(PaperReadingElement element,
                                                  Map<String, PaperReadingElement> elementsByReadingId,
                                                  Map<String, PaperLocation> locationsByRef,
                                                  Map<Integer, PaperLocation> pageLocationsByPage) {
        PaperLocation ownLocation = locationFor(element.getLocationRef(), locationsByRef);
        if (ownLocation != null) {
            return new PaperReadingElementSearchResult(
                    element,
                    ownLocation.getLocationRef(),
                    ownLocation.getLocationType(),
                    "OWN_LOCATION"
            );
        }

        PaperReadingElement parent = isBlank(element.getParentReadingElementId())
                ? null
                : elementsByReadingId.get(element.getParentReadingElementId());
        PaperLocation parentLocation = parent == null ? null : locationFor(parent.getLocationRef(), locationsByRef);
        if (parentLocation != null) {
            return new PaperReadingElementSearchResult(
                    element,
                    parentLocation.getLocationRef(),
                    parentLocation.getLocationType(),
                    "PARENT_LOCATION"
            );
        }

        PaperLocation pageLocation = element.getPageNumber() == null ? null : pageLocationsByPage.get(element.getPageNumber());
        if (pageLocation != null) {
            return new PaperReadingElementSearchResult(
                    element,
                    pageLocation.getLocationRef(),
                    pageLocation.getLocationType(),
                    "PAGE_LOCATION"
            );
        }

        return new PaperReadingElementSearchResult(element, null, null, "UNRESOLVED");
    }

    private PaperLocation locationFor(String locationRef, Map<String, PaperLocation> locationsByRef) {
        if (isBlank(locationRef)) {
            return null;
        }
        return locationsByRef.get(locationRef);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
