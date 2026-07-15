package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.CorpusRetrievalService;
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
}
