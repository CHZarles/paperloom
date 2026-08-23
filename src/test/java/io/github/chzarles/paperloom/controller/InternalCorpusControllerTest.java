package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.PaperReadingService;
import io.github.chzarles.paperloom.service.PaperReadingException;
import io.github.chzarles.paperloom.service.CanonicalReadingLocationService;
import io.github.chzarles.paperloom.service.HarnessReadingService;
import io.github.chzarles.paperloom.service.PaperSourceQuoteReadService;
import io.github.chzarles.paperloom.service.ReadingModelQdrantIndexService;
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
    void malformedJsonFieldTypesAndArrayShapesReturnStableArgumentErrors() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalCorpusController(
                mock(PaperReadingService.class), mock(HarnessReadingService.class),
                mock(ReadingModelQdrantIndexService.class), "internal-secret")).build();

        for (String body : List.of(
                "{",
                "{\"user_id\":7,\"paper_ids\":[\"paper-a\"],\"top_k\":\"bad\"}",
                "{\"user_id\":7,\"paper_ids\":\"paper-a\"}")) {
            mvc.perform(post("/internal/v1/corpus/locations/search")
                            .header("Authorization", "Bearer internal-secret")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("READING_ARGUMENTS_INVALID"));
        }
    }

    @Test
    void paperReadingErrorsExposeStableCodesAndSafeMessages() throws Exception {
        PaperReadingService readingService = mock(PaperReadingService.class);
        when(readingService.searchPaperContent(any())).thenThrow(new PaperReadingException(
                "PAPER_NOT_AVAILABLE", "The requested paper is not available"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalCorpusController(
                readingService, mock(HarnessReadingService.class),
                mock(ReadingModelQdrantIndexService.class), "internal-secret")).build();

        mvc.perform(post("/internal/v1/corpus/locations/search")
                        .header("Authorization", "Bearer internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":7,\"paper_ids\":[\"paper-a\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAPER_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.message").value("The requested paper is not available"));
    }

    @Test
    void sourceQuoteFailuresRemainTechnical() throws Exception {
        HarnessReadingService harnessReadingService = mock(HarnessReadingService.class);
        when(harnessReadingService.readAndCreateQuotes(any()))
                .thenThrow(new IllegalArgumentException("PASSAGE_SOURCE_SPAN_INVALID"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalCorpusController(
                mock(PaperReadingService.class), harnessReadingService,
                mock(ReadingModelQdrantIndexService.class), "internal-secret")).build();

        mvc.perform(post("/internal/v1/corpus/locations/read")
                        .header("Authorization", "Bearer internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":7,\"location_refs\":[\"location-a\"]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("RETRIEVAL_UNAVAILABLE"));
    }

    @Test
    void blankInternalTokenRejectsCorpusRequests() throws Exception {
        PaperReadingService readingService = mock(PaperReadingService.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        when(readingService.searchPaperCandidates(any())).thenReturn(
                new PaperReadingService.PaperSearchResult("", List.of(), 0, 0, "complete", null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(readingService, mock(HarnessReadingService.class), indexService, "")
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
        PaperReadingService readingService = mock(PaperReadingService.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        when(readingService.searchPaperContent(any())).thenReturn(
                new PaperReadingService.ContentSearchResult(List.of(), 12, 8, "test-index"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(readingService, mock(HarnessReadingService.class), indexService, "internal-secret")
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
                .andExpect(jsonPath("$.coverage").value("truncated"))
                .andExpect(jsonPath("$.evidence_payloads").doesNotExist());
    }

    @Test
    void locationReadExposesCanonicalVisualEvidenceFields() throws Exception {
        PaperReadingService readingService = mock(PaperReadingService.class);
        HarnessReadingService harnessReadingService = mock(HarnessReadingService.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        when(harnessReadingService.readAndCreateQuotes(any())).thenReturn(
                new PaperSourceQuoteReadService.ReadResult(
                        List.of(new PaperSourceQuoteReadService.ReadItem(
                                new CanonicalReadingLocationService.CanonicalLocation(
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
                                ),
                                List.of(new PaperSourceQuoteReadService.SourceQuote(
                                        "source_quote_a", "paper-a", "rm-current", "location_ref_a", "TABLE",
                                        2, 2, "Methods", "TABLE", "Canonical table text.", "{\"bbox\":{\"x\":1}}"
                                ))
                        )),
                        List.of()
                )
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(readingService, harnessReadingService, indexService, "internal-secret")
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
                .andExpect(jsonPath("$.items[0].asset_warnings").isArray())
                .andExpect(jsonPath("$.items[0].source_quotes[0].source_quote_ref").value("source_quote_a"));
    }

    @Test
    void currentModelCandidateExportReturnsMySqlPayloadWithoutSearch() throws Exception {
        PaperReadingService readingService = mock(PaperReadingService.class);
        ReadingModelQdrantIndexService indexService = mock(ReadingModelQdrantIndexService.class);
        when(indexService.exportCurrentModelLocations("paper-a")).thenReturn(
                new ReadingModelQdrantIndexService.CandidateExport(
                        "paper-a", "rm-current", "READING_MODEL_READY", "READY", "hybrid-v1",
                        "mineru", "1", List.of(new ReadingModelQdrantIndexService.IndexedLocation(
                        "paper-a", "rm-current", "passage_ref_a", 3, "search text", java.util.Map.of(
                        "location_type", "PASSAGE",
                        "section_path", "Methods",
                        "content_text", "Canonical passage text.",
                        "content_hash", "hash-a",
                        "source_span_json", "{\"spans\":[]}"
                )))));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new InternalCorpusController(readingService, mock(HarnessReadingService.class), indexService, "internal-secret")
        ).build();

        mvc.perform(post("/internal/v1/corpus/locations/export")
                        .header("Authorization", "Bearer internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paper_id":"paper-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model_version").value("rm-current"))
                .andExpect(jsonPath("$.candidates[0].location_ref").value("passage_ref_a"))
                .andExpect(jsonPath("$.candidates[0].section").value("Methods"))
                .andExpect(jsonPath("$.candidates[0].content").value("Canonical passage text."));
    }
}
