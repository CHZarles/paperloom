package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CanonicalReadingLocationService {

    private final PaperLocationRepository locationRepository;
    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperReadingElementRepository elementRepository;
    private final PaperRepository paperRepository;
    private final PaperVisualAssetRepository visualAssetRepository;
    private final ObjectMapper objectMapper;

    public CanonicalReadingLocationService(PaperLocationRepository locationRepository,
                                           PaperReadingModelRepository modelRepository,
                                           PaperPageRepository pageRepository,
                                           PaperSectionRepository sectionRepository,
                                           PaperReadingElementRepository elementRepository,
                                           PaperRepository paperRepository,
                                           PaperVisualAssetRepository visualAssetRepository,
                                           ObjectMapper objectMapper) {
        this.locationRepository = locationRepository;
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
        this.elementRepository = elementRepository;
        this.paperRepository = paperRepository;
        this.visualAssetRepository = visualAssetRepository;
        this.objectMapper = objectMapper;
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
        Map<String, PaperLocation> locations = locationRepository.findByLocationRefIn(refs).stream()
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<String, PaperReadingModel> currentModels = currentModels(locations.values().stream()
                .map(PaperLocation::getPaperId)
                .distinct()
                .toList());
        Map<String, String> titles = paperTitles(locations.values().stream()
                .map(PaperLocation::getPaperId)
                .distinct()
                .toList());
        List<CanonicalLocation> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String ref : refs) {
            PaperLocation location = locations.get(ref);
            if (location == null || !authorizedPaperIds.contains(location.getPaperId())) {
                missing.add(ref);
                continue;
            }
            PaperReadingModel current = currentModels.get(location.getPaperId());
            if (current == null || !location.getModelVersion().equals(current.getModelVersion())) {
                missing.add(ref);
                continue;
            }
            CanonicalLocation item = resolve(location, current, titles.getOrDefault(location.getPaperId(), location.getPaperId()));
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
                                      String paperTitle) {
        PaperLocationType type = location.getLocationType();
        if (type == PaperLocationType.PAGE) {
            Optional<PaperPage> page = pageRepository.findFirstByPaperIdAndModelVersionAndPageNumber(
                    location.getPaperId(), location.getModelVersion(), location.getPageNumber());
            if (page.isEmpty() || blank(page.get().getPageText())) {
                return null;
            }
            return item(location, model, paperTitle, "page", page.get().getPageText(), null,
                    page.get().getParserName(), page.get().getParserVersion(), null);
        }
        if (type == PaperLocationType.SECTION) {
            Optional<PaperSection> section = sectionRepository.findFirstByPaperIdAndModelVersionAndSectionId(
                    location.getPaperId(), location.getModelVersion(), trim(location.getSourceObjectId()));
            if (section.isEmpty() || blank(section.get().getSectionText())) {
                return null;
            }
            return item(location, model, paperTitle, "section", section.get().getSectionText(), null,
                    section.get().getParserName(), section.get().getParserVersion(), null);
        }
        Optional<PaperReadingElement> element = elementRepository.findFirstByPaperIdAndModelVersionAndReadingElementId(
                location.getPaperId(), location.getModelVersion(), trim(location.getSourceObjectId()));
        if (element.isEmpty()) {
            return null;
        }
        String text = type == PaperLocationType.TABLE
                ? firstNonBlank(element.get().getBodyText(), structuredPayloadText(element.get().getStructuredPayloadJson()))
                : firstNonBlank(element.get().getCaptionText(), element.get().getBodyText());
        return item(location, model, paperTitle, type.name().toLowerCase(), text,
                element.get().getBboxJson(), element.get().getParserName(), element.get().getParserVersion(),
                element.get().getSourceObjectId());
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
                trim(text),
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

    private String structuredPayloadText(String structuredPayloadJson) {
        if (blank(structuredPayloadJson)) {
            return "";
        }
        try {
            List<String> values = new ArrayList<>();
            collectText(objectMapper.readTree(structuredPayloadJson), values);
            return String.join("\n", values).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void collectText(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = trim(node.asText());
            if (!value.isBlank()) {
                values.add(value);
            }
            return;
        }
        if (node.isArray() || node.isObject()) {
            node.forEach(child -> collectText(child, values));
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return "";
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
}
