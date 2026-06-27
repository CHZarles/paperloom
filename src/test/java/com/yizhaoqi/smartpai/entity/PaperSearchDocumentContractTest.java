package com.yizhaoqi.smartpai.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.Paper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperSearchDocumentContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void paperSearchDocumentSerializesToDedicatedMappingFields() throws Exception {
        Paper paper = new Paper();
        paper.setPaperId("paper-a");
        paper.setPaperTitle("Graph-Augmented Retrieval for Scientific Literature Search");
        paper.setOriginalFilename("graph-rag.pdf");
        paper.setAbstractText("We retrieve related papers using title and abstract facets.");
        paper.setAuthors("Ada Lovelace; Grace Hopper");
        paper.setVenue("SIGIR");
        paper.setPublicationYear(2026);
        paper.setDoi("10.1234/paper");
        paper.setArxivId("2606.00001");

        PaperSearchDocument document = PaperSearchDocument.from(paper, "user-1", "eval-litsearch", true);

        JsonNode serialized = objectMapper.valueToTree(document);

        assertEquals("paper-a", serialized.path("id").asText());
        assertEquals("paper-a", serialized.path("paperId").asText());
        assertEquals("Graph-Augmented Retrieval for Scientific Literature Search", serialized.path("paperTitle").asText());
        assertEquals("graph-rag.pdf", serialized.path("originalFilename").asText());
        assertEquals("Ada Lovelace; Grace Hopper", serialized.path("authors").asText());
        assertEquals("SIGIR", serialized.path("venue").asText());
        assertEquals(2026, serialized.path("year").asInt());
        assertEquals("10.1234/paper", serialized.path("doi").asText());
        assertEquals("2606.00001", serialized.path("arxivId").asText());
        assertTrue(serialized.path("searchText").asText().contains("title: Graph-Augmented Retrieval"));
        assertTrue(serialized.path("searchText").asText().contains("abstract: We retrieve related papers"));
        assertTrue(serialized.path("searchText").asText().contains("authors: Ada Lovelace"));
        assertTrue(serialized.path("searchText").asText().contains("venue: SIGIR"));
        assertTrue(serialized.has("public"));
        assertFalse(serialized.has("isPublic"));

        InputStream mappingStream = getClass().getResourceAsStream("/es-mappings/paper_search.json");
        assertNotNull(mappingStream);

        JsonNode mapping = objectMapper.readTree(mappingStream);
        JsonNode properties = mapping.path("mappings").path("properties");
        assertTrue(properties.has("id"));
        assertTrue(properties.has("paperId"));
        assertTrue(properties.has("paperTitle"));
        assertTrue(properties.has("originalFilename"));
        assertTrue(properties.has("abstractText"));
        assertTrue(properties.has("authors"));
        assertTrue(properties.has("venue"));
        assertTrue(properties.has("year"));
        assertTrue(properties.has("doi"));
        assertTrue(properties.has("arxivId"));
        assertTrue(properties.has("searchText"));
        assertTrue(properties.has("userId"));
        assertTrue(properties.has("orgTag"));
        assertTrue(properties.has("public"));
        assertFalse(properties.has("isPublic"));
    }
}
