package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaperReadingModelService {

    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperReadingElementRepository readingElementRepository;
    private final PaperReadingModelBuilder builder;

    public PaperReadingModelService(PaperReadingModelRepository modelRepository,
                                    PaperPageRepository pageRepository,
                                    PaperSectionRepository sectionRepository,
                                    PaperLocationRepository locationRepository,
                                    PaperReadingElementRepository readingElementRepository,
                                    PaperReadingModelBuilder builder) {
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.sectionRepository = sectionRepository;
        this.locationRepository = locationRepository;
        this.readingElementRepository = readingElementRepository;
        this.builder = builder;
    }

    @Transactional
    public PaperReadingModel replaceFromParsedPaper(String paperId,
                                                    ParsedPaper parsedPaper,
                                                    String userId,
                                                    String orgTag,
                                                    boolean isPublic) {
        return replaceFromParsedPaper(paperId, parsedPaper, null, userId, orgTag, isPublic);
    }

    @Transactional
    public PaperReadingModel replaceFromParsedPaper(String paperId,
                                                    ParsedPaper parsedPaper,
                                                    Integer physicalPageCount,
                                                    String userId,
                                                    String orgTag,
                                                    boolean isPublic) {
        String modelVersion = newModelVersion();
        Integer pageCount = resolvePageCount(parsedPaper, physicalPageCount);
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_BUILDING);
        model.setCurrent(false);
        model.setParserName(parsedPaper == null ? null : parsedPaper.parserName());
        model.setParserVersion(parsedPaper == null ? null : parsedPaper.parserVersion());
        model.setPageCount(pageCount);
        model.setReadablePageCount(0);
        model.setReadableCharCount(0);
        model = modelRepository.save(model);

        PaperReadingModelBuildResult result;
        try {
            result = builder.build(paperId, modelVersion, parsedPaper, physicalPageCount, userId, orgTag, isPublic);
        } catch (PaperReadingModelValidationException exception) {
            model.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
            model.setFailureReason(exception.failureReason());
            model.setDiagnosticsJson(exception.diagnosticsJson());
            model.setCurrent(false);
            return modelRepository.save(model);
        }

        pageRepository.saveAll(result.pages());
        sectionRepository.saveAll(result.sections());
        locationRepository.saveAll(result.locations());
        readingElementRepository.saveAll(result.readingElements());
        modelRepository.clearCurrentModels(paperId, modelVersion);

        int readableCharCount = result.pages().stream()
                .mapToInt(page -> page.getCharCount() == null ? 0 : page.getCharCount())
                .sum();
        int readablePageCount = (int) result.pages().stream()
                .filter(page -> com.yizhaoqi.smartpai.model.PaperPage.TEXT_STATUS_READABLE.equals(page.getTextStatus()))
                .count();
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setPageCount(pageCount);
        model.setReadablePageCount(readablePageCount);
        model.setReadableCharCount(readableCharCount);
        model.setDiagnosticsJson(result.diagnosticsJson());
        model.setFailureReason(null);
        return modelRepository.save(model);
    }

    private Integer resolvePageCount(ParsedPaper parsedPaper) {
        return resolvePageCount(parsedPaper, null);
    }

    private Integer resolvePageCount(ParsedPaper parsedPaper, Integer physicalPageCount) {
        if (physicalPageCount != null && physicalPageCount > 0) {
            return physicalPageCount;
        }
        if (parsedPaper == null) {
            return null;
        }
        if (parsedPaper.metadata() != null && parsedPaper.metadata().pageCount() != null) {
            return parsedPaper.metadata().pageCount();
        }
        if (parsedPaper.elements() == null) {
            return null;
        }
        return parsedPaper.elements().stream()
                .filter(Objects::nonNull)
                .map(ParsedPaperElement::pageNumber)
                .filter(pageNumber -> pageNumber != null && pageNumber > 0)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private String newModelVersion() {
        return "rm_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
