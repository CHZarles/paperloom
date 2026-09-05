package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ArxivImportService {

    private static final String BUCKET = "uploads";

    private final ArxivClient arxivClient;
    private final PaperRepository paperRepository;
    private final MinioClient minioClient;

    public ArxivImportService(ArxivClient arxivClient,
                              PaperRepository paperRepository,
                              MinioClient minioClient) {
        this.arxivClient = arxivClient;
        this.paperRepository = paperRepository;
        this.minioClient = minioClient;
    }

    /**
     * Downloads one confirmed arXiv version and turns it into the existing upload-processing task.
     * The method runs inside the Kafka consumer, never in the HTTP request thread.
     */
    public PaperProcessingTask materialize(PaperProcessingTask importTask) {
        String arxivId = ArxivClient.normalizeVersionedId(importTask.getArxivId());
        if (arxivId == null || !arxivId.matches(".*v\\d+$")) {
            throw new IllegalArgumentException("a versioned arXiv ID is required");
        }
        String userId = required(importTask.getUserId(), "userId");
        ArxivClient.Paper metadata = arxivClient.fetchExact(arxivId);

        Optional<Paper> existingByArxiv = paperRepository
                .findFirstByArxivIdAndUserIdOrderByCreatedAtDesc(arxivId, userId);
        if (existingByArxiv.isPresent()) {
            Paper existing = existingByArxiv.get();
            return uploadTask(existing.getPaperId(), existing.getPaperTitle(), userId);
        }

        byte[] pdf = arxivClient.downloadPdf(arxivId);
        String paperId = DigestUtils.md5Hex(pdf);
        Optional<Paper> existingByHash = paperRepository
                .findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);
        if (existingByHash.isPresent()) {
            return uploadTask(existingByHash.get().getPaperId(), existingByHash.get().getPaperTitle(), userId);
        }

        String objectName = "merged/" + paperId;
        boolean objectCreated = false;
        try {
            if (paperRepository.countByPaperId(paperId) == 0) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(BUCKET)
                        .object(objectName)
                        .stream(new ByteArrayInputStream(pdf), pdf.length, -1)
                        .contentType("application/pdf")
                        .build());
                objectCreated = true;
            }

            Paper paper = new Paper();
            paper.setPaperId(paperId);
            paper.setOriginalFilename(arxivId.replace('/', '_') + ".pdf");
            paper.setPaperTitle(metadata.title());
            paper.setAuthors(String.join(", ", metadata.authors()));
            paper.setPublicationYear(year(metadata.published()));
            paper.setAbstractText(metadata.abstractPreview());
            paper.setArxivId(arxivId);
            paper.setTotalSize(pdf.length);
            paper.setStatus(Paper.STATUS_COMPLETED);
            paper.setMergedAt(LocalDateTime.now());
            paper.setUserId(userId);
            paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PENDING);
            paperRepository.save(paper);
            return uploadTask(paperId, metadata.title(), userId);
        } catch (Exception error) {
            if (objectCreated) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(BUCKET)
                            .object(objectName)
                            .build());
                } catch (Exception cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            throw new RuntimeException("arXiv paper import failed", error);
        }
    }

    private PaperProcessingTask uploadTask(String paperId, String title, String userId) {
        return new PaperProcessingTask(
                paperId,
                null,
                title,
                userId,
                PaperProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                userId
        );
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private Integer year(String published) {
        if (published == null || published.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(published.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
