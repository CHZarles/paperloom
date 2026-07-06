package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaperCandidateSearchServiceTest {

    @Mock
    private PaperService paperService;

    private PaperCandidateSearchService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PaperCandidateSearchService(paperService);
    }

    @Test
    void titleMatchRanksAboveFilenameMatch() {
        Paper filenameMatch = paper("filename-paper", "Unrelated", "agentic-eval-report.pdf", null);
        Paper titleMatch = paper("title-paper", "Agentic Eval Benchmark", "benchmark.pdf", null);
        when(paperService.getAccessiblePapers("u1", "lab")).thenReturn(List.of(filenameMatch, titleMatch));

        List<PaperCandidate> candidates = service.search(new PaperCandidateSearchRequest(
                "agentic eval",
                "u1",
                "lab",
                20
        ));

        assertEquals(List.of("title-paper", "filename-paper"), candidates.stream().map(PaperCandidate::paperId).toList());
        assertEquals(10, candidates.get(0).rank());
        assertTrue(candidates.get(0).matchedFields().contains("title"));
        assertEquals(50, candidates.get(1).rank());
        assertTrue(candidates.get(1).matchedFields().contains("originalFilename"));
    }

    @Test
    void abstractMatchReturnsCandidateWithPreview() {
        Paper paper = paper("abstract-paper", "Different Topic", "different.pdf",
                "This work studies agentic evaluation loops for tool-using systems.");
        when(paperService.getAccessiblePapers("u1", "lab")).thenReturn(List.of(paper));

        List<PaperCandidate> candidates = service.search(new PaperCandidateSearchRequest(
                "agentic evaluation",
                "u1",
                "lab",
                20
        ));

        assertEquals(1, candidates.size());
        assertEquals("abstract-paper", candidates.get(0).paperId());
        assertEquals(20, candidates.get(0).rank());
        assertTrue(candidates.get(0).matchedFields().contains("abstract"));
        assertTrue(candidates.get(0).abstractPreview().contains("agentic evaluation"));
    }

    @Test
    void searchUsesAccessibleLibraryFromPaperService() {
        Paper accessible = paper("accessible-paper", "Agentic Evaluation", "accessible.pdf", null);
        when(paperService.getAccessiblePapers("u1", "lab")).thenReturn(List.of(accessible));

        List<PaperCandidate> candidates = service.search(new PaperCandidateSearchRequest(
                "agentic",
                "u1",
                "lab",
                20
        ));

        assertEquals(List.of("accessible-paper"), candidates.stream().map(PaperCandidate::paperId).toList());
        verify(paperService).getAccessiblePapers("u1", "lab");
    }

    @Test
    void emptyOrTooShortQueryReturnsNoCandidatesWithoutLoadingLibrary() {
        assertEquals(List.of(), service.search(new PaperCandidateSearchRequest(" ", "u1", "lab", 20)));
        assertEquals(List.of(), service.search(new PaperCandidateSearchRequest("a", "u1", "lab", 20)));
        verifyNoInteractions(paperService);
    }

    private Paper paper(String paperId, String title, String filename, String abstractText) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setPaperTitle(title);
        paper.setOriginalFilename(filename);
        paper.setAuthors("Ada Lovelace");
        paper.setVenue("NeurIPS");
        paper.setPublicationYear(2025);
        paper.setAbstractText(abstractText);
        return paper;
    }
}
