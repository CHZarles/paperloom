package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaperReadingModelService {

    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperReadingModelBuilder builder;

    public PaperReadingModelService(PaperReadingModelRepository modelRepository,
                                    PaperPageRepository pageRepository,
                                    PaperLocationRepository locationRepository,
                                    PaperReadingModelBuilder builder) {
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.locationRepository = locationRepository;
        this.builder = builder;
    }

    @Transactional
    public PaperReadingModel replaceFromParsedPaper(String paperId,
                                                    ParsedPaper parsedPaper,
                                                    String userId,
                                                    String orgTag,
                                                    boolean isPublic) {
        String modelVersion = newModelVersion();
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_BUILDING);
        model.setCurrent(false);
        model.setParserName(parsedPaper == null ? null : parsedPaper.parserName());
        model.setParserVersion(parsedPaper == null ? null : parsedPaper.parserVersion());
        model.setPageCount(resolvePageCount(parsedPaper));
        model.setReadablePageCount(0);
        model.setReadableCharCount(0);
        model = modelRepository.save(model);

        PaperReadingModelBuildResult result;
        try {
            result = builder.build(paperId, modelVersion, parsedPaper, userId, orgTag, isPublic);
        } catch (PaperReadingModelValidationException exception) {
            model.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
            model.setFailureReason(exception.failureReason());
            model.setDiagnosticsJson(exception.diagnosticsJson());
            model.setCurrent(false);
            return modelRepository.save(model);
        }

        pageRepository.saveAll(result.pages());
        locationRepository.saveAll(result.locations());
        modelRepository.clearCurrentModels(paperId, modelVersion);

        int readableCharCount = result.pages().stream()
                .mapToInt(page -> page.getCharCount() == null ? 0 : page.getCharCount())
                .sum();
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setPageCount(resolvePageCount(parsedPaper));
        model.setReadablePageCount(result.pages().size());
        model.setReadableCharCount(readableCharCount);
        model.setDiagnosticsJson(result.diagnosticsJson());
        model.setFailureReason(null);
        return modelRepository.save(model);
    }

    private Integer resolvePageCount(ParsedPaper parsedPaper) {
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
