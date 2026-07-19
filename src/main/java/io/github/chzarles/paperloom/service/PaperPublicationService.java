package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperPublicationService {

    private final PaperPublicationRepository publicationRepository;
    private final PaperRepository paperRepository;
    private final PaperSearchabilityService searchabilityService;
    private final UserService userService;

    public PaperPublicationService(PaperPublicationRepository publicationRepository,
                                   PaperRepository paperRepository,
                                   PaperSearchabilityService searchabilityService,
                                   UserService userService) {
        this.publicationRepository = publicationRepository;
        this.paperRepository = paperRepository;
        this.searchabilityService = searchabilityService;
        this.userService = userService;
    }

    @Transactional
    public PaperPublication publish(String paperId, String administratorId) {
        requireAdmin(administratorId);
        Paper adminPaper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(
                        paperId, administratorId)
                .orElseThrow(() -> new CustomException(
                        "Paper is not in the administrator library", HttpStatus.CONFLICT));
        if (!searchabilityService.isSearchable(adminPaper)) {
            throw new CustomException("PAPER_NOT_READY", HttpStatus.CONFLICT);
        }
        return publicationRepository.findById(paperId).orElseGet(() -> {
            PaperPublication publication = new PaperPublication();
            publication.setPaperId(paperId);
            publication.setPublishedBy(administratorId);
            return publicationRepository.save(publication);
        });
    }

    @Transactional
    public void unpublish(String paperId, String administratorId) {
        requireAdmin(administratorId);
        publicationRepository.deleteByPaperId(paperId);
    }

    private void requireAdmin(String userId) {
        if (!userService.isAdminUser(userId)) {
            throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        }
    }
}
