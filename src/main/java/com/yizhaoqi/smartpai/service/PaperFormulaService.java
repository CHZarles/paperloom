package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperFormula;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFormula;
import com.yizhaoqi.smartpai.repository.PaperFormulaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PaperFormulaService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PaperFormulaRepository paperFormulaRepository;

    @Transactional
    public List<PaperFormula> replaceFormulas(String paperId,
                                              ParsedPaper parsedPaper,
                                              String userId,
                                              String orgTag,
                                              boolean isPublic) {
        paperFormulaRepository.deleteByPaperId(paperId);
        if (parsedPaper == null || parsedPaper.formulas() == null || parsedPaper.formulas().isEmpty()) {
            return List.of();
        }
        List<PaperFormula> formulas = parsedPaper.formulas().stream()
                .map(formula -> toRecord(paperId, parsedPaper, formula, userId, orgTag, isPublic))
                .toList();
        return paperFormulaRepository.saveAll(formulas);
    }

    public List<PaperFormula> listFormulas(String paperId) {
        return paperFormulaRepository.findByPaperIdOrderByPageNumberAscIdAsc(paperId);
    }

    public Optional<PaperFormula> findFormula(String paperId, String formulaId) {
        return paperFormulaRepository.findFirstByPaperIdAndFormulaId(paperId, formulaId);
    }

    public long countByPaperId(String paperId) {
        return paperFormulaRepository.countByPaperId(paperId);
    }

    @Transactional
    public void deleteFormulas(String paperId) {
        paperFormulaRepository.deleteByPaperId(paperId);
    }

    private PaperFormula toRecord(String paperId,
                                  ParsedPaper parsedPaper,
                                  ParsedPaperFormula parsedFormula,
                                  String userId,
                                  String orgTag,
                                  boolean isPublic) {
        PaperFormula formula = new PaperFormula();
        formula.setPaperId(paperId);
        formula.setFormulaId(parsedFormula.formulaId());
        formula.setPageNumber(parsedFormula.pageNumber());
        formula.setLatex(parsedFormula.latex());
        formula.setContextText(parsedFormula.contextText());
        formula.setSectionTitle(parsedFormula.sectionTitle());
        formula.setBboxJson(toJson(parsedFormula.boundingBox()));
        formula.setParserName(parsedPaper.parserName());
        formula.setParserVersion(parsedPaper.parserVersion());
        formula.setUserId(userId);
        formula.setOrgTag(orgTag);
        formula.setPublic(isPublic);
        return formula;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化公式 bbox 失败", e);
        }
    }
}
