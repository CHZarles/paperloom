package io.github.chzarles.paperloom.service;

import java.util.Locale;
import java.util.Map;

final class PdfDownloadHeaders {

    enum Disposition {
        INLINE("inline"),
        ATTACHMENT("attachment");

        private final String value;

        Disposition(String value) {
            this.value = value;
        }
    }

    static final String CONTENT_TYPE_PDF = "application/pdf";
    private static final String RESPONSE_CONTENT_TYPE = "response-content-type";
    private static final String RESPONSE_CONTENT_DISPOSITION = "response-content-disposition";

    private PdfDownloadHeaders() {
    }

    static Map<String, String> presignedQueryParams(String originalFilename, String fallbackPaperId) {
        return presignedQueryParams(originalFilename, fallbackPaperId, Disposition.INLINE);
    }

    static Map<String, String> presignedQueryParams(
            String originalFilename,
            String fallbackPaperId,
            Disposition disposition
    ) {
        String filename = sanitizePdfFilename(originalFilename, fallbackPaperId);
        return Map.of(
                RESPONSE_CONTENT_TYPE, CONTENT_TYPE_PDF,
                RESPONSE_CONTENT_DISPOSITION, disposition.value + "; filename=\"" + filename + "\""
        );
    }

    static Map<String, String> objectHeaders() {
        return Map.of("Content-Type", CONTENT_TYPE_PDF);
    }

    static String sanitizePdfFilename(String originalFilename, String fallbackPaperId) {
        String filename = originalFilename;
        if (filename == null || filename.isBlank()) {
            filename = fallbackPaperId;
        }
        if (filename == null || filename.isBlank()) {
            filename = "paper";
        }

        filename = filename.trim()
                .replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ")
                .replace('/', '_')
                .replace('\\', '_')
                .replace('"', '_')
                .trim();
        filename = filename.replaceAll("\\s+", " ");
        if (filename.isBlank()) {
            filename = "paper";
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            filename = filename + ".pdf";
        }
        return filename;
    }
}
