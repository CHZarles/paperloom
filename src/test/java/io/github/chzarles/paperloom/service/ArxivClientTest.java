package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArxivClientTest {

    @Test
    void parsesVersionedAtomEntriesAndBoundsAbstractPreview() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2401.01234v2</id>
                    <title> A   Paper </title>
                    <summary> first   sentence </summary>
                    <published>2024-01-01T00:00:00Z</published>
                    <updated>2024-02-01T00:00:00Z</updated>
                    <author><name>Ada</name></author>
                    <link title="pdf" href="https://arxiv.org/pdf/2401.01234v2"/>
                  </entry>
                </feed>
                """;

        List<ArxivClient.Paper> papers = ArxivClient.parseFeed(xml, 10);

        assertEquals(1, papers.size());
        assertEquals("2401.01234v2", papers.get(0).arxivId());
        assertEquals("A Paper", papers.get(0).title());
        assertEquals(List.of("Ada"), papers.get(0).authors());
        assertEquals("first sentence", papers.get(0).abstractPreview());
        assertEquals("2401.01234v2", ArxivClient.normalizeVersionedId("arXiv:2401.01234v2"));
        assertNull(ArxivClient.normalizeVersionedId("not-an-arxiv-id"));
    }
}
