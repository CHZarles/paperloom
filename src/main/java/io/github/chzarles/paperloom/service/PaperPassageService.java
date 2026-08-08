package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperSection;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPassageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PaperPassageService {

    private final PaperPassageRepository passageRepository;
    private final PaperSectionRepository sectionRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperReadingElementRepository elementRepository;
    private final StructuralPassageBuilder builder;

    public PaperPassageService(PaperPassageRepository passageRepository,
                               PaperSectionRepository sectionRepository,
                               PaperLocationRepository locationRepository,
                               PaperReadingElementRepository elementRepository,
                               StructuralPassageBuilder builder) {
        this.passageRepository = passageRepository;
        this.sectionRepository = sectionRepository;
        this.locationRepository = locationRepository;
        this.elementRepository = elementRepository;
        this.builder = builder;
    }

    @Transactional
    public PassageBuildResult rebuild(String paperId, String modelVersion, String userId) {
        List<PaperSection> sections = sectionRepository
                .findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc(paperId, modelVersion);
        List<PaperLocation> locations = locationRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(paperId, modelVersion);
        List<PaperReadingElement> elements = elementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, modelVersion);
        PassageBuildResult result = builder.build(paperId, modelVersion, sections, locations, elements, userId);

        locationRepository.deleteByPaperIdAndModelVersionAndLocationType(
                paperId, modelVersion, PaperLocationType.PASSAGE);
        passageRepository.deleteByPaperIdAndModelVersion(paperId, modelVersion);
        passageRepository.saveAll(result.passages());
        locationRepository.saveAll(result.locations());
        return result;
    }
}
