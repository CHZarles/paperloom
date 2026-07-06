package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.model.PaperSection;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import com.yizhaoqi.smartpai.repository.PaperSectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperReadingModelServiceTest {

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperPageRepository pageRepository;

    @Mock
    private PaperSectionRepository sectionRepository;

    @Mock
    private PaperLocationRepository locationRepository;

    @Mock
    private PaperReadingElementRepository readingElementRepository;

    @Test
    void successfulBuildCreatesCurrentReadyModel() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(locationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(readingElementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                sectionRepository,
                locationRepository,
                readingElementRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("Readable text."),
                "user-a",
                "lab",
                true
        );

        assertEquals(PaperReadingModelStatus.READING_MODEL_READY, model.getModelStatus());
        assertTrue(model.isCurrent());
        assertEquals(1, model.getPageCount());
        assertEquals(1, model.getReadablePageCount());
        assertEquals("MinerU", model.getParserName());
        verify(pageRepository).saveAll(any());
        verify(sectionRepository).saveAll(any());
        verify(locationRepository).saveAll(any());
        verify(readingElementRepository).saveAll(any());
        verify(modelRepository).clearCurrentModels(eq("paper-a"), eq(model.getModelVersion()));
    }

    @Test
    void failedBuildDoesNotReplaceCurrentModel() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                sectionRepository,
                locationRepository,
                readingElementRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper(" "),
                "user-a",
                "lab",
                false
        );

        assertEquals(PaperReadingModelStatus.READING_MODEL_FAILED, model.getModelStatus());
        assertEquals("NO_READABLE_NUMBERED_TEXT", model.getFailureReason());
        assertFalse(model.isCurrent());
        verify(pageRepository, never()).saveAll(any());
        verify(sectionRepository, never()).saveAll(any());
        verify(locationRepository, never()).saveAll(any());
        verify(readingElementRepository, never()).saveAll(any());
        verify(modelRepository, never()).clearCurrentModels(any(), any());
    }

    @Test
    void pagesAndLocationsUseSameModelVersion() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(locationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(readingElementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                sectionRepository,
                locationRepository,
                readingElementRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper("paper-a", parsedPaper("Readable text."), "user-a", "lab", true);

        ArgumentCaptor<Iterable<PaperPage>> pagesCaptor = ArgumentCaptor.forClass(Iterable.class);
        ArgumentCaptor<Iterable<PaperSection>> sectionsCaptor = ArgumentCaptor.forClass(Iterable.class);
        ArgumentCaptor<Iterable<PaperLocation>> locationsCaptor = ArgumentCaptor.forClass(Iterable.class);
        ArgumentCaptor<Iterable<PaperReadingElement>> readingElementsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(pageRepository).saveAll(pagesCaptor.capture());
        verify(sectionRepository).saveAll(sectionsCaptor.capture());
        verify(locationRepository).saveAll(locationsCaptor.capture());
        verify(readingElementRepository).saveAll(readingElementsCaptor.capture());
        PaperPage page = pagesCaptor.getValue().iterator().next();
        PaperSection section = sectionsCaptor.getValue().iterator().next();
        PaperLocation location = locationsCaptor.getValue().iterator().next();
        PaperReadingElement readingElement = readingElementsCaptor.getValue().iterator().next();

        assertEquals(model.getModelVersion(), page.getModelVersion());
        assertEquals(model.getModelVersion(), section.getModelVersion());
        assertEquals(model.getModelVersion(), location.getModelVersion());
        assertEquals(model.getModelVersion(), readingElement.getModelVersion());
    }

    @Test
    void repeatedBuildsUseDistinctModelVersions() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sectionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(locationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(readingElementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                sectionRepository,
                locationRepository,
                readingElementRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel first = service.replaceFromParsedPaper("paper-a", parsedPaper("Readable text."), "user-a", "lab", true);
        PaperReadingModel second = service.replaceFromParsedPaper("paper-a", parsedPaper("Readable text."), "user-a", "lab", true);

        assertFalse(first.getModelVersion().equals(second.getModelVersion()));
        verify(modelRepository).clearCurrentModels(eq("paper-a"), eq(first.getModelVersion()));
        verify(modelRepository).clearCurrentModels(eq("paper-a"), eq(second.getModelVersion()));
    }

    private ParsedPaper parsedPaper(String text) {
        List<ParsedPaperElement> elements = text == null || text.isBlank()
                ? List.of(new ParsedPaperElement(
                "p1",
                1,
                1,
                ParsedPaperElementType.PARAGRAPH,
                text,
                null,
                null,
                null,
                Map.of()
        ))
                : List.of(
                new ParsedPaperElement(
                        "h1",
                        1,
                        1,
                        ParsedPaperElementType.HEADING,
                        "Intro",
                        null,
                        1,
                        null,
                        Map.of()
                ),
                new ParsedPaperElement(
                        "p1",
                        1,
                        2,
                        ParsedPaperElementType.PARAGRAPH,
                        text,
                        "Intro",
                        null,
                        null,
                        Map.of()
                )
        );
        return new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                elements,
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
