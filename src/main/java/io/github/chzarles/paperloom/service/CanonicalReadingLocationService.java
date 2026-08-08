package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.model.PaperVisualAsset;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import io.github.chzarles.paperloom.repository.PaperVisualAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final PaperRepository paperRepository;
    private final PaperVisualAssetRepository visualAssetRepository;
    public CanonicalReadingLocationService(PaperLocationRepository locationRepository,
                                           PaperReadingModelRepository modelRepository,
                                           PaperPageRepository pageRepository,
                                           PaperSectionRepository sectionRepository,
                                           PaperRepository paperRepository,
                                           PaperVisualAssetRepository visualAssetRepository) {
        this.locationRepository = locationRepository;
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
        this.paperRepository = paperRepository;
        this.visualAssetRepository = visualAssetRepository;
    }

    @Transactional(readOnly = true)
    public ReadBatch read(List<String> locationRefs, List<String> authorizedPaperIds) {
        return read(locationRefs, authorizedPaperIds, List.of());
    }

    @Transactional(readOnly = true)
    public ReadBatch read(List<String> locationRefs,
                          List<String> authorizedPaperIds,
                          List<EvidencePayload> evidencePayloads) {
        List<String> refs = locationRefs == null ? List.of() : locationRefs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
        if (refs.isEmpty()) {
            return new ReadBatch(List.of(), List.of());
        }
        Map<String, PaperLocation> locations = locationRepository.findByLocationRefIn(refs).stream()
                .collect(Collectors.toMap(PaperLocation::getLocationRef, Function.identity(), (left, right) -> left));
        Map<String, EvidencePayload> payloadsByRef = (evidencePayloads == null ? List.<EvidencePayload>of() : evidencePayloads)
                .stream()
                .filter(payload -> !blank(payload.locationRef()))
                .collect(Collectors.toMap(EvidencePayload::locationRef, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
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
            CanonicalLocation item = isEvidenceLocation(location)
                    ? resolveEvidence(location, current, titles.getOrDefault(location.getPaperId(), location.getPaperId()),
                    payloadsByRef.get(ref))
                    : resolveStructure(location, current, titles.getOrDefault(location.getPaperId(), location.getPaperId()));
            if (item == null || item.spanText().isBlank()) {
                missing.add(ref);
                continue;
            }
            items.add(item);
        }
        return new ReadBatch(items, missing);
    }

    private CanonicalLocation resolveStructure(PaperLocation location,
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
        return null;
    }

    private CanonicalLocation resolveEvidence(PaperLocation location,
                                              PaperReadingModel model,
                                              String paperTitle,
                                              EvidencePayload payload) {
        if (payload == null
                || !location.getPaperId().equals(payload.paperId())
                || !location.getModelVersion().equals(payload.modelVersion())
                || !location.getLocationRef().equals(payload.locationRef())
                || !location.getLocationType().name().equalsIgnoreCase(payload.locationType())
                || blank(payload.contentText())
                || !sha256(payload.contentText()).equals(payload.contentHash())
                || blank(payload.sourceSpanJson())
                || !payload.sourceSpanJson().equals(location.getSourceSpanJson())) {
            return null;
        }
        return item(location, model, paperTitle, payload.locationType().toLowerCase(), payload.contentText(),
                payload.sourceSpanJson(), model.getParserName(), model.getParserVersion(), location.getSourceObjectId());
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

    private boolean isEvidenceLocation(PaperLocation location) {
        return location.getLocationType() == PaperLocationType.PASSAGE
                || location.getLocationType() == PaperLocationType.TABLE
                || location.getLocationType() == PaperLocationType.FIGURE;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
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

    public record EvidencePayload(String paperId,
                                  String modelVersion,
                                  String locationRef,
                                  String locationType,
                                  String contentText,
                                  String contentHash,
                                  String sourceSpanJson) {
        public EvidencePayload {
            paperId = trimValue(paperId);
            modelVersion = trimValue(modelVersion);
            locationRef = trimValue(locationRef);
            locationType = trimValue(locationType);
            contentText = contentText == null ? "" : contentText;
            contentHash = trimValue(contentHash);
            sourceSpanJson = sourceSpanJson == null ? "" : sourceSpanJson;
        }

        private static String trimValue(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
