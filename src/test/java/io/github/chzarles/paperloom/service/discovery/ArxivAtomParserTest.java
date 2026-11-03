package io.github.chzarles.paperloom.service.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArxivAtomParserTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:arxiv="http://arxiv.org/schemas/atom"
                  xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
              <opensearch:totalResults>2</opensearch:totalResults>
              <entry>
                <id>http://arxiv.org/abs/2409.04701v2</id>
                <updated>2024-09-08T17:59:58Z</updated>
                <published>2024-09-05T17:59:58Z</published>
                <title>Retrieval Augmented
                  Generation   for Research</title>
                <summary>
                  We study how retrieval helps a research agent.
                </summary>
                <author><name>Ada Lovelace</name></author>
                <author><name>Alan Turing</name></author>
                <arxiv:primary_category term="cs.CL" scheme="http://arxiv.org/schemas/atom"/>
                <category term="cs.CL" scheme="http://arxiv.org/schemas/atom"/>
                <category term="cs.IR" scheme="http://arxiv.org/schemas/atom"/>
                <link href="http://arxiv.org/abs/2409.04701v2" rel="alternate" type="text/html"/>
                <link title="pdf" href="http://arxiv.org/pdf/2409.04701v2" rel="related" type="application/pdf"/>
              </entry>
              <entry>
                <id>http://arxiv.org/abs/hep-th/9901001v1</id>
                <published>1999-01-01T00:00:00Z</published>
                <title>An old style identifier</title>
                <summary>Old style summaries still parse.</summary>
                <author><name>Grace Hopper</name></author>
                <arxiv:primary_category term="hep-th" scheme="http://arxiv.org/schemas/atom"/>
              </entry>
            </feed>
            """;

    @Test
    void parsesEntriesAndStripsVersions() {
        List<ArxivPaper> papers = ArxivAtomParser.parse(FEED);

        assertEquals(2, papers.size());
        ArxivPaper first = papers.get(0);
        assertEquals("2409.04701", first.arxivId());
        assertEquals("Retrieval Augmented Generation for Research", first.title());
        assertEquals("We study how retrieval helps a research agent.", first.summary());
        assertEquals(List.of("Ada Lovelace", "Alan Turing"), first.authors());
        assertEquals(List.of("cs.CL", "cs.IR"), first.categories());
        assertEquals(2024, first.publishedYear());
        assertEquals("http://arxiv.org/pdf/2409.04701v2", first.pdfUrl());
        assertEquals("http://arxiv.org/abs/2409.04701", first.absUrl());
    }

    @Test
    void keepsOldStyleIdentifiersAndFallsBackToCanonicalUrls() {
        ArxivPaper second = ArxivAtomParser.parse(FEED).get(1);

        assertEquals("hep-th/9901001", second.arxivId());
        assertEquals(1999, second.publishedYear());
        assertEquals("https://arxiv.org/pdf/hep-th/9901001", second.pdfUrl());
        assertEquals(List.of("hep-th"), second.categories());
    }

    @Test
    void feedExposesTotalResultsForPaging() {
        ArxivAtomParser.ArxivFeed feed = ArxivAtomParser.parseFeed(FEED);

        assertEquals(2, feed.totalResults());
        assertEquals(2, feed.papers().size());
    }

    @Test
    void feedWithoutTotalResultsReportsUnknownInsteadOfZero() {
        ArxivAtomParser.ArxivFeed feed = ArxivAtomParser.parseFeed("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"/>
                """);

        assertEquals(-1, feed.totalResults());
    }

    @Test
    void emptyFeedIsAnEmptyResultRatherThanFailure() {
        assertTrue(ArxivAtomParser.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"></feed>
                """).isEmpty());
    }

    @Test
    void malformedPayloadFailsLoudly() {
        assertThrows(ArxivResponseException.class, () -> ArxivAtomParser.parse("not xml at all"));
        assertThrows(ArxivResponseException.class, () -> ArxivAtomParser.parse("  "));
    }
}
