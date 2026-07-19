package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperTextChunkRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperServiceCandidateSearchTest {

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaperAccessService paperAccessService;

    @Mock
    private PaperSearchabilityService paperSearchabilityService;

    private PaperService paperService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paperService = new PaperService();
        ReflectionTestUtils.setField(paperService, "paperRepository", paperRepository);
        ReflectionTestUtils.setField(paperService, "paperTextChunkRepository", paperTextChunkRepository);
        ReflectionTestUtils.setField(paperService, "userRepository", userRepository);
        ReflectionTestUtils.setField(paperService, "paperAccessService", paperAccessService);
        ReflectionTestUtils.setField(paperService, "paperSearchabilityService", paperSearchabilityService);
    }

    @Test
    void readinessSearchableFiltersAccessiblePersonalAndGlobalPapers() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");

        Paper searchable = paper("paper-searchable");
        Paper unavailable = paper("paper-unavailable");
        searchable.setPaperTitle("Agent Searchable");
        unavailable.setPaperTitle("Agent Unavailable");
        PageRequest pageable = PageRequest.of(0, 10);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paperAccessService.accessiblePapers("1")).thenReturn(List.of(searchable, unavailable));
        when(paperSearchabilityService.isSearchable(searchable)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(unavailable)).thenReturn(false);

        Page<Paper> result = paperService.searchAccessiblePaperCandidates(
                "1",
                "ignored",
                " agent ",
                "searchable",
                pageable
        );

        assertEquals(List.of("paper-searchable"), result.getContent().stream().map(Paper::getPaperId).toList());
        assertEquals(1L, result.getTotalElements());
        verify(paperAccessService).accessiblePapers("1");
    }

    private Paper paper(String paperId) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setUserId("1");
        paper.setPaperTitle(paperId);
        paper.setOriginalFilename(paperId + ".pdf");
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        return paper;
    }
}
