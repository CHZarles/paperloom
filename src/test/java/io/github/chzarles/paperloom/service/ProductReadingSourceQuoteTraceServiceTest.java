package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.ConversationSourceQuote;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperSourceQuote;
import io.github.chzarles.paperloom.repository.ConversationSourceQuoteRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperSourceQuoteRepository;
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
    @Mock
    private PaperReadingModelRepository modelRepository;

    private ProductReadingSourceQuoteTraceService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ProductReadingSourceQuoteResolver resolver = new ProductReadingSourceQuoteResolver(
                conversationSourceQuoteRepository,
                sourceQuoteRepository,
                modelRepository,
                paperRepository
        );
        service = new ProductReadingSourceQuoteTraceService(
                resolver,
                handleService
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
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(model("model-v1", PaperReadingModelStatus.READING_MODEL_READY)));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));

        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_abc", "source_quote_abc"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("paper-a")))
        );

        assertEquals(1, result.sourceQuotes().size());
        assertEquals("source_quote_abc", result.sourceQuotes().get(0).get("sourceQuoteRef"));
        assertEquals("paper-a", result.sourceQuotes().get(0).get("paperId"));
        assertEquals("model-v1", result.sourceQuotes().get(0).get("paperVersion"));
        assertEquals("paper_handle_a", result.sourceQuotes().get(0).get("paperHandle"));
        assertEquals("Readable Paper", result.sourceQuotes().get(0).get("paperTitle"));
        assertEquals("Stored quote content with score.", result.sourceQuotes().get(0).get("content"));
        assertTrue(result.sourceQuotes().get(0).containsKey("sourceSpanJson"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visualRegions =
                (List<Map<String, Object>>) result.sourceQuotes().get(0).get("visualRegions");
        assertEquals(1, visualRegions.size());
        assertEquals("mineru_1000", visualRegions.get(0).get("unit"));
        assertEquals("top_left_1000", visualRegions.get(0).get("coordinateSystem"));
        assertEquals(List.of(Map.of("sourceQuoteRef", "source_quote_abc", "status", "OK")), result.traceStatus());

        String json = objectMapper.writeValueAsString(result);
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

    @Test
    void rejectsSourceQuoteFromOlderReadingModelVersion() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_old"
        )).thenReturn(Optional.of(conversationQuote("source_quote_old")));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_old"))
                .thenReturn(Optional.of(sourceQuote("source_quote_old", "paper-a")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.auto())).thenReturn(true);
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(model("model-v2", PaperReadingModelStatus.READING_MODEL_READY)));

        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_old"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertTrue(result.sourceQuotes().isEmpty());
        assertEquals("SOURCE_QUOTE_UNAVAILABLE", result.traceStatus().get(0).get("status"));
    }

    @Test
    void rejectsCurrentReadyQuoteHiddenFromUserScope() {
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(
                "conversation-1",
                "source_quote_hidden"
        )).thenReturn(Optional.of(conversationQuote("source_quote_hidden")));
        when(sourceQuoteRepository.findFirstBySourceQuoteRef("source_quote_hidden"))
                .thenReturn(Optional.of(sourceQuote("source_quote_hidden", "paper-a")));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(model("model-v1", PaperReadingModelStatus.READING_MODEL_READY)));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.manual(List.of("other-paper"))))
                .thenReturn(false);

        ProductReadingSourceQuoteTraceService.TraceResult result = service.traceSourceQuotes(
                List.of("source_quote_hidden"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.manual(List.of("other-paper")))
        );

        assertTrue(result.sourceQuotes().isEmpty());
        assertEquals("SOURCE_QUOTE_UNAVAILABLE", result.traceStatus().get(0).get("status"));
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
        quote.setSourceSpanJson("""
                {
                  "pageNumber": 3,
                  "locationType": "PAGE",
                  "bbox": {"pageNumber":3,"left":100,"top":120,"right":300,"bottom":180,"unit":"mineru_1000","coordinateSystem":"top_left_1000"}
                }
                """);
        return quote;
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle(title);
        return paper;
    }

    private PaperReadingModel model(String modelVersion, PaperReadingModelStatus status) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion(modelVersion);
        model.setModelStatus(status);
        model.setCurrent(true);
        return model;
    }
}
