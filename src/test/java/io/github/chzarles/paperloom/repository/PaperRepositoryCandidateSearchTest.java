package io.github.chzarles.paperloom.repository;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModel;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.model.PaperRetrievalIndexStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class PaperRepositoryCandidateSearchTest {

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private PaperReadingModelRepository paperReadingModelRepository;

    @Test
    void noOrgCandidateSearchMatchesMetadataAndExcludesPrivateOrgPapers() {
        paperRepository.saveAll(List.of(
                paper("repo-abstract", "owner", true, null, "Unrelated", "unrelated.pdf", "GraphLoom appears in the abstract."),
                paper("repo-filename", "owner", true, null, "Unrelated", "graphloom-system.pdf", null),
                paper("repo-title", "owner", true, null, "GraphLoom Retrieval", "retrieval.pdf", null),
                paper("repo-private-org", "other-user", false, "lab-a", "GraphLoom Private", "private.pdf", null),
                paper("repo-unrelated", "owner", true, null, "Different Topic", "different.pdf", null)
        ));
        paperRepository.flush();

        Page<Paper> page = paperRepository.searchAccessiblePaperCandidatesWithoutOrgTags(
                "owner",
                "graphloom",
                PageRequest.of(0, 2, Sort.by("paperId"))
        );

        assertEquals(2, page.getContent().size());
        assertEquals(3L, page.getTotalElements());
        assertEquals(List.of("repo-abstract", "repo-filename"),
                page.getContent().stream().map(Paper::getPaperId).toList());
    }

    @Test
    void orgCandidateSearchAllowsPrivateOrgPaperAndBlocksOtherPrivateOrgPapers() {
        paperRepository.saveAll(List.of(
                paper("repo-accessible-org", "other-user", false, "lab-a", "Neural Source Routing", "routing.pdf", null),
                paper("repo-blocked-org", "other-user", false, "lab-b", "Neural Source Routing", "blocked.pdf", null),
                paper("repo-owned", "owner", false, "lab-b", "Owned Neural Source Routing", "owned.pdf", null)
        ));
        paperRepository.flush();

        Page<Paper> page = paperRepository.searchAccessiblePaperCandidates(
                "owner",
                List.of("lab-a"),
                "routing",
                PageRequest.of(0, 10, Sort.by("paperId"))
        );

        assertEquals(2L, page.getTotalElements());
        assertEquals(List.of("repo-accessible-org", "repo-owned"),
                page.getContent().stream().map(Paper::getPaperId).toList());
    }

    @Test
    void noOrgSearchableCandidateSearchFiltersBeforePagingAndCountsSearchableRowsOnly() {
        paperRepository.saveAll(List.of(
                paper("repo-a-searchable", "owner", true, null, "GraphLoom Searchable A", "a.pdf", null),
                paper("repo-b-no-chunks", "owner", true, null, "GraphLoom Not Indexed", "b.pdf", null),
                paper("repo-c-searchable", "owner", true, null, "GraphLoom Searchable C", "c.pdf", null)
        ));
        paperReadingModelRepository.saveAll(List.of(
                indexedModel("repo-a-searchable"),
                indexedModel("repo-c-searchable")
        ));
        paperRepository.flush();
        paperReadingModelRepository.flush();

        Page<Paper> firstPage = paperRepository.searchAccessibleSearchablePaperCandidatesWithoutOrgTags(
                "owner",
                "graphloom",
                PageRequest.of(0, 1, Sort.by("paperId"))
        );
        Page<Paper> secondPage = paperRepository.searchAccessibleSearchablePaperCandidatesWithoutOrgTags(
                "owner",
                "graphloom",
                PageRequest.of(1, 1, Sort.by("paperId"))
        );

        assertEquals(2L, firstPage.getTotalElements());
        assertEquals(List.of("repo-a-searchable"), firstPage.getContent().stream().map(Paper::getPaperId).toList());
        assertEquals(2L, secondPage.getTotalElements());
        assertEquals(List.of("repo-c-searchable"), secondPage.getContent().stream().map(Paper::getPaperId).toList());
    }

    private Paper paper(String paperId,
                        String userId,
                        boolean isPublic,
                        String orgTag,
                        String paperTitle,
                        String originalFilename,
                        String abstractText) {
        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setUserId(userId);
        paper.setPublic(isPublic);
        paper.setOrgTag(orgTag);
        paper.setPaperTitle(paperTitle);
        paper.setOriginalFilename(originalFilename);
        paper.setAbstractText(abstractText);
        paper.setStatus(Paper.STATUS_COMPLETED);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_COMPLETED);
        return paper;
    }

    private PaperReadingModel indexedModel(String paperId) {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion("rm-1");
        model.setCurrent(true);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setRetrievalIndexStatus(PaperRetrievalIndexStatus.READY);
        model.setRetrievalIndexGeneration("generation-1");
        model.setRetrievalEmbeddingContract("collection|embedding-v1|3");
        model.setRetrievalIndexedLocationCount(1);
        return model;
    }
}
