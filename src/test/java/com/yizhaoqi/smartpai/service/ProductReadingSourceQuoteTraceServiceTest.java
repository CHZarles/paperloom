package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ConversationSourceQuote;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperSourceQuote;
import com.yizhaoqi.smartpai.repository.ConversationSourceQuoteRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperSourceQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingSourceQuoteTraceServiceTest {

    @Mock
    private ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    @Mock
    private PaperSourceQuoteRepository sourceQuoteRepository;
    @Mock
    private ProductPaperHandleService handleService;
    @Mock
    private PaperRepository paperRepository;

    private ProductReadingSourceQuoteTraceService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProductReadingSourceQuoteTraceService(
                conversationSourceQuoteRepository,
                sourceQuoteRepository,
                handleService,
                paperRepository
        );
    }

    @Test
    void tracesRegisteredVisibleSourceQuoteWithoutLeakingInternalFields() throws Exception {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_abc"
        )).thenReturn(Optional.of(conversationQuote("source_quote_abc")));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_abc"))
                .thenReturn(Optional.of(sourceQuote("source_quote_abc", "paper-a")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.manual(List.of("paper-a"))))
                .thenReturn(true);
        when(handleService.handleForPaperId("paper-a")).thenReturn("paper_handle_a");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));

        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_abc", "source_quote_abc"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("paper-a")))
        );

        assertEquals(1, result.sourceQuotes().size());
        assertEquals("source_quote_abc", result.sourceQuotes().get(0).get("sourceQuoteRef"));
        assertEquals("paper_handle_a", result.sourceQuotes().get(0).get("paperHandle"));
        assertEquals("Readable Paper", result.sourceQuotes().get(0).get("paperTitle"));
        assertEquals("Stored quote content with score.", result.sourceQuotes().get(0).get("content"));
        assertEquals(List.of(Map.of("sourceQuoteRef", "source_quote_abc", "status", "OK")), result.traceStatus());

        String json = objectMapper.writeValueAsString(result);
        assertFalse(json.contains("paperId"));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("splitPolicyVersion"));
        assertFalse(json.contains("contentHash"));
        assertFalse(json.contains("score\":"));
        assertFalse(json.contains("rank\":"));
    }

    @Test
    void blankConversationCannotAuthorizeTrace() {
        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_abc"),
                new ProductToolContext(7L, "", "generation-2", SourceScope.auto())
        );

        assertTrue(result.sourceQuotes().isEmpty());
        assertEquals("SOURCE_QUOTE_NOT_IN_CONVERSATION", result.traceStatus().get(0).get("status"));
        verify(conversationSourceQuoteRepository, never())
                .findFirstByConversationIdAndSourceQuoteRef(anyString(), anyString());
        verify(sourceQuoteRepository, never()).findFirstBySourceQuoteRef(anyString());
    }

    @Test
    void returnsStatusForUnregisteredMissingAndUnavailableQuotes() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_unregistered"
        )).thenReturn(Optional.empty());
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_missing"
        )).thenReturn(Optional.of(conversationQuote("source_quote_missing")));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_missing")).thenReturn(Optional.empty());
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_hidden"
        )).thenReturn(Optional.of(conversationQuote("source_quote_hidden")));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_hidden"))
                .thenReturn(Optional.of(sourceQuote("source_quote_hidden", "paper-hidden")));
        when(handleService.isPaperVisibleToUser("paper-hidden", 7L, SourceScope.auto())).thenReturn(false);

        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_unregistered", "source_quote_missing", "source_quote_hidden"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.sourceQuotes().isEmpty());
        assertEquals("SOURCE_QUOTE_NOT_IN_CONVERSATION", result.traceStatus().get(0).get("status"));
        assertEquals("SOURCE_QUOTE_NOT_FOUND", result.traceStatus().get(1).get("status"));
        assertEquals("SOURCE_QUOTE_UNAVAILABLE", result.traceStatus().get(2).get("status"));
    }

    private ConversationSourceQuote conversationQuote(String sourceQuoteRef) {
        ConversationSourceQuote quote = new ConversationSourceQuote();
        quote.setConversationId("conversation-1");
        quote.setSourceQuoteRef(sourceQuoteRef);
        quote.setFirstSeenTurnId("generation-1");
        quote.setUserId("7");
        return quote;
    }

    private PaperSourceQuote sourceQuote(String sourceQuoteRef, String paperId) {
        PaperSourceQuote quote = new PaperSourceQuote();
        quote.setSourceQuoteRef(sourceQuoteRef);
        quote.setPaperId(paperId);
        quote.setModelVersion("model-v1");
        quote.setLocationRef("page_ref_old");
        quote.setLocationType("PAGE");
        quote.setPageNumber(3);
        quote.setPageEndNumber(3);
        quote.setSectionTitle("Results");
        quote.setContentKind("TEXT");
        quote.setContent("Stored quote content with score.");
        quote.setContentHash("hidden-hash");
        quote.setSplitPolicyVersion("read_locations_v1");
        quote.setSplitIndex(0);
        quote.setSourceSpanJson("{}");
        return quote;
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle(title);
        return paper;
    }
}
