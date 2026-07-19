package io.github.chzarles.paperloom.config;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.PaperPublicationRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class PaperPublicationBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaperPublicationBackfill.class);

    private final PaperRepository paperRepository;
    private final PaperPublicationRepository publicationRepository;
    private final UserRepository userRepository;
    private final PaperSearchabilityService searchabilityService;

    public PaperPublicationBackfill(PaperRepository paperRepository,
                                    PaperPublicationRepository publicationRepository,
                                    UserRepository userRepository,
                                    PaperSearchabilityService searchabilityService) {
        this.paperRepository = paperRepository;
        this.publicationRepository = publicationRepository;
        this.userRepository = userRepository;
        this.searchabilityService = searchabilityService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Paper> legacyPublic = paperRepository.findAllByIsPublicTrue();
        for (Paper paper : legacyPublic) {
            if (isAdmin(paper.getUserId())
                    && searchabilityService.isSearchable(paper)
                    && !publicationRepository.existsByPaperId(paper.getPaperId())) {
                PaperPublication publication = new PaperPublication();
                publication.setPaperId(paper.getPaperId());
                publication.setPublishedBy(paper.getUserId());
                publicationRepository.save(publication);
            } else {
                log.info("Legacy public paper remains private after access migration: paperId={}, owner={}",
                        paper.getPaperId(), paper.getUserId());
            }
            paper.setPublic(false);
        }
        if (!legacyPublic.isEmpty()) {
            paperRepository.saveAll(legacyPublic);
        }
    }

    private boolean isAdmin(String userId) {
        Optional<User> user;
        try {
            user = userRepository.findById(Long.parseLong(userId));
        } catch (NumberFormatException ignored) {
            user = userRepository.findByUsername(userId);
        }
        return user.map(candidate -> candidate.getRole() == User.Role.ADMIN).orElse(false);
    }
}
