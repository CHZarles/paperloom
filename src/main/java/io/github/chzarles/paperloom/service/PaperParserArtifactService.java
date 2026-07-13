package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperParserArtifact;
import io.github.chzarles.paperloom.paper.parser.ParsedPaper;
import io.github.chzarles.paperloom.paper.parser.ParsedPaperArtifactPayload;
import io.github.chzarles.paperloom.repository.PaperParserArtifactRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class PaperParserArtifactService {

    private static final String BUCKET = "uploads";
    private static final String CONTENT_TYPE_JSON = "application/json";

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private PaperParserArtifactRepository paperParserArtifactRepository;

    @Transactional
    public PaperParserArtifact saveParserArtifact(String paperId,
                                                  ParsedPaper parsedPaper,
                                                  String userId,
                                                  String orgTag,
                                                  boolean isPublic) {
        if (paperId == null || paperId.isBlank() || parsedPaper == null) {
            return null;
        }

        deleteParserArtifacts(paperId);

        if (parsedPaper.artifacts() != null && !parsedPaper.artifacts().isEmpty()) {
            List<PaperParserArtifact> saved = parsedPaper.artifacts().stream()
                    .map(payload -> savePayload(paperId, parsedPaper, payload, userId, orgTag, isPublic))
                    .filter(artifact -> artifact != null)
                    .toList();
            return saved.isEmpty() ? null : saved.get(0);
        }

        if (parsedPaper.rawParserJson() == null || parsedPaper.rawParserJson().isBlank()) {
            return null;
        }
        byte[] bytes = parsedPaper.rawParserJson().getBytes(StandardCharsets.UTF_8);
        return savePayload(
                paperId,
                parsedPaper,
                new ParsedPaperArtifactPayload(
                        PaperParserArtifact.TYPE_OPENDATALOADER_JSON,
                        "%s-%s.json".formatted(safeKey(parsedPaper.parserName()), safeKey(parsedPaper.parserVersion())),
                        CONTENT_TYPE_JSON,
                        bytes
                ),
                userId,
                orgTag,
                isPublic
        );
    }

    private PaperParserArtifact savePayload(String paperId,
                                            ParsedPaper parsedPaper,
                                            ParsedPaperArtifactPayload payload,
                                            String userId,
                                            String orgTag,
                                            boolean isPublic) {
        if (payload == null || payload.bytes() == null || payload.bytes().length == 0) {
            return null;
        }
        String contentType = payload.contentType() == null || payload.contentType().isBlank()
                ? CONTENT_TYPE_JSON
                : payload.contentType();
        String objectKey = "paper-parser-artifacts/%s/%s/%s".formatted(
                paperId,
                safeKey(parsedPaper.parserName()),
                safeKey(payload.filename())
        );

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(payload.bytes()), payload.bytes().length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("保存 parser artifact 到 MinIO 失败: " + e.getMessage(), e);
        }

        PaperParserArtifact artifact = new PaperParserArtifact();
        artifact.setPaperId(paperId);
        artifact.setArtifactType(payload.artifactType());
        artifact.setParserName(parsedPaper.parserName());
        artifact.setParserVersion(parsedPaper.parserVersion());
        artifact.setObjectKey(objectKey);
        artifact.setContentType(contentType);
        artifact.setSizeBytes((long) payload.bytes().length);
        artifact.setSha256(sha256(payload.bytes()));
        artifact.setUserId(userId);
        artifact.setOrgTag(orgTag);
        artifact.setPublic(isPublic);
        return paperParserArtifactRepository.save(artifact);
    }

    public Optional<PaperParserArtifact> findLatestParserArtifact(String paperId) {
        return paperParserArtifactRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId);
    }

    public long countByPaperId(String paperId) {
        return paperParserArtifactRepository.countByPaperId(paperId);
    }

    public String generateDownloadUrl(PaperParserArtifact artifact) {
        if (artifact == null || artifact.getObjectKey() == null || artifact.getObjectKey().isBlank()) {
            return null;
        }
        try {
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(BUCKET)
                            .object(artifact.getObjectKey())
                            .expiry(3600)
                            .build()
            );
            return uploadService.transToPublicUrl(presignedUrl);
        } catch (Exception e) {
            throw new RuntimeException("生成 parser artifact 下载链接失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteParserArtifacts(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return;
        }
        for (PaperParserArtifact artifact : paperParserArtifactRepository.findByPaperId(paperId)) {
            removeObjectQuietly(artifact.getObjectKey());
        }
        paperParserArtifactRepository.deleteByPaperId(paperId);
    }

    private void removeObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // MinIO cleanup is best-effort; database metadata is still removed below.
        }
    }

    private String safeKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException("计算 parser artifact SHA-256 失败", e);
        }
    }
}
