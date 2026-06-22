package com.yizhaoqi.smartpai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.PaperChunkDocument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsMappingContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void paperChunksMappingUsesTheSameFieldNamesAsPaperChunkDocumentSerialization() throws Exception {
        PaperChunkDocument document = new PaperChunkDocument(
                "doc-id",
                "file-md5",
                1,
                "content",
                1,
                "anchor",
                "PARAGRAPH",
                "Methods",
                1,
                "{\"pageNumber\":1}",
                "opendataloader-pdf",
                "2.4.7",
                new float[]{0.1f, 0.2f},
                "text-embedding-v4",
                "1",
                "default",
                true
        );

        JsonNode serializedDocument = objectMapper.valueToTree(document);
        assertTrue(serializedDocument.has("paperId"));
        assertFalse(serializedDocument.has("fileMd5"));
        assertTrue(serializedDocument.has("public"));
        assertFalse(serializedDocument.has("isPublic"));
        assertTrue(serializedDocument.has("elementType"));
        assertTrue(serializedDocument.has("sectionTitle"));
        assertTrue(serializedDocument.has("sectionLevel"));
        assertTrue(serializedDocument.has("bboxJson"));
        assertTrue(serializedDocument.has("parserName"));
        assertTrue(serializedDocument.has("parserVersion"));

        InputStream mappingStream = getClass().getResourceAsStream("/es-mappings/paper_chunks.json");
        assertNotNull(mappingStream);

        JsonNode mapping = objectMapper.readTree(mappingStream);
        JsonNode properties = mapping.path("mappings").path("properties");

        assertTrue(properties.has("paperId"));
        assertFalse(properties.has("fileMd5"));
        assertTrue(properties.has("public"));
        assertFalse(properties.has("isPublic"));
        assertTrue(properties.has("elementType"));
        assertTrue(properties.has("sectionTitle"));
        assertTrue(properties.has("sectionLevel"));
        assertTrue(properties.has("bboxJson"));
        assertTrue(properties.has("parserName"));
        assertTrue(properties.has("parserVersion"));
    }
}
