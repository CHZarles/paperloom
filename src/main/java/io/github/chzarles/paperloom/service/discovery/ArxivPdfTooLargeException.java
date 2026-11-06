package io.github.chzarles.paperloom.service.discovery;

/**
 * arXiv PDF 超过允许的最大体积。
 */
public class ArxivPdfTooLargeException extends RuntimeException {

    public ArxivPdfTooLargeException(String message) {
        super(message);
    }
}
