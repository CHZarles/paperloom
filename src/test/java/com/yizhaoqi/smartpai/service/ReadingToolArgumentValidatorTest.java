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
    void parsesSupportedLocationTypes() {
        assertEquals(
                List.of(PaperLocationType.PAGE, PaperLocationType.SECTION, PaperLocationType.TABLE, PaperLocationType.FIGURE),
                validator.locationTypes(List.of("PAGE", "SECTION", "TABLE", "FIGURE"))
        );
    }
}
