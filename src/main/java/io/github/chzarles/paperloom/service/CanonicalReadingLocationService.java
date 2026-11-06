package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperPassageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import static io.github.chzarles.paperloom.utils.TextUtils.firstNonBlank;

@Service
public class CanonicalReadingLocationService {

    private final PaperLocationRepository locationRepository;
    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperPassageRepository passageRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperRepository paperRepository;
    private final PaperVisualAssetRepository visualAssetRepository;
    public CanonicalReadingLocationService(PaperLocationRepository locationRepository,
                                           PaperReadingModelRepository modelRepository,
                                           PaperPageRepository pageRepository,
                                           PaperSectionRepository sectionRepository,
                                           PaperPassageRepository passageRepository,
                                           PaperReadingElementRepository elementRepository,
                                           PaperRepository paperRepository,
                                           PaperVisualAssetRepository visualAssetRepository) {
        this.locationRepository = locationRepository;
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
        this.passageRepository = passageRepository;
        this.elementRepository = elementRepository;
        this.paperRepository = paperRepository;
        this.visualAssetRepository = visualAssetRepository;
    }

    @Transactional(readOnly = true)
    public ReadBatch read(List<String> locationRefs, List<String> authorizedPaperIds) {
        List<String> refs = locationRefs == null ? List.of() : locationRefs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
        if (refs.isEmpty()) {
            return new ReadBatch(List.of(), List.of());
        }
        Set<String> authorized = authorizedPaperIds == null ? Set.of() : Set.copyOf(authorizedPaperIds);
        Map<String, PaperLocation> locations = locationRepository.findByLocationRefIn(refs).stream()
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, PaperReadingModel> currentModels = currentModels(locations.values().stream()
                .map(PaperLocation::getPaperId)
                .distinct()
                .toList());
        Map<String, String> titles = paperTitles(locations.values().stream()
                .map(PaperLocation::getPaperId)
                .distinct()
                .toList());
        CanonicalEntities entities = loadCanonicalEntities(locations.values().stream()
                .filter(location -> authorized.contains(location.getPaperId()))
                .filter(location -> {
                    PaperReadingModel current = currentModels.get(location.getPaperId());
                    return current != null && location.getModelVersion().equals(current.getModelVersion());
                })
                .toList());
        List<CanonicalLocation> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String ref : refs) {
            PaperLocation location = locations.get(ref);
            if (location == null || !authorized.contains(location.getPaperId())) {
                missing.add(ref);
                continue;
            }
            PaperReadingModel current = currentModels.get(location.getPaperId());
            if (current == null || !location.getModelVersion().equals(current.getModelVersion())) {
                missing.add(ref);
                continue;
            }
            CanonicalLocation item = resolve(location, current,
                    titles.getOrDefault(location.getPaperId(), location.getPaperId()), entities);
            if (item == null || item.spanText().isBlank()) {
                missing.add(ref);
                continue;
            }
            items.add(item);
        }
        return new ReadBatch(items, missing);
    }

    private CanonicalLocation resolve(PaperLocation location,
                                      PaperReadingModel model,
                                      String paperTitle,
                                      CanonicalEntities entities) {
        ObjectKey objectKey = new ObjectKey(
                location.getPaperId(), location.getModelVersion(), trim(location.getSourceObjectId()));
        return switch (location.getLocationType()) {
            case PAGE -> resolvePage(location, model, paperTitle, entities.pages().get(new PageKey(
                    location.getPaperId(), location.getModelVersion(), location.getPageNumber())));
            case SECTION -> resolveSection(location, model, paperTitle, entities.sections().get(objectKey));
            case PASSAGE -> resolvePassage(location, model, paperTitle, entities.passages().get(objectKey));
            case TABLE, FIGURE -> resolveElement(location, model, paperTitle, entities.elements().get(objectKey));
        };
    }

    private CanonicalLocation resolvePage(PaperLocation location, PaperReadingModel model,
                                          String title, PaperPage page) {
        if (page == null || !Objects.equals(location.getPageNumber(), page.getPageNumber())
                || blank(page.getPageText()) || !sameSpan(location.getSourceSpanJson(), page.getSourceSpanJson())) {
            return null;
        }
        return item(location, model, title, "page", page.getPageText(), null,
                page.getParserName(), page.getParserVersion(), null);
    }

    private CanonicalLocation resolveSection(PaperLocation location, PaperReadingModel model,
                                             String title, PaperSection section) {
        if (section == null || !Objects.equals(location.getPaperId(), section.getPaperId())
                || !Objects.equals(location.getModelVersion(), section.getModelVersion())
                || !Objects.equals(location.getSourceObjectId(), section.getSectionId())
                || blank(section.getSectionText())
                || !sameSpan(location.getSourceSpanJson(), section.getSourceSpanJson())) {
            return null;
        }
        return item(location, model, title, "section", section.getSectionText(), null,
                section.getParserName(), section.getParserVersion(), section.getSectionId());
    }

    private CanonicalLocation resolvePassage(PaperLocation location, PaperReadingModel model,
                                             String title, PaperPassage passage) {
        if (passage == null || !Objects.equals(location.getPaperId(), passage.getPaperId())
                || !Objects.equals(location.getModelVersion(), passage.getModelVersion())
                || !Objects.equals(location.getSourceObjectId(), passage.getPassageRef())
                || blank(canonicalPassageContent(passage))
                || !sameSpan(location.getSourceSpanJson(), passage.getSourceSpanJson())) {
            return null;
        }
        return item(location, model, title, "passage", canonicalPassageContent(passage),
                location.getSourceSpanJson(), model.getParserName(), model.getParserVersion(), passage.getPassageRef());
    }

    private CanonicalLocation resolveElement(PaperLocation location, PaperReadingModel model,
                                             String title, PaperReadingElement element) {
        if (element == null
                || !Objects.equals(location.getSourceObjectId(), element.getReadingElementId())
                || !Objects.equals(location.getPaperId(), element.getPaperId())
                || !Objects.equals(location.getModelVersion(), element.getModelVersion())
                || !Objects.equals(location.getLocationRef(), element.getLocationRef())
                || location.getLocationType() != element.getLocationType()
                || !validElementType(location.getLocationType(), element.getElementType())
                || blank(location.getSourceSpanJson())
                || blank(canonicalElementContent(element))) {
            return null;
        }
        return item(location, model, title, location.getLocationType().name().toLowerCase(),
                canonicalElementContent(element), location.getSourceSpanJson(), element.getParserName(),
                element.getParserVersion(), element.getReadingElementId());
    }

    private CanonicalEntities loadCanonicalEntities(List<PaperLocation> locations) {
        Map<PageKey, PaperPage> pages = new LinkedHashMap<>();
        Map<ObjectKey, PaperSection> sections = new LinkedHashMap<>();
        Map<ObjectKey, PaperPassage> passages = new LinkedHashMap<>();
        Map<ObjectKey, PaperReadingElement> elements = new LinkedHashMap<>();
        Map<PaperModelKey, List<PaperLocation>> byModel = locations.stream().collect(Collectors.groupingBy(
                location -> new PaperModelKey(location.getPaperId(), location.getModelVersion()),
                LinkedHashMap::new,
                Collectors.toList()));
        for (Map.Entry<PaperModelKey, List<PaperLocation>> entry : byModel.entrySet()) {
            PaperModelKey key = entry.getKey();
            List<PaperLocation> group = entry.getValue();
            List<Integer> pageNumbers = group.stream()
                    .filter(location -> location.getLocationType() == PaperLocationType.PAGE)
                    .map(PaperLocation::getPageNumber).filter(java.util.Objects::nonNull).distinct().toList();
            if (!pageNumbers.isEmpty()) {
                pageRepository.findByPaperIdAndModelVersionAndPageNumberIn(
                                key.paperId(), key.modelVersion(), pageNumbers)
                        .forEach(page -> pages.put(new PageKey(
                                page.getPaperId(), page.getModelVersion(), page.getPageNumber()), page));
            }
            loadObjects(key, group, PaperLocationType.SECTION,
                    ids -> sectionRepository.findByPaperIdAndModelVersionAndSectionIdIn(
                            key.paperId(), key.modelVersion(), ids),
                    PaperSection::getSectionId, sections);
            loadObjects(key, group, PaperLocationType.PASSAGE,
                    ids -> passageRepository.findByPaperIdAndModelVersionAndPassageRefIn(
                            key.paperId(), key.modelVersion(), ids),
                    PaperPassage::getPassageRef, passages);
            List<String> elementIds = group.stream()
                    .filter(location -> location.getLocationType() == PaperLocationType.TABLE
                            || location.getLocationType() == PaperLocationType.FIGURE)
                    .map(PaperLocation::getSourceObjectId).filter(value -> !blank(value)).distinct().toList();
            if (!elementIds.isEmpty()) {
                elementRepository.findByPaperIdAndModelVersionAndReadingElementIdIn(
                                key.paperId(), key.modelVersion(), elementIds)
                        .forEach(element -> elements.put(new ObjectKey(
                                element.getPaperId(), element.getModelVersion(), element.getReadingElementId()), element));
            }
        }
        return new CanonicalEntities(pages, sections, passages, elements);
    }

    private <T> void loadObjects(PaperModelKey key,
                                 List<PaperLocation> locations,
                                 PaperLocationType type,
                                 Function<List<String>, List<T>> loader,
                                 Function<T, String> id,
                                 Map<ObjectKey, T> target) {
        List<String> ids = locations.stream().filter(location -> location.getLocationType() == type)
                .map(PaperLocation::getSourceObjectId).filter(value -> !blank(value)).distinct().toList();
        if (!ids.isEmpty()) {
            loader.apply(ids).forEach(value -> target.put(
                    new ObjectKey(key.paperId(), key.modelVersion(), id.apply(value)), value));
        }
    }

    static String canonicalPassageContent(PaperPassage passage) {
        return passage == null ? "" : passage.getContentText();
    }

    static String passageIndexText(PaperPassage passage) {
        return passage == null ? "" : firstNonBlankStatic(passage.getIndexText(), passage.getContentText());
    }

    static String canonicalElementContent(PaperReadingElement element) {
        return element == null ? "" : firstNonBlankStatic(
                element.getSearchableText(), element.getBodyText(), element.getCaptionText());
    }

    private static String firstNonBlankStatic(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean validElementType(PaperLocationType type, String elementType) {
        String normalized = trim(elementType).toUpperCase();
        return type == PaperLocationType.TABLE
                ? "TABLE".equals(normalized)
                : Set.of("IMAGE", "FIGURE", "CHART").contains(normalized);
    }

    private boolean sameSpan(String locationSpan, String entitySpan) {
        return !blank(locationSpan) && locationSpan.equals(entitySpan);
    }

    private CanonicalLocation item(PaperLocation location,
                                   PaperReadingModel model,
                                   String title,
                                   String elementType,
                                   String text,
                                   String bboxJson,
                                   String parserName,
                                   String parserVersion,
                                   String sourceObjectId) {
        VisualAvailability visual = visualAvailability(
                location.getPaperId(),
                location.getPageNumber(),
                elementType,
                trim(sourceObjectId)
        );
        return new CanonicalLocation(
                location.getPaperId(),
                title,
                model.getModelVersion(),
                location.getLocationRef(),
                elementType,
                location.getPageNumber(),
                location.getPageEndNumber(),
                trim(location.getSectionTitle()),
                text,
                trim(bboxJson),
                firstNonBlank(parserName, model.getParserName()),
                firstNonBlank(parserVersion, model.getParserVersion()),
                trim(sourceObjectId),
                visual.pageScreenshotAvailable(),
                visual.pdfEvidenceAvailable(),
                visual.tableScreenshotAvailable(),
                visual.figureScreenshotAvailable(),
                visual.assetWarnings()
        );
    }

    private VisualAvailability visualAvailability(String paperId,
                                                  Integer pageNumber,
                                                  String elementType,
                                                  String sourceObjectId) {
        boolean pageScreenshot = pageNumber != null
                && visualAssetRepository.findFirstByPaperIdAndAssetTypeAndPageNumber(
                        paperId,
                        PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                        pageNumber
                ).filter(this::availableAsset).isPresent();
        boolean tableScreenshot = "table".equalsIgnoreCase(elementType)
                && !sourceObjectId.isBlank()
                && visualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                        paperId,
                        PaperVisualAsset.TYPE_TABLE_CROP,
                        sourceObjectId
                ).or(() -> visualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                        paperId,
                        PaperVisualAsset.TYPE_PARSER_IMAGE,
                        sourceObjectId
                )).filter(this::availableAsset).isPresent();
        boolean figureScreenshot = ("figure".equalsIgnoreCase(elementType) || "chart".equalsIgnoreCase(elementType))
                && !sourceObjectId.isBlank()
                && visualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                        paperId,
                        PaperVisualAsset.TYPE_FIGURE_CROP,
                        sourceObjectId
                ).or(() -> visualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                        paperId,
                        PaperVisualAsset.TYPE_CHART_CROP,
                        sourceObjectId
                )).or(() -> visualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                        paperId,
                        PaperVisualAsset.TYPE_PARSER_IMAGE,
                        sourceObjectId
                )).filter(this::availableAsset).isPresent();
        return new VisualAvailability(
                pageScreenshot,
                pageScreenshot,
                tableScreenshot,
                figureScreenshot,
                pageScreenshot ? List.of() : List.of("pdf_page_visual_evidence_unavailable")
        );
    }

    private boolean availableAsset(PaperVisualAsset asset) {
        return asset != null && PaperVisualAsset.STATUS_AVAILABLE.equals(asset.getAssetStatus());
    }

    private Map<String, PaperReadingModel> currentModels(List<String> paperIds) {
        Map<String, PaperReadingModel> result = new LinkedHashMap<>();
        for (String paperId : paperIds) {
            modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId)
                    .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                    .ifPresent(model -> result.put(paperId, model));
        }
        return result;
    }

    private Map<String, String> paperTitles(List<String> paperIds) {
        if (paperIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Paper paper : paperRepository.findByPaperIdIn(paperIds)) {
            result.putIfAbsent(paper.getPaperId(), firstNonBlank(paper.getPaperTitle(), paper.getOriginalFilename(), paper.getPaperId()));
        }
        return result;
    }


    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record CanonicalLocation(String paperId,
                                    String title,
                                    String paperVersion,
                                    String locationRef,
                                    String elementType,
                                    Integer page,
                                    Integer pageEnd,
                                    String section,
                                    String spanText,
                                    String bboxJson,
                                    String parserName,
                                    String parserVersion,
                                    String sourceObjectId,
                                    boolean pageScreenshotAvailable,
                                    boolean pdfEvidenceAvailable,
                                    boolean tableScreenshotAvailable,
                                    boolean figureScreenshotAvailable,
                                    List<String> assetWarnings) {
        public CanonicalLocation {
            assetWarnings = assetWarnings == null ? List.of() : List.copyOf(assetWarnings);
        }
    }

    public record VisualAvailability(boolean pageScreenshotAvailable,
                                     boolean pdfEvidenceAvailable,
                                     boolean tableScreenshotAvailable,
                                     boolean figureScreenshotAvailable,
                                     List<String> assetWarnings) {
        public VisualAvailability {
            assetWarnings = assetWarnings == null ? List.of() : List.copyOf(assetWarnings);
        }
    }

    public record ReadBatch(List<CanonicalLocation> items, List<String> missingLocationRefs) {
        public ReadBatch {
            items = items == null ? List.of() : List.copyOf(items);
            missingLocationRefs = missingLocationRefs == null ? List.of() : List.copyOf(missingLocationRefs);
        }
    }

    private record PaperModelKey(String paperId, String modelVersion) {
    }

    private record PageKey(String paperId, String modelVersion, Integer pageNumber) {
    }

    private record ObjectKey(String paperId, String modelVersion, String objectId) {
    }

    private record CanonicalEntities(Map<PageKey, PaperPage> pages,
                                     Map<ObjectKey, PaperSection> sections,
                                     Map<ObjectKey, PaperPassage> passages,
                                     Map<ObjectKey, PaperReadingElement> elements) {
    }
}
