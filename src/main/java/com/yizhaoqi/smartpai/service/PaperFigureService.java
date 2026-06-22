package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperFigure;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFigure;
import com.yizhaoqi.smartpai.repository.PaperFigureRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PaperFigureService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PaperFigureRepository paperFigureRepository;

    @Transactional
    public List<PaperFigure> replaceFigures(String paperId,
                                            ParsedPaper parsedPaper,
                                            String userId,
                                            String orgTag,
                                            boolean isPublic) {
        paperFigureRepository.deleteByPaperId(paperId);
        if (parsedPaper == null || parsedPaper.figures() == null || parsedPaper.figures().isEmpty()) {
            return List.of();
        }
        List<PaperFigure> figures = parsedPaper.figures().stream()
                .map(figure -> toRecord(paperId, parsedPaper, figure, userId, orgTag, isPublic))
                .toList();
        return paperFigureRepository.saveAll(figures);
    }

    public List<PaperFigure> listFigures(String paperId) {
        return paperFigureRepository.findByPaperIdOrderByPageNumberAscIdAsc(paperId);
    }

    public Optional<PaperFigure> findFigure(String paperId, String figureId) {
        return paperFigureRepository.findFirstByPaperIdAndFigureId(paperId, figureId);
    }

    public long countByPaperId(String paperId) {
        return paperFigureRepository.countByPaperId(paperId);
    }

    @Transactional
    public void updateFigureScreenshot(String paperId, String figureId, String objectKey) {
        paperFigureRepository.findFirstByPaperIdAndFigureId(paperId, figureId).ifPresent(figure -> {
            figure.setScreenshotObjectKey(objectKey);
            paperFigureRepository.save(figure);
        });
    }

    @Transactional
    public void deleteFigures(String paperId) {
        paperFigureRepository.deleteByPaperId(paperId);
    }

    private PaperFigure toRecord(String paperId,
                                 ParsedPaper parsedPaper,
                                 ParsedPaperFigure parsedFigure,
                                 String userId,
                                 String orgTag,
                                 boolean isPublic) {
        PaperFigure figure = new PaperFigure();
        figure.setPaperId(paperId);
        figure.setFigureId(parsedFigure.figureId());
        figure.setPageNumber(parsedFigure.pageNumber());
        figure.setCaption(parsedFigure.caption());
        figure.setSectionTitle(parsedFigure.sectionTitle());
        figure.setFigureText(parsedFigure.figureText());
        figure.setBboxJson(toJson(parsedFigure.boundingBox()));
        figure.setDetectionSource(parsedFigure.detectionSource());
        figure.setConfidence(parsedFigure.confidence());
        figure.setParserName(parsedPaper.parserName());
        figure.setParserVersion(parsedPaper.parserVersion());
        figure.setUserId(userId);
        figure.setOrgTag(orgTag);
        figure.setPublic(isPublic);
        return figure;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化图像 bbox 失败", e);
        }
    }
}
