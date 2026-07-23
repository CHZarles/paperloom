package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.CorpusRetrievalService;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalCorpusControllerTest {

    @Test
    void blankInternalTokenRejectsCorpusRequests() throws Exception {
        CorpusRetrievalService retrievalService = mock(CorpusRetrievalService.class);
        when(retrievalService.searchPapers(any())).thenReturn(
                new CorpusRetrievalService.PaperSearchResult("", List.of(), 0, 0, "complete", null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(retrievalService, "")
        ).build();

        mvc.perform(post("/internal/v1/corpus/papers/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "user_id": 7,
                                  "scope_paper_ids": ["paper-a"],
                                  "query_text": ""
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void locationSearchPreservesModelVisibleCoverageContract() throws Exception {
        CorpusRetrievalService retrievalService = mock(CorpusRetrievalService.class);
        when(retrievalService.searchLocations(any())).thenReturn(
                new CorpusRetrievalService.LocationSearchResult(List.of(), 12, 8, "test-index"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(retrievalService, "internal-secret")
        ).build();

        mvc.perform(post("/internal/v1/corpus/locations/search")
                        .header("Authorization", "Bearer internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "user_id": 7,
                                  "scope_paper_ids": ["paper-a"],
                                  "paper_ids": ["paper-a"],
                                  "query_text": "agent evaluation",
                                  "top_k": 8
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query_text").value("agent evaluation"))
                .andExpect(jsonPath("$.coverage").value("truncated"));
    }

    @Test
    void locationReadExposesCanonicalVisualEvidenceFields() throws Exception {
        CorpusRetrievalService retrievalService = mock(CorpusRetrievalService.class);
        when(retrievalService.readLocations(any())).thenReturn(
                new CanonicalReadingLocationService.ReadBatch(
                        List.of(new CanonicalReadingLocationService.CanonicalLocation(
                                "paper-a",
                                "Paper A",
                                "rm-current",
                                "location_ref_a",
                                "table",
                                2,
                                3,
                                "Methods",
                                "Canonical table text.",
                                "{\"x\":1}",
                                "mineru",
                                "1",
                                "table-1",
                                true,
                                true,
                                true,
                                false,
                                List.of()
                        )),
                        List.of()
                )
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(retrievalService, "internal-secret")
        ).build();

        mvc.perform(post("/internal/v1/corpus/locations/read")
                        .header("Authorization", "Bearer internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "user_id": 7,
                                  "scope_paper_ids": ["paper-a"],
                                  "location_refs": ["location_ref_a"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].location_ref").value("location_ref_a"))
                .andExpect(jsonPath("$.items[0].bbox_json").value("{\"x\":1}"))
                .andExpect(jsonPath("$.items[0].page_screenshot_available").value(true))
                .andExpect(jsonPath("$.items[0].pdf_evidence_available").value(true))
                .andExpect(jsonPath("$.items[0].table_screenshot_available").value(true))
                .andExpect(jsonPath("$.items[0].figure_screenshot_available").value(false))
                .andExpect(jsonPath("$.items[0].asset_warnings").isArray());
    }
}
