package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingToolArgumentValidatorTest {

    private ReadingToolArgumentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReadingToolArgumentValidator();
    }

    @Test
    void searchRejectsForbiddenFieldsAtAnyDepthAndUnsupportedTopLevelFields() {
        ReadingToolArgumentValidator.ValidationResult forbidden = validator.validateSearchPaperCandidates(Map.of(
                "queryText", "agentic eval",
                "options", Map.of("topK", 20)
        ));
        ReadingToolArgumentValidator.ValidationResult unsupported = validator.validateSearchPaperCandidates(Map.of(
                "queryText", "agentic eval",
                "metadataFilters", Map.of()
        ));

        assertFalse(forbidden.valid());
        assertEquals("forbidden_argument", forbidden.error());
        assertEquals("topK", forbidden.argument());
        assertFalse(unsupported.valid());
        assertEquals("unsupported_argument", unsupported.error());
        assertEquals("metadataFilters", unsupported.argument());
    }

    @Test
    void searchRejectsMissingOrTokenlessQueryText() {
        assertFalse(validator.validateSearchPaperCandidates(Map.of()).valid());
        assertFalse(validator.validateSearchPaperCandidates(Map.of("queryText", "a")).valid());
        assertTrue(validator.validateSearchPaperCandidates(Map.of("queryText", "agentic eval")).valid());
    }

    @Test
    void getSessionStateAcceptsEmptyArgumentsOnly() {
        ReadingToolArgumentValidator.ValidationResult rawId = validator.validateGetSessionState(Map.of(
                "paperId", "ready-paper"
        ));
        ReadingToolArgumentValidator.ValidationResult unsupported = validator.validateGetSessionState(Map.of(
                "includeFacets", true
        ));

        assertTrue(validator.validateGetSessionState(Map.of()).valid());
        assertFalse(rawId.valid());
        assertEquals("forbidden_argument", rawId.error());
        assertEquals("paperId", rawId.argument());
        assertFalse(unsupported.valid());
        assertEquals("unsupported_argument", unsupported.error());
        assertEquals("includeFacets", unsupported.argument());
    }

    @Test
    void listPapersRejectsSemanticRawOrdinalControlAndUnsupportedFilterArguments() {
        ReadingToolArgumentValidator.ValidationResult query =
                validator.validateListPapers(Map.of("queryText", "agentic eval"));
        ReadingToolArgumentValidator.ValidationResult rawId =
                validator.validateListPapers(Map.of(
                        "filters", Map.of("paperId", "ready-paper")
                ));
        ReadingToolArgumentValidator.ValidationResult ordinal =
                validator.validateListPapers(Map.of(
                        "filters", Map.of("titleContains", "agent"),
                        "ordinal", 1
                ));
        ReadingToolArgumentValidator.ValidationResult catalogTopic =
                validator.validateListPapers(Map.of(
                        "filters", Map.of("catalogTopicIds", List.of("topic_abc"))
                ));
        ReadingToolArgumentValidator.ValidationResult pageSize =
                validator.validateListPapers(Map.of("pageSize", 50));
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateListPapers(Map.of(
                        "filters", Map.of(
                                "titleContains", "agent",
                                "filenameExact", "agentic-eval.pdf",
                                "yearRange", Map.of("from", 2023, "to", 2026)
                        ),
                        "includeFacets", true,
                        "sort", "YEAR"
                ));

        assertFalse(query.valid());
        assertEquals("forbidden_argument", query.error());
        assertEquals("queryText", query.argument());
        assertFalse(rawId.valid());
        assertEquals("forbidden_argument", rawId.error());
        assertEquals("paperId", rawId.argument());
        assertFalse(ordinal.valid());
        assertEquals("forbidden_argument", ordinal.error());
        assertEquals("ordinal", ordinal.argument());
        assertFalse(catalogTopic.valid());
        assertEquals("unsupported_argument", catalogTopic.error());
        assertEquals("catalogTopicIds", catalogTopic.argument());
        assertFalse(pageSize.valid());
        assertEquals("forbidden_argument", pageSize.error());
        assertEquals("pageSize", pageSize.argument());
        assertTrue(valid.valid());
        assertEquals(new ReadingToolArgumentValidator.ListPaperFilters(
                        "agent",
                        "",
                        "",
                        "agentic-eval.pdf",
                        "",
                        "",
                        "",
                        new ReadingToolArgumentValidator.YearRange(2023, 2026),
                        ""
                ),
                validator.listPaperFilters(Map.of(
                        "titleContains", "agent",
                        "filenameExact", "agentic-eval.pdf",
                        "yearRange", Map.of("from", 2023, "to", 2026)
                )));
        assertTrue(validator.includeFacets(true));
        assertEquals(ReadingToolArgumentValidator.ListPaperSort.YEAR, validator.listPaperSort("YEAR"));
    }

    @Test
    void listPapersRejectsInvalidYearRangeAndSort() {
        ReadingToolArgumentValidator.ValidationResult invalidYear =
                validator.validateListPapers(Map.of(
                        "filters", Map.of("yearRange", Map.of("from", 2026, "to", 2023))
                ));
        ReadingToolArgumentValidator.ValidationResult invalidSort =
                validator.validateListPapers(Map.of("sort", "RELEVANCE"));

        assertFalse(invalidYear.valid());
        assertEquals("invalid_year_range", invalidYear.error());
        assertEquals("yearRange", invalidYear.argument());
        assertFalse(invalidSort.valid());
        assertEquals("unsupported_sort", invalidSort.error());
        assertEquals("sort", invalidSort.argument());
        assertEquals(ReadingToolArgumentValidator.ListPaperSort.RECENT, validator.listPaperSort(null));
    }

    @Test
    void identityLookupAcceptsOnlyCanonicalIdentityHints() {
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", " LoRA ",
                                "filenameExact", " lora.pdf ",
                                "doiExact", " DOI:https://doi.org/10.48550/ARXIV.2106.09685 ",
                                "arxivIdExact", " https://arxiv.org/abs/2106.09685v2 ",
                                "authorName", " Hu ",
                                "year", 2021
                        )
                ));

        assertTrue(valid.valid());
        assertEquals(new ReadingToolArgumentValidator.IdentityHints(
                        "LoRA",
                        "",
                        "",
                        "lora.pdf",
                        "10.48550/arxiv.2106.09685",
                        "2106.09685",
                        "Hu",
                        2021
                ),
                validator.identityHints(Map.of(
                        "titleContains", " LoRA ",
                        "filenameExact", " lora.pdf ",
                        "doiExact", " DOI:https://doi.org/10.48550/ARXIV.2106.09685 ",
                        "arxivIdExact", " https://arxiv.org/abs/2106.09685v2 ",
                        "authorName", " Hu ",
                        "year", 2021
                )));
    }

    @Test
    void identityLookupNormalizesCommonFilenameAlias() {
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of("filename", "Exact>2412.08972.pdf")
                ));

        assertTrue(valid.valid());
        assertEquals(new ReadingToolArgumentValidator.IdentityHints(
                        "",
                        "",
                        "",
                        "2412.08972.pdf",
                        "",
                        "",
                        "",
                        null
                ),
                validator.identityHints(Map.of("filename", "Exact>2412.08972.pdf")));
    }

    @Test
    void identityLookupNormalizesFilenameAliasWithoutSeparator() {
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of("filename", "Exact2412.08972.pdf")
                ));

        assertTrue(valid.valid());
        assertEquals(new ReadingToolArgumentValidator.IdentityHints(
                        "",
                        "",
                        "",
                        "2412.08972.pdf",
                        "",
                        "",
                        "",
                        null
                ),
                validator.identityHints(Map.of("filename", "Exact2412.08972.pdf")));
    }

    @Test
    void identityLookupRejectsMissingEmptyYearOnlyAndInvalidYearHints() {
        ReadingToolArgumentValidator.ValidationResult missing =
                validator.validateFindPapersByIdentity(Map.of());
        ReadingToolArgumentValidator.ValidationResult nonObject =
                validator.validateFindPapersByIdentity(Map.of("identityHints", "LoRA"));
        ReadingToolArgumentValidator.ValidationResult empty =
                validator.validateFindPapersByIdentity(Map.of("identityHints", Map.of()));
        ReadingToolArgumentValidator.ValidationResult yearOnly =
                validator.validateFindPapersByIdentity(Map.of("identityHints", Map.of("year", 2021)));
        ReadingToolArgumentValidator.ValidationResult invalidYear =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "year", 0
                        )
                ));

        assertFalse(missing.valid());
        assertEquals("missing_argument", missing.error());
        assertEquals("identityHints", missing.argument());
        assertFalse(nonObject.valid());
        assertEquals("unsupported_argument", nonObject.error());
        assertEquals("identityHints", nonObject.argument());
        assertFalse(empty.valid());
        assertEquals("missing_argument", empty.error());
        assertEquals("identityHints", empty.argument());
        assertFalse(yearOnly.valid());
        assertEquals("missing_argument", yearOnly.error());
        assertEquals("identityHints", yearOnly.argument());
        assertFalse(invalidYear.valid());
        assertEquals("invalid_year", invalidYear.error());
        assertEquals("year", invalidYear.argument());
    }

    @Test
    void identityLookupRejectsForbiddenAndUnsupportedArgumentsAtAnyDepth() {
        ReadingToolArgumentValidator.ValidationResult topLevel =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of("titleContains", "LoRA"),
                        "sort", "TITLE"
                ));
        ReadingToolArgumentValidator.ValidationResult nestedUnsupported =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "venue", "ICLR"
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult rawId =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "paperId", "raw-paper"
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult paperHandle =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "paperHandle", "paper_handle_abc"
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult semantic =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "options", Map.of("queryText", "low rank adaptation")
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult ordinal =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "ordinal", 1
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult pageSize =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "pageSize", 50
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult score =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "score", 0.8
                        )
                ));
        ReadingToolArgumentValidator.ValidationResult rank =
                validator.validateFindPapersByIdentity(Map.of(
                        "identityHints", Map.of(
                                "titleContains", "LoRA",
                                "rank", 1
                        )
                ));

        assertFalse(topLevel.valid());
        assertEquals("unsupported_argument", topLevel.error());
        assertEquals("sort", topLevel.argument());
        assertFalse(nestedUnsupported.valid());
        assertEquals("unsupported_argument", nestedUnsupported.error());
        assertEquals("venue", nestedUnsupported.argument());
        assertFalse(rawId.valid());
        assertEquals("forbidden_argument", rawId.error());
        assertEquals("paperId", rawId.argument());
        assertFalse(paperHandle.valid());
        assertEquals("forbidden_argument", paperHandle.error());
        assertEquals("paperHandle", paperHandle.argument());
        assertFalse(semantic.valid());
        assertEquals("forbidden_argument", semantic.error());
        assertEquals("queryText", semantic.argument());
        assertFalse(ordinal.valid());
        assertEquals("forbidden_argument", ordinal.error());
        assertEquals("ordinal", ordinal.argument());
        assertFalse(pageSize.valid());
        assertEquals("forbidden_argument", pageSize.error());
        assertEquals("pageSize", pageSize.argument());
        assertFalse(score.valid());
        assertEquals("forbidden_argument", score.error());
        assertEquals("score", score.argument());
        assertFalse(rank.valid());
        assertEquals("forbidden_argument", rank.error());
        assertEquals("rank", rank.argument());
    }

    @Test
    void readingLocationsRejectsEmptyHandlesUnsupportedTypesAndIntentAliases() {
        ReadingToolArgumentValidator.ValidationResult emptyHandles = validator.validateFindReadingLocations(Map.of(
                "paperHandles", List.of(),
                "queryText", "methods"
        ));
        ReadingToolArgumentValidator.ValidationResult unsupportedType = validator.validateFindReadingLocations(Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "queryText", "methods",
                "locationTypes", List.of("SECTION", "APPENDIX")
        ));
        ReadingToolArgumentValidator.ValidationResult alias = validator.validateFindReadingLocations(Map.of(
                "paperHandles", List.of("paper_handle_abc"),
                "queryText", "methods",
                "readingNeed", "find limitations"
        ));

        assertFalse(emptyHandles.valid());
        assertEquals("missing_argument", emptyHandles.error());
        assertEquals("paperHandles", emptyHandles.argument());
        assertFalse(unsupportedType.valid());
        assertEquals("unsupported_location_type", unsupportedType.error());
        assertEquals("APPENDIX", unsupportedType.argument());
        assertFalse(alias.valid());
        assertEquals("forbidden_argument", alias.error());
        assertEquals("readingNeed", alias.argument());
    }

    @Test
    void listPaperLocationsRequiresHandlesAndRejectsSemanticOrdinalAndRangeControls() {
        ReadingToolArgumentValidator.ValidationResult missingHandles =
                validator.validateListPaperLocations(Map.of());
        ReadingToolArgumentValidator.ValidationResult queryText =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "methods"
                ));
        ReadingToolArgumentValidator.ValidationResult ordinal =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "ordinal", 1
                ));
        ReadingToolArgumentValidator.ValidationResult unsupportedTopLevel =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "metadataFilters", Map.of()
                ));
        ReadingToolArgumentValidator.ValidationResult badRange =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "pageRange", Map.of("from", 4, "to", 3)
                ));
        ReadingToolArgumentValidator.ValidationResult badType =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "locationTypes", List.of("PAGE", "APPENDIX")
                ));
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateListPaperLocations(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "pageRange", Map.of("from", 3, "to", 3),
                        "locationTypes", List.of("PAGE", "SECTION")
                ));

        assertFalse(missingHandles.valid());
        assertEquals("missing_argument", missingHandles.error());
        assertEquals("paperHandles", missingHandles.argument());
        assertFalse(queryText.valid());
        assertEquals("forbidden_argument", queryText.error());
        assertEquals("queryText", queryText.argument());
        assertFalse(ordinal.valid());
        assertEquals("forbidden_argument", ordinal.error());
        assertEquals("ordinal", ordinal.argument());
        assertFalse(unsupportedTopLevel.valid());
        assertEquals("unsupported_argument", unsupportedTopLevel.error());
        assertEquals("metadataFilters", unsupportedTopLevel.argument());
        assertFalse(badRange.valid());
        assertEquals("invalid_page_range", badRange.error());
        assertEquals("pageRange", badRange.argument());
        assertFalse(badType.valid());
        assertEquals("unsupported_location_type", badType.error());
        assertEquals("APPENDIX", badType.argument());
        assertTrue(valid.valid());
        assertEquals(new ReadingToolArgumentValidator.PageRange(3, 3),
                validator.pageRange(Map.of("from", 3, "to", 3)));
    }

    @Test
    void getPaperOutlineRequiresHandlesAndRejectsSemanticOrdinalsAndControls() {
        ReadingToolArgumentValidator.ValidationResult missing =
                validator.validateGetPaperOutline(Map.of());
        ReadingToolArgumentValidator.ValidationResult query =
                validator.validateGetPaperOutline(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "queryText", "methods"
                ));
        ReadingToolArgumentValidator.ValidationResult ordinal =
                validator.validateGetPaperOutline(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "ordinal", 1
                ));
        ReadingToolArgumentValidator.ValidationResult pageRange =
                validator.validateGetPaperOutline(Map.of(
                        "paperHandles", List.of("paper_handle_abc"),
                        "pageRange", Map.of("from", 1, "to", 2)
                ));
        ReadingToolArgumentValidator.ValidationResult valid =
                validator.validateGetPaperOutline(Map.of(
                        "paperHandles", List.of("paper_handle_abc")
                ));

        assertFalse(missing.valid());
        assertEquals("missing_argument", missing.error());
        assertEquals("paperHandles", missing.argument());
        assertFalse(query.valid());
        assertEquals("forbidden_argument", query.error());
        assertEquals("queryText", query.argument());
        assertFalse(ordinal.valid());
        assertEquals("forbidden_argument", ordinal.error());
        assertEquals("ordinal", ordinal.argument());
        assertFalse(pageRange.valid());
        assertEquals("forbidden_argument", pageRange.error());
        assertEquals("pageRange", pageRange.argument());
        assertTrue(valid.valid());
    }

    @Test
    void readLocationsRejectsEmptyRefsAndForbiddenReadControls() {
        ReadingToolArgumentValidator.ValidationResult emptyRefs = validator.validateReadLocations(Map.of(
                "locationRefs", List.of()
        ));
        ReadingToolArgumentValidator.ValidationResult queryAlias = validator.validateReadLocations(Map.of(
                "locationRefs", List.of("page_ref_abc"),
                "queryText", "methods"
        ));
        ReadingToolArgumentValidator.ValidationResult sourceQuoteInput = validator.validateReadLocations(Map.of(
                "locationRefs", List.of("page_ref_abc"),
                "sourceQuoteRef", "source_quote_abc"
        ));
        ReadingToolArgumentValidator.ValidationResult ordinalRef = validator.validateReadLocations(Map.of(
                "locationRefs", List.of("1")
        ));
        ReadingToolArgumentValidator.ValidationResult candidateRef = validator.validateReadLocations(Map.of(
                "locationRefs", List.of("candidate 2")
        ));
        ReadingToolArgumentValidator.ValidationResult valid = validator.validateReadLocations(Map.of(
                "locationRefs", List.of("page_ref_abc")
        ));

        assertFalse(emptyRefs.valid());
        assertEquals("missing_argument", emptyRefs.error());
        assertEquals("locationRefs", emptyRefs.argument());
        assertFalse(queryAlias.valid());
        assertEquals("forbidden_argument", queryAlias.error());
        assertEquals("queryText", queryAlias.argument());
        assertFalse(sourceQuoteInput.valid());
        assertEquals("forbidden_argument", sourceQuoteInput.error());
        assertEquals("sourceQuoteRef", sourceQuoteInput.argument());
        assertFalse(ordinalRef.valid());
        assertEquals("invalid_location_ref", ordinalRef.error());
        assertEquals("locationRefs", ordinalRef.argument());
        assertFalse(candidateRef.valid());
        assertEquals("invalid_location_ref", candidateRef.error());
        assertEquals("locationRefs", candidateRef.argument());
        assertTrue(valid.valid());
    }

    @Test
    void traceSourceQuotesRejectsEmptyRefsForbiddenControlsAndDisplayReferences() {
        ReadingToolArgumentValidator.ValidationResult emptyRefs = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of()
        ));
        ReadingToolArgumentValidator.ValidationResult locationInput = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of("source_quote_abc"),
                "locationRef", "page_ref_abc"
        ));
        ReadingToolArgumentValidator.ValidationResult queryAlias = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of("source_quote_abc"),
                "queryText", "explain"
        ));
        ReadingToolArgumentValidator.ValidationResult numberedCitation = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of("[1]")
        ));
        ReadingToolArgumentValidator.ValidationResult ordinalRef = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of("1")
        ));
        ReadingToolArgumentValidator.ValidationResult valid = validator.validateTraceSourceQuotes(Map.of(
                "sourceQuoteRefs", List.of("source_quote_abc")
        ));

        assertFalse(emptyRefs.valid());
        assertEquals("missing_argument", emptyRefs.error());
        assertEquals("sourceQuoteRefs", emptyRefs.argument());
        assertFalse(locationInput.valid());
        assertEquals("forbidden_argument", locationInput.error());
        assertEquals("locationRef", locationInput.argument());
        assertFalse(queryAlias.valid());
        assertEquals("forbidden_argument", queryAlias.error());
        assertEquals("queryText", queryAlias.argument());
        assertFalse(numberedCitation.valid());
        assertEquals("invalid_source_quote_ref", numberedCitation.error());
        assertEquals("sourceQuoteRefs", numberedCitation.argument());
        assertFalse(ordinalRef.valid());
        assertEquals("invalid_source_quote_ref", ordinalRef.error());
        assertEquals("sourceQuoteRefs", ordinalRef.argument());
        assertTrue(valid.valid());
    }

    @Test
    void parsesSupportedLocationTypes() {
        assertEquals(
                List.of(PaperLocationType.PAGE, PaperLocationType.SECTION, PaperLocationType.TABLE, PaperLocationType.FIGURE),
                validator.locationTypes(List.of("PAGE", "SECTION", "TABLE", "FIGURE"))
        );
    }
}
