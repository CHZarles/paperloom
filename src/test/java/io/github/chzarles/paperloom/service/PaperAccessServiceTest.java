package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperAccessServiceTest {

    @Test
    void accessiblePapersArePersonalMembershipsPlusGlobalPublicationsWithoutDuplicates() {
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperPublicationRepository publicationRepository = mock(PaperPublicationRepository.class);
        Paper own = paper("paper-a", "1", 2);
        Paper otherCopy = paper("paper-a", "2", 3);
        Paper global = paper("paper-b", "2", 1);
        when(paperRepository.findByUserId("1")).thenReturn(List.of(own));
        when(publicationRepository.findAll()).thenReturn(List.of(
                publication("paper-a", "2"),
                publication("paper-b", "2")
        ));
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("paper-a", "2"))
                .thenReturn(java.util.Optional.of(otherCopy));
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("paper-b", "2"))
                .thenReturn(java.util.Optional.of(global));

        List<Paper> accessible = new PaperAccessService(paperRepository, publicationRepository)
                .accessiblePapers("1");

        assertEquals(List.of("paper-a", "paper-b"), accessible.stream().map(Paper::getPaperId).toList());
        assertEquals("1", accessible.get(0).getUserId());
    }

    @Test
    void globalPublicationGrantsAccessWithoutChangingUploadOwnership() {
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperPublicationRepository publicationRepository = mock(PaperPublicationRepository.class);
        when(publicationRepository.existsByPaperId("paper-a")).thenReturn(true);

        assertTrue(new PaperAccessService(paperRepository, publicationRepository)
                .canAccess("1", "paper-a"));
    }

    private Paper paper(String paperId, String userId, int day) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setUserId(userId);
        paper.setCreatedAt(LocalDateTime.of(2026, 7, day, 0, 0));
        return paper;
    }

    private PaperPublication publication(String paperId, String publishedBy) {
        PaperPublication publication = new PaperPublication();
        publication.setPaperId(paperId);
        publication.setPublishedBy(publishedBy);
        return publication;
    }
}
