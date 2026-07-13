package io.github.chzarles.paperloom.paper.parser;

import io.github.chzarles.paperloom.model.PaperParserArtifact;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "paper.parsing", name = "provider", havingValue = "mineru", matchIfMissing = true)
public class MinerUPaperPdfParser implements PaperPdfParser {

    private static final String PROVIDER_NAME = "MinerU";
    private static final String PROVIDER_VERSION = "self-hosted";

    private final MinerUParserClient minerUParserClient;
    private final MinerUOutputMapper minerUOutputMapper;

    public MinerUPaperPdfParser(MinerUParserClient minerUParserClient,
                                MinerUOutputMapper minerUOutputMapper) {
        this.minerUParserClient = minerUParserClient;
        this.minerUOutputMapper = minerUOutputMapper;
    }

    @Override
    public ParsedPaper parse(InputStream pdfInputStream, String originalFilename) {
        if (pdfInputStream == null) {
            throw new PaperParsingException("PDF input stream must not be null");
        }
        try {
            MinerUParserClient.MinerUParseResult result = minerUParserClient.parse(pdfInputStream.readAllBytes(), originalFilename);
            ParsedPaper mapped = minerUOutputMapper.map(
                    result.contentListJson(),
                    result.middleJson(),
                    result.markdown(),
                    providerName(),
                    providerVersion(),
                    originalFilename
            );
            return withRawZipArtifact(mapped, result.rawResultZipBytes());
        } catch (IOException e) {
            throw new PaperParsingException("Failed to read PDF for MinerU parser", e);
        }
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String providerVersion() {
        return PROVIDER_VERSION;
    }

    private ParsedPaper withRawZipArtifact(ParsedPaper paper, byte[] rawZipBytes) {
        if (rawZipBytes == null || rawZipBytes.length == 0) {
            return paper;
        }
        List<ParsedPaperArtifactPayload> artifacts = new ArrayList<>();
        artifacts.add(new ParsedPaperArtifactPayload(
                PaperParserArtifact.TYPE_MINERU_RESULT_ZIP,
                "raw-result.zip",
                "application/zip",
                rawZipBytes
        ));
        if (paper.artifacts() != null) {
            artifacts.addAll(paper.artifacts());
        }
        return new ParsedPaper(
                paper.parserName(),
                paper.parserVersion(),
                paper.metadata(),
                paper.elements(),
                paper.rawMetadata(),
                paper.rawParserJson(),
                paper.tables(),
                paper.figures(),
                paper.formulas(),
                artifacts
        );
    }
}
