package com.yizhaoqi.smartpai.paper.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenDataLoaderJsonMapperTest {

    @Test
    void mapsOpenDataLoaderJsonToParsedPaperWithPageElementAndBoundingBoxProvenance() throws Exception {
        String json = """
                {
                  "file name": "attention.pdf",
                  "number of pages": 2,
                  "author": "Vaswani et al.",
                  "title": "Attention Is All You Need",
                  "creation date": "D:20170612000000Z",
                  "modification date": "D:20170613000000Z",
                  "kids": [
                    {
                      "type": "heading",
                      "id": 1,
                      "page number": 1,
                      "bounding box": [72.0, 700.0, 520.0, 735.0],
                      "heading level": 1,
                      "font": "Times",
                      "font size": 18.0,
                      "text color": "[0.0]",
                      "content": "Methods"
                    },
                    {
                      "type": "paragraph",
                      "id": 2,
                      "page number": 1,
                      "bounding box": [72.0, 620.0, 520.0, 690.0],
                      "font": "Times",
                      "font size": 10.0,
                      "text color": "[0.0]",
                      "content": "We propose a new simple network architecture, the Transformer."
                    },
                    {
                      "type": "table",
                      "id": 3,
                      "page number": 2,
                      "bounding box": [80.0, 300.0, 500.0, 450.0],
                      "number of rows": 2,
                      "number of columns": 2,
                      "rows": [
                        {
                          "type": "table row",
                          "row number": 1,
                          "cells": [
                            {"type": "table cell", "page number": 2, "bounding box": [80, 420, 200, 450], "content": "Metric"},
                            {"type": "table cell", "page number": 2, "bounding box": [200, 420, 500, 450], "content": "Value"}
                          ]
                        },
                        {
                          "type": "table row",
                          "row number": 2,
                          "cells": [
                            {"type": "table cell", "page number": 2, "bounding box": [80, 390, 200, 420], "content": "BLEU"},
                            {"type": "table cell", "page number": 2, "bounding box": [200, 390, 500, 420], "content": "28.4"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        ParsedPaper paper = new OpenDataLoaderJsonMapper().map(json, "opendataloader-pdf", "2.4.7");

        assertEquals("opendataloader-pdf", paper.parserName());
        assertEquals("2.4.7", paper.parserVersion());
        assertEquals("attention.pdf", paper.metadata().originalFilename());
        assertEquals("Attention Is All You Need", paper.metadata().title());
        assertEquals("Vaswani et al.", paper.metadata().authors());
        assertEquals(2, paper.metadata().pageCount());
        assertEquals(3, paper.elements().size());

        ParsedPaperElement heading = paper.elements().get(0);
        assertEquals("1", heading.elementId());
        assertEquals(ParsedPaperElementType.HEADING, heading.elementType());
        assertEquals(1, heading.pageNumber());
        assertEquals(1, heading.sectionLevel());
        assertEquals("Methods", heading.text());
        assertNotNull(heading.boundingBox());
        assertEquals(72.0, heading.boundingBox().left());
        assertEquals(735.0, heading.boundingBox().top());

        ParsedPaperElement table = paper.elements().get(2);
        assertEquals(ParsedPaperElementType.TABLE, table.elementType());
        assertTrue(table.text().contains("Metric\tValue"));
        assertTrue(table.text().contains("BLEU\t28.4"));
        assertTrue(table.rawAttributes().containsKey("number of rows"));
    }
}
