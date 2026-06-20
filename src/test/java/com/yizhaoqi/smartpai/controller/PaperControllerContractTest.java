package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.service.PaperService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class PaperControllerContractTest {

    @Mock
    private PaperService paperService;

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private OrganizationTagRepository organizationTagRepository;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private ChatHandler chatHandler;

    @Mock
    private ConversationService conversationService;

    private PaperController paperController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paperController = new PaperController();
        ReflectionTestUtils.setField(paperController, "paperService", paperService);
        ReflectionTestUtils.setField(paperController, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(paperController, "organizationTagRepository", organizationTagRepository);
        ReflectionTestUtils.setField(paperController, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(paperController, "chatHandler", chatHandler);
        ReflectionTestUtils.setField(paperController, "conversationService", conversationService);
    }

    @Test
    void uploadedPaperListUsesPaperContractNames() {
        Paper paper = new Paper();
        paper.setPaperId("0123456789abcdef0123456789abcdef");
        paper.setOriginalFilename("original.pdf");
        paper.setPaperTitle("Paper Title");
        paper.setTotalSize(4096L);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        paper.setUserId("2");
        paper.setOrgTag("lab");
        paper.setPublic(true);

        when(paperService.getUserUploadedPapers("2")).thenReturn(List.of(paper));
        when(organizationTagRepository.findByTagId("lab")).thenReturn(Optional.empty());

        var response = paperController.getUserUploadedPapers("2");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        List<?> data = (List<?>) body.get("data");
        Map<?, ?> item = (Map<?, ?>) data.get(0);

        assertEquals("0123456789abcdef0123456789abcdef", item.get("paperId"));
        assertEquals("Paper Title", item.get("paperTitle"));
        assertEquals("original.pdf", item.get("originalFilename"));
        assertEquals("COMPLETED", item.get("processingStatus"));
        assertTrue((Boolean) item.get("isPublic"));
        assertFalse(item.containsKey("fileMd5"));
        assertFalse(item.containsKey("fileName"));
        assertFalse(item.containsKey("sourceFileName"));
        assertFalse(item.containsKey("public"));
        assertFalse(item.containsKey("vectorizationStatus"));
    }
}
