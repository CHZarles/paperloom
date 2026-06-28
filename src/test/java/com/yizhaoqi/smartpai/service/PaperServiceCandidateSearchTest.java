package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Paper;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperRepository;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

class PaperServiceCandidateSearchTest {

    @Mock
    private PaperRepository paperRepository;

    @Mock
    private PaperTextChunkRepository paperTextChunkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgTagCacheService orgTagCacheService;

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
        ReflectionTestUtils.setField(paperService, "orgTagCacheService", orgTagCacheService);
        ReflectionTestUtils.setField(paperService, "paperSearchabilityService", paperSearchabilityService);
    }

    @Test
    void readinessSearchableFiltersCurrentPageAfterRepositoryPagingAndPreservesTotal() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");

        Paper searchable = paper("paper-searchable");
        Paper unsearchable = paper("paper-unsearchable");
        PageRequest pageable = PageRequest.of(0, 10);
        Page<Paper> repositoryPage = new PageImpl<>(List.of(searchable, unsearchable), pageable, 25);

        when(paperRepository.findAllByVectorizationStatusIsNull()).thenReturn(List.of());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("lab"));
        when(paperRepository.searchAccessiblePaperCandidates("1", List.of("lab"), "agent", pageable))
                .thenReturn(repositoryPage);
        when(paperSearchabilityService.isSearchable(searchable)).thenReturn(true);
        when(paperSearchabilityService.isSearchable(unsearchable)).thenReturn(false);

        Page<Paper> result = paperService.searchAccessiblePaperCandidates(
                "1",
                "ignored",
                " agent ",
                "searchable",
                pageable
        );

        assertEquals(List.of("paper-searchable"), result.getContent().stream().map(Paper::getPaperId).toList());
        assertEquals(25L, result.getTotalElements());

        InOrder inOrder = inOrder(paperRepository, paperSearchabilityService);
        inOrder.verify(paperRepository).searchAccessiblePaperCandidates("1", List.of("lab"), "agent", pageable);
        inOrder.verify(paperSearchabilityService).isSearchable(searchable);
        inOrder.verify(paperSearchabilityService).isSearchable(unsearchable);
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
