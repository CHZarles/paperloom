package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperPublicationServiceTest {

    @Test
    void administratorCanPublishOwnSearchablePaperWithoutReprocessingIt() {
        PaperPublicationRepository publicationRepository = mock(PaperPublicationRepository.class);
        PaperRepository paperRepository = mock(PaperRepository.class);
        PaperSearchabilityService searchabilityService = mock(PaperSearchabilityService.class);
        UserService userService = mock(UserService.class);
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setUserId("9");
        when(userService.isAdminUser("9")).thenReturn(true);
        when(paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc("paper-a", "9"))
                .thenReturn(Optional.of(paper));
        when(searchabilityService.isSearchable(paper)).thenReturn(true);
        when(publicationRepository.findById("paper-a")).thenReturn(Optional.empty());
        when(publicationRepository.save(any(PaperPublication.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperPublication publication = new PaperPublicationService(
                publicationRepository, paperRepository, searchabilityService, userService)
                .publish("paper-a", "9");

        assertEquals("paper-a", publication.getPaperId());
        assertEquals("9", publication.getPublishedBy());
        verify(publicationRepository).save(any(PaperPublication.class));
    }

    @Test
    void ordinaryUserCannotPublish() {
        UserService userService = mock(UserService.class);
        when(userService.isAdminUser("1")).thenReturn(false);

        PaperPublicationService service = new PaperPublicationService(
                mock(PaperPublicationRepository.class),
                mock(PaperRepository.class),
                mock(PaperSearchabilityService.class),
                userService
        );

        assertThrows(CustomException.class, () -> service.publish("paper-a", "1"));
    }
}
