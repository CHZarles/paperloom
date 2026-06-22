package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperTable;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperTable;
import com.yizhaoqi.smartpai.repository.PaperTableRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PaperTableService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PaperTableRepository paperTableRepository;

    @Transactional
    public List<PaperTable> replaceTables(String paperId,
                                          ParsedPaper parsedPaper,
                                          String userId,
                                          String orgTag,
                                          boolean isPublic) {
        paperTableRepository.deleteByPaperId(paperId);
        if (parsedPaper == null || parsedPaper.tables() == null || parsedPaper.tables().isEmpty()) {
            return List.of();
        }

        List<PaperTable> tables = parsedPaper.tables().stream()
                .map(table -> toRecord(paperId, parsedPaper, table, userId, orgTag, isPublic))
                .toList();
        return paperTableRepository.saveAll(tables);
    }

    public List<PaperTable> listTables(String paperId) {
        return paperTableRepository.findByPaperIdOrderByPageNumberAscIdAsc(paperId);
    }

    public Optional<PaperTable> findTable(String paperId, String tableId) {
        return paperTableRepository.findFirstByPaperIdAndTableId(paperId, tableId);
    }

    public long countByPaperId(String paperId) {
        return paperTableRepository.countByPaperId(paperId);
    }

    @Transactional
    public void updateTableScreenshot(String paperId, String tableId, String objectKey) {
        paperTableRepository.findFirstByPaperIdAndTableId(paperId, tableId).ifPresent(table -> {
            table.setScreenshotObjectKey(objectKey);
            paperTableRepository.save(table);
        });
    }

    @Transactional
    public void deleteTables(String paperId) {
        paperTableRepository.deleteByPaperId(paperId);
    }

    private PaperTable toRecord(String paperId,
                                ParsedPaper parsedPaper,
                                ParsedPaperTable parsedTable,
                                String userId,
                                String orgTag,
                                boolean isPublic) {
        PaperTable table = new PaperTable();
        table.setPaperId(paperId);
        table.setTableId(parsedTable.tableId());
        table.setPageNumber(parsedTable.pageNumber());
        table.setCaption(parsedTable.caption());
        table.setSectionTitle(parsedTable.sectionTitle());
        table.setRowCount(parsedTable.rowCount());
        table.setColumnCount(parsedTable.columnCount());
        table.setTableText(parsedTable.tableText());
        table.setTableMarkdown(parsedTable.tableMarkdown());
        table.setBboxJson(toJson(parsedTable.boundingBox()));
        table.setParserName(parsedPaper.parserName());
        table.setParserVersion(parsedPaper.parserVersion());
        table.setUserId(userId);
        table.setOrgTag(orgTag);
        table.setPublic(isPublic);
        return table;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化表格 bbox 失败", e);
        }
    }
}
