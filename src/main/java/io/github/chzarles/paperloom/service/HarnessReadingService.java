package io.github.chzarles.paperloom.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HarnessReadingService {

    private final PaperReadingService readingService;
    private final PaperSourceQuoteReadService quoteService;

    public HarnessReadingService(PaperReadingService readingService,
                                 PaperSourceQuoteReadService quoteService) {
        this.readingService = readingService;
        this.quoteService = quoteService;
    }

    @Transactional
    public PaperSourceQuoteReadService.ReadResult readAndCreateQuotes(
            PaperReadingService.ContentReadQuery query) {
        PaperReadingService.ContentReadResult read = readingService.readPaperContent(query);
        if (read.items().isEmpty()) {
            return new PaperSourceQuoteReadService.ReadResult(List.of(), read.missingLocationRefs());
        }
        return quoteService.createQuotes(new CanonicalReadingLocationService.ReadBatch(
                read.items(), read.missingLocationRefs()));
    }
}
