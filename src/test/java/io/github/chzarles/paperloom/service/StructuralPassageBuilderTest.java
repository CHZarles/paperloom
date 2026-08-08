package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperLocation;
import io.github.chzarles.paperloom.model.PaperLocationType;
import io.github.chzarles.paperloom.model.PaperPassage;
import io.github.chzarles.paperloom.model.PaperReadingElement;
import io.github.chzarles.paperloom.model.PaperSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralPassageBuilderTest {

    private final StructuralPassageBuilder builder = new StructuralPassageBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDeterministicPassagesWithoutCrossingSectionsOrTableBarriers() throws Exception {
        PaperSection methods = section("section-methods", "Methods", 1, 4);
        PaperSection results = section("section-results", "Results", 5, 6);
        List<PaperLocation> locations = List.of(
                sectionLocation(methods, "section_ref_methods"),
                sectionLocation(results, "section_ref_results")
        );
        List<PaperReadingElement> elements = List.of(
                element("heading-methods", "HEADING", "Methods", 1, 1),
                element("method-text", "PARAGRAPH", "First method paragraph.", 2, 1),
                element("table", "TABLE", "Table 1: ignored from passages", 3, 1),
                element("method-after-table", "PARAGRAPH", "Second method paragraph.", 4, 2),
                element("heading-results", "HEADING", "Results", 5, 3),
                element("result-text", "PARAGRAPH", "Result paragraph.", 6, 3)
        );

        PassageBuildResult first = builder.build("paper-a", "rm_1", List.of(methods, results), locations, elements, "user-a");
        PassageBuildResult second = builder.build("paper-a", "rm_1", List.of(methods, results), locations, elements, "user-a");

        assertEquals(3, first.passages().size());
        assertEquals(first.passages().stream().map(PaperPassage::getPassageRef).toList(),
                second.passages().stream().map(PaperPassage::getPassageRef).toList());
        assertEquals("section-methods", first.passages().get(0).getParentSectionId());
        assertEquals("section-methods", first.passages().get(1).getParentSectionId());
        assertEquals("section-results", first.passages().get(2).getParentSectionId());
        assertEquals(1, first.passages().get(0).getPageNumberFrom());
        assertEquals(1, first.passages().get(0).getPageNumberTo());
        assertEquals(2, first.passages().get(1).getPageNumberFrom());
        assertFalse(first.passages().stream().map(PaperPassage::getContentText)
                .anyMatch(content -> content.contains("Table 1")));
        assertEquals(List.of(1, 2, 1), first.passages().stream().map(PaperPassage::getSectionOrdinal).toList());
        assertTrue(first.locations().stream().allMatch(location -> location.getLocationType() == PaperLocationType.PASSAGE));
        assertEquals(first.passages().get(0).getPassageRef(), first.locations().get(0).getLocationRef());

        JsonNode spans = objectMapper.readTree(first.passages().get(0).getSourceSpanJson()).path("spans");
        assertEquals(2, spans.size());
        assertEquals("heading-methods", spans.get(0).path("readingElementId").asText());
        assertEquals(0, spans.get(0).path("char_from").asInt());
        assertEquals("Methods".length(), spans.get(0).path("char_to").asInt());
    }

    @Test
    void leavesContentBeforeTheFirstHeadingUnsectioned() {
        PaperReadingElement title = element("title", "TITLE", "Paper title.", 1, 1);

        PassageBuildResult result = builder.build("paper-a", "rm_1", List.of(), List.of(), List.of(title), "user-a");

        assertEquals(1, result.passages().size());
        assertNull(result.passages().get(0).getParentSectionId());
        assertNull(result.passages().get(0).getParentSectionRef());
        assertNull(result.passages().get(0).getSectionOrdinal());
    }

    private PaperSection section(String id, String title, int from, int to) {
        PaperSection section = new PaperSection();
        section.setSectionId(id);
        section.setSectionTitle(title);
        section.setReadingOrderFrom(from);
        section.setReadingOrderTo(to);
        section.setDisplayOrder(from);
        return section;
    }

    private PaperLocation sectionLocation(PaperSection section, String ref) {
        PaperLocation location = new PaperLocation();
        location.setLocationType(PaperLocationType.SECTION);
        location.setLocationRef(ref);
        location.setSourceObjectId(section.getSectionId());
        return location;
    }

    private PaperReadingElement element(String id, String type, String text, int order, int page) {
        PaperReadingElement element = new PaperReadingElement();
        element.setReadingElementId(id);
        element.setParserElementId(id + "-parser");
        element.setElementType(type);
        element.setBodyText(text);
        element.setPageNumber(page);
        element.setReadingOrder(order);
        element.setAttachmentRole("NONE");
        element.setAssociationStatus("SELF");
        element.setSourceSpanJson("{\"element\":\"" + id + "\"}");
        return element;
    }
}
