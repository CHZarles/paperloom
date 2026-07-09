package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.ConversationSourceQuote;
import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.model.PaperSourceQuote;
import com.yizhaoqi.smartpai.repository.ConversationSourceQuoteRepository;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import com.yizhaoqi.smartpai.repository.PaperSourceQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReadingLocationReadServiceTest {

    @Mock
    private PaperLocationRepository locationRepository;
    @Mock
    private PaperReadingModelRepository modelRepository;
    @Mock
    private PaperPageRepository pageRepository;
    @Mock
    private PaperSectionRepository sectionRepository;
    @Mock
    private PaperReadingElementRepository elementRepository;
    @Mock
    private PaperSourceQuoteRepository sourceQuoteRepository;
    @Mock
    private ConversationSourceQuoteRepository conversationSourceQuoteRepository;
    @Mock
    private ProductPaperHandleService handleService;
    @Mock
    private PaperRepository paperRepository;

    private ProductReadingLocationReadService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<PaperSourceQuote> savedSourceQuotes = new ArrayList<>();
    private final List<ConversationSourceQuote> savedConversationQuotes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProductReadingLocationReadService(
                locationRepository,
                modelRepository,
                pageRepository,
                sectionRepository,
                elementRepository,
                sourceQuoteRepository,
                conversationSourceQuoteRepository,
                handleService,
                paperRepository,
                objectMapper
        );
        when(sourceQuoteRepository.findFirstByPaperIdAndModelVersionAndLocationRefAndSplitPolicyVersionAndSplitIndexAndContentHash(
                anyString(), anyString(), anyString(), anyString(), any(Integer.class), anyString()
        )).thenAnswer(invocation -> savedSourceQuotes.stream()
                .filter(quote -> quote.getPaperId().equals(invocation.getArgument(0)))
                .filter(quote -> quote.getModelVersion().equals(invocation.getArgument(1)))
                .filter(quote -> quote.getLocationRef().equals(invocation.getArgument(2)))
                .filter(quote -> quote.getSplitPolicyVersion().equals(invocation.getArgument(3)))
                .filter(quote -> quote.getSplitIndex().equals(invocation.getArgument(4)))
                .filter(quote -> quote.getContentHash().equals(invocation.getArgument(5)))
                .findFirst());
        when(sourceQuoteRepository.saveAndFlush(any(PaperSourceQuote.class))).thenAnswer(invocation -> {
            PaperSourceQuote quote = invocation.getArgument(0);
            savedSourceQuotes.add(quote);
            return quote;
        });
        when(conversationSourceQuoteRepository.findFirstByConversationIdAndSourceQuoteRef(anyString(), anyString()))
                .thenAnswer(invocation -> savedConversationQuotes.stream()
                        .filter(item -> item.getConversationId().equals(invocation.getArgument(0)))
                        .filter(item -> item.getSourceQuoteRef().equals(invocation.getArgument(1)))
                        .findFirst());
        when(conversationSourceQuoteRepository.save(any(ConversationSourceQuote.class))).thenAnswer(invocation -> {
            ConversationSourceQuote row = invocation.getArgument(0);
            savedConversationQuotes.add(row);
            return row;
        });
    }

    @Test
    void readsPageTextIntoStableOpaqueSourceQuotesAndRegistersConversation() throws Exception {
        PaperLocation location = location("page_ref_1", PaperLocationType.PAGE, null, 3);
        PaperPage page = page("The reported score improves.\n\nSecond paragraph.");
        when(locationRepository.findFirstByLocationRef("page_ref_1")).thenReturn(Optional.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model("model-v1")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.handleForPaperId("paper-a")).thenReturn("paper_handle_a");
        when(pageRepository.findFirstByPaperIdAndModelVersionAndPageNumber("paper-a", "model-v1", 3))
                .thenReturn(Optional.of(page));
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));

        ProductReadingLocationReadService.ReadResult first = service.readLocations(
                List.of("page_ref_1"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );
        ProductReadingLocationReadService.ReadResult second = service.readLocations(
                List.of("page_ref_1"),
                new ProductToolContext(7L, "conversation-1", "generation-2", SourceScope.auto())
        );

        assertEquals(2, first.sourceQuotes().size());
        assertEquals("OK", first.readStatus().get(0).get("status"));
        String firstRef = String.valueOf(first.sourceQuotes().get(0).get("sourceQuoteRef"));
        String secondRef = String.valueOf(second.sourceQuotes().get(0).get("sourceQuoteRef"));
        assertEquals(firstRef, secondRef);
        assertTrue(firstRef.startsWith("source_quote_"));
        assertFalse(firstRef.contains("paper-a"));
        assertFalse(firstRef.contains("model-v1"));
        assertFalse(firstRef.contains("page_ref_1"));
        assertEquals(2, savedSourceQuotes.size());
        assertEquals(2, savedConversationQuotes.size());
        assertEquals("generation-1", savedConversationQuotes.get(0).getFirstSeenTurnId());
        assertEquals("paper-a", first.sourceQuotes().get(0).get("paperId"));
        assertEquals("model-v1", first.sourceQuotes().get(0).get("paperVersion"));

        String json = objectMapper.writeValueAsString(first.sourceQuotes());
        assertTrue(json.contains("Readable Paper"));
        assertTrue(json.contains("paper_handle_a"));
        assertTrue(json.contains("The reported score improves."));
        assertFalse(json.contains("modelVersion"));
        assertFalse(json.contains("splitPolicyVersion"));
        assertFalse(json.contains("contentHash"));
    }

    @Test
    void rejectsStaleLocationFromOlderReadingModelVersion() {
        PaperLocation location = location("page_ref_old", PaperLocationType.PAGE, null, 3);
        when(locationRepository.findFirstByLocationRef("page_ref_old")).thenReturn(Optional.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model("model-v2")));

        ProductReadingLocationReadService.ReadResult result = service.readLocations(
                List.of("page_ref_old"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertTrue(result.sourceQuotes().isEmpty());
        assertEquals("CURRENT_LOCATION_NOT_FOUND", result.readStatus().get(0).get("status"));
        verify(pageRepository, never()).findFirstByPaperIdAndModelVersionAndPageNumber(anyString(), anyString(), any());
    }

    @Test
    void readsSectionAndTableByLocationSourceObjectId() {
        PaperLocation sectionLocation = location("section_ref_1", PaperLocationType.SECTION, "section-1", 2);
        PaperLocation tableLocation = location("table_ref_1", PaperLocationType.TABLE, "reading-el-1", 4);
        when(locationRepository.findFirstByLocationRef("section_ref_1")).thenReturn(Optional.of(sectionLocation));
        when(locationRepository.findFirstByLocationRef("table_ref_1")).thenReturn(Optional.of(tableLocation));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model("model-v1")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.handleForPaperId("paper-a")).thenReturn("paper_handle_a");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));
        PaperSection section = new PaperSection();
        section.setSectionText("Section original text.");
        when(sectionRepository.findFirstByPaperIdAndModelVersionAndSectionId("paper-a", "model-v1", "section-1"))
                .thenReturn(Optional.of(section));
        PaperReadingElement table = new PaperReadingElement();
        table.setBodyText("Table original text.");
        when(elementRepository.findFirstByPaperIdAndModelVersionAndReadingElementId("paper-a", "model-v1", "reading-el-1"))
                .thenReturn(Optional.of(table));

        ProductReadingLocationReadService.ReadResult result = service.readLocations(
                List.of("section_ref_1", "table_ref_1"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertEquals(2, result.sourceQuotes().size());
        assertEquals("TEXT", result.sourceQuotes().get(0).get("contentKind"));
        assertEquals("TABLE", result.sourceQuotes().get(1).get("contentKind"));
        assertEquals("Section original text.", result.sourceQuotes().get(0).get("content"));
        assertEquals("Table original text.", result.sourceQuotes().get(1).get("content"));
    }

    @Test
    void skipsSectionTitleOnlySplitsWhenReadingEvidence() {
        PaperLocation sectionLocation = location("section_ref_1", PaperLocationType.SECTION, "section-1", 2);
        sectionLocation.setSectionTitle("4 Experiments");
        when(locationRepository.findFirstByLocationRef("section_ref_1")).thenReturn(Optional.of(sectionLocation));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a")).thenReturn(Optional.of(model("model-v1")));
        when(handleService.isPaperVisibleToUser("paper-a", 7L, SourceScope.auto())).thenReturn(true);
        when(handleService.handleForPaperId("paper-a")).thenReturn("paper_handle_a");
        when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper-a")).thenReturn(Optional.of(paper("Readable Paper")));
        PaperSection section = new PaperSection();
        section.setSectionText("4 Experiments\n\nThis section presents the experimental setup and results.");
        when(sectionRepository.findFirstByPaperIdAndModelVersionAndSectionId("paper-a", "model-v1", "section-1"))
                .thenReturn(Optional.of(section));

        ProductReadingLocationReadService.ReadResult result = service.readLocations(
                List.of("section_ref_1"),
                new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
        );

        assertEquals(1, result.sourceQuotes().size());
        assertEquals("This section presents the experimental setup and results.",
                result.sourceQuotes().get(0).get("content"));
        assertEquals("OK", result.readStatus().get(0).get("status"));
    }

    private PaperLocation location(String locationRef,
                                   PaperLocationType type,
                                   String sourceObjectId,
                                   Integer pageNumber) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef(locationRef);
        location.setPaperId("paper-a");
        location.setModelVersion("model-v1");
        location.setLocationType(type);
        location.setPageNumber(pageNumber);
        location.setPageEndNumber(pageNumber);
        location.setSectionTitle("Methods");
        location.setSourceObjectId(sourceObjectId);
        location.setSourceSpanJson("{}");
        location.setContentKind(type.name());
        return location;
    }

    private PaperReadingModel model(String version) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion(version);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        return model;
    }

    private PaperPage page(String text) {
        PaperPage page = new PaperPage();
        page.setPageText(text);
        page.setTextStatus(PaperPage.TEXT_STATUS_READABLE);
        return page;
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle(title);
        return paper;
    }
}
