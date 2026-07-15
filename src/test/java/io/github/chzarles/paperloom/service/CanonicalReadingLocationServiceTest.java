package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPage;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperLocationRepository;
import io.github.chzarles.paperloom.repository.PaperPageRepository;
import io.github.chzarles.paperloom.repository.PaperReadingElementRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperSectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalReadingLocationServiceTest {

    private final PaperLocationRepository locationRepository = mock(PaperLocationRepository.class);
    private final PaperReadingModelRepository modelRepository = mock(PaperReadingModelRepository.class);
    private final PaperPageRepository pageRepository = mock(PaperPageRepository.class);
    private final PaperSectionRepository sectionRepository = mock(PaperSectionRepository.class);
    private final PaperReadingElementRepository elementRepository = mock(PaperReadingElementRepository.class);
    private final PaperRepository paperRepository = mock(PaperRepository.class);
    private CanonicalReadingLocationService service;

    @BeforeEach
    void setUp() {
        service = new CanonicalReadingLocationService(
                locationRepository,
                modelRepository,
                pageRepository,
                sectionRepository,
                elementRepository,
                paperRepository,
                new ObjectMapper()
        );
        when(paperRepository.findByPaperIdIn(List.of("paper-a"))).thenReturn(List.of());
    }

    @Test
    void staleLocationFromOldModelCannotBeRead() {
        PaperLocation location = pageLocation("rm-old");
        when(locationRepository.findByLocationRefIn(List.of("location_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("location_ref_a"), List.of("paper-a"));

        assertEquals(List.of(), result.items());
        assertEquals(List.of("location_ref_a"), result.missingLocationRefs());
        verifyNoInteractions(pageRepository);
    }

    @Test
    void currentPageLocationReturnsExactCanonicalContent() {
        PaperLocation location = pageLocation("rm-current");
        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm-current");
        page.setPageNumber(2);
        page.setPageText("Exact canonical page content.");
        page.setParserName("mineru");
        page.setParserVersion("1");
        when(locationRepository.findByLocationRefIn(List.of("location_ref_a"))).thenReturn(List.of(location));
        when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a"))
                .thenReturn(Optional.of(readyModel("rm-current")));
        when(pageRepository.findFirstByPaperIdAndModelVersionAndPageNumber("paper-a", "rm-current", 2))
                .thenReturn(Optional.of(page));

        CanonicalReadingLocationService.ReadBatch result = service.read(
                List.of("location_ref_a"), List.of("paper-a"));

        assertEquals(1, result.items().size());
        assertEquals("Exact canonical page content.", result.items().get(0).spanText());
        assertEquals(List.of(), result.missingLocationRefs());
    }

    private PaperLocation pageLocation(String modelVersion) {
        PaperLocation location = new PaperLocation();
        location.setLocationRef("location_ref_a");
        location.setPaperId("paper-a");
        location.setModelVersion(modelVersion);
        location.setLocationType(PaperLocationType.PAGE);
        location.setPageNumber(2);
        location.setSectionTitle("Methods");
        return location;
    }

    private PaperReadingModel readyModel(String modelVersion) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        return model;
    }
}
