package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.PaperTextChunkRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 启动时导入内置论文 PDF，确保新环境首次启动即可进行 PaperLoom 问答。
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "paper.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class BootstrapPaperInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(BootstrapPaperInitializer.class);

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final ElasticsearchService elasticsearchService;
    private final FileUploadRepository fileUploadRepository;
    private final PaperTextChunkRepository paperTextChunkRepository;
    private final MinioClient minioClient;
    private final UserRepository userRepository;

    @Value("${paper.bootstrap.path:docs/paismart.pdf}")
    private String bootstrapDocumentPath;

    @Value("${paper.bootstrap.org-tag:default}")
    private String bootstrapOrgTag;

    @Value("${paper.bootstrap.public:true}")
    private boolean bootstrapPublic;

    @Value("${paper.bootstrap.user-id:system-bootstrap}")
    private String bootstrapUserId;

    @Value("${minio.bucketName:uploads}")
    private String minioBucketName;

    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    public BootstrapPaperInitializer(ParseService parseService,
                                     VectorizationService vectorizationService,
                                     ElasticsearchService elasticsearchService,
                                     FileUploadRepository fileUploadRepository,
                                     PaperTextChunkRepository paperTextChunkRepository,
                                     MinioClient minioClient,
                                     UserRepository userRepository) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.elasticsearchService = elasticsearchService;
        this.fileUploadRepository = fileUploadRepository;
        this.paperTextChunkRepository = paperTextChunkRepository;
        this.minioClient = minioClient;
        this.userRepository = userRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        Path documentPath = resolveDocumentPath();
        if (!Files.isRegularFile(documentPath)) {
            logger.warn("启动论文 PDF 不存在，跳过导入: {}", documentPath);
            return;
        }

        String fileName = documentPath.getFileName().toString();
        String fileMd5 = calculateMd5(documentPath);
        long totalSize = Files.size(documentPath);
        String ownerUserId = resolveOwnerUserId();

        cleanupBootstrapHistory(fileName, fileMd5, ownerUserId);

        if (isBootstrapDocumentReady(fileMd5, fileName, ownerUserId)) {
            logger.info("启动论文 PDF 已完成导入，跳过重复处理: fileName={}, paperId={}", fileName, fileMd5);
            return;
        }

        cleanupBootstrapData(fileMd5, ownerUserId);
        importBootstrapDocument(documentPath, fileMd5, fileName, totalSize, ownerUserId);
    }

    private void cleanupBootstrapHistory(String fileName, String currentFileMd5, String ownerUserId) {
        List<FileUpload> bootstrapHistory = fileUploadRepository.findByUserIdAndFileNameOrderByCreatedAtDesc(ownerUserId, fileName);
        if (bootstrapHistory.isEmpty()) {
            return;
        }

        boolean keptCurrentRecord = false;
        List<FileUpload> duplicateCurrentRecords = new ArrayList<>();
        Set<String> staleFileMd5s = new LinkedHashSet<>();

        for (FileUpload fileUpload : bootstrapHistory) {
            if (currentFileMd5.equals(fileUpload.getFileMd5())) {
                if (!keptCurrentRecord) {
                    keptCurrentRecord = true;
                } else {
                    duplicateCurrentRecords.add(fileUpload);
                }
            } else {
                staleFileMd5s.add(fileUpload.getFileMd5());
            }
        }

        staleFileMd5s.forEach(staleFileMd5 -> {
            logger.info("清理旧版本启动论文 PDF: oldPaperId={}, currentPaperId={}", staleFileMd5, currentFileMd5);
            cleanupBootstrapData(staleFileMd5, ownerUserId);
        });

        if (!duplicateCurrentRecords.isEmpty()) {
            logger.warn("检测到重复的启动论文记录，删除多余记录: fileName={}, paperId={}, duplicates={}",
                    fileName, currentFileMd5, duplicateCurrentRecords.size());
            fileUploadRepository.deleteAll(duplicateCurrentRecords);
        }
    }

    private boolean isBootstrapDocumentReady(String fileMd5, String fileName, String ownerUserId) {
        Optional<FileUpload> existingFile = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, ownerUserId);
        long fileRecordCount = fileUploadRepository.countByFileMd5AndUserId(fileMd5, ownerUserId);
        long vectorCount = paperTextChunkRepository.countByPaperId(fileMd5);
        long pageAwareVectorCount = paperTextChunkRepository.countByPaperIdAndPageNumberIsNotNull(fileMd5);
        long esCount = elasticsearchService.countByPaperId(fileMd5);

        if (existingFile.isEmpty()) {
            return false;
        }

        FileUpload fileUpload = existingFile.get();
        boolean metadataMatches = fileName.equals(fileUpload.getFileName())
                && bootstrapOrgTag.equals(fileUpload.getOrgTag())
                && bootstrapPublic == fileUpload.isPublic()
                && fileUpload.getStatus() == 1;

        boolean pageMetadataReady = !fileName.toLowerCase().endsWith(".pdf") || pageAwareVectorCount > 0;
        if (metadataMatches && vectorCount > 0 && esCount > 0 && pageMetadataReady && fileRecordCount == 1) {
            return true;
        }

        logger.info("启动论文数据不完整、元数据已变化或存在重复记录，准备重新导入: paperId={}, fileRecords={}, vectors={}, pageAwareVectors={}, esDocs={}",
                fileMd5, fileRecordCount, vectorCount, pageAwareVectorCount, esCount);
        return false;
    }

    private void importBootstrapDocument(Path documentPath, String fileMd5, String fileName, long totalSize, String ownerUserId)
            throws IOException, TikaException {
        logger.info("开始导入启动论文 PDF: path={}, paperId={}", documentPath, fileMd5);

        uploadToMinio(documentPath, fileMd5);

        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            parseService.parseAndSave(fileMd5, inputStream, ownerUserId, bootstrapOrgTag, bootstrapPublic);
        } catch (Exception e) {
            cleanupBootstrapData(fileMd5, ownerUserId);
            throw e;
        }

        try {
            vectorizationService.vectorize(fileMd5, ownerUserId, bootstrapOrgTag, bootstrapPublic, bootstrapUserId);

            FileUpload fileUpload = new FileUpload();
            fileUpload.setFileMd5(fileMd5);
            fileUpload.setFileName(fileName);
            fileUpload.setTotalSize(totalSize);
            fileUpload.setStatus(1);
            fileUpload.setUserId(ownerUserId);
            fileUpload.setOrgTag(bootstrapOrgTag);
            fileUpload.setPublic(bootstrapPublic);
            fileUpload.setMergedAt(LocalDateTime.now());
            fileUploadRepository.save(fileUpload);

            logger.info("启动论文 PDF 导入完成: fileName={}, paperId={}", fileName, fileMd5);
        } catch (Exception e) {
            cleanupBootstrapData(fileMd5, ownerUserId);
            throw e;
        }
    }

    private void uploadToMinio(Path documentPath, String fileMd5) throws IOException {
        String objectName = "merged/" + fileMd5;
        String contentType = Files.probeContentType(documentPath);
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/pdf";
        }
        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioBucketName)
                            .object(objectName)
                            .stream(inputStream, Files.size(documentPath), -1)
                            .contentType(contentType)
                            .build()
            );
            logger.info("启动论文 PDF 已写入 MinIO: bucket={}, object={}", minioBucketName, objectName);
        } catch (Exception e) {
            throw new RuntimeException("写入 MinIO 失败", e);
        }
    }

    private void cleanupBootstrapData(String fileMd5, String ownerUserId) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioBucketName)
                            .object("merged/" + fileMd5)
                            .build()
            );
        } catch (Exception e) {
            logger.warn("清理启动论文 MinIO 文件失败: paperId={}, error={}", fileMd5, e.getMessage());
        }

        try {
            elasticsearchService.deleteByPaperId(fileMd5);
        } catch (Exception e) {
            logger.warn("清理启动论文 ES 数据失败: paperId={}, error={}", fileMd5, e.getMessage());
        }

        try {
            paperTextChunkRepository.deleteByPaperId(fileMd5);
        } catch (Exception e) {
            logger.warn("清理启动论文 chunk 数据失败: paperId={}, error={}", fileMd5, e.getMessage());
        }

        try {
            fileUploadRepository.deleteByFileMd5AndUserId(fileMd5, ownerUserId);
        } catch (Exception e) {
            logger.warn("清理启动论文记录失败: paperId={}, error={}", fileMd5, e.getMessage());
        }
    }

    private String resolveOwnerUserId() {
        Optional<User> adminUser = findAdminUser();
        if (adminUser.isPresent()) {
            String ownerUserId = String.valueOf(adminUser.get().getId());
            logger.info("启动论文 PDF 归属管理员账号: username={}, userId={}",
                    adminUser.get().getUsername(), ownerUserId);
            return ownerUserId;
        }

        logger.warn("未找到管理员账号，启动论文 PDF 回退使用 userId={}", bootstrapUserId);
        return bootstrapUserId;
    }

    private Optional<User> findAdminUser() {
        if (adminUsername != null && !adminUsername.isBlank()) {
            Optional<User> configuredAdmin = userRepository.findByUsername(adminUsername)
                    .filter(user -> User.Role.ADMIN.equals(user.getRole()));
            if (configuredAdmin.isPresent()) {
                return configuredAdmin;
            }
        }

        return userRepository.findAll().stream()
                .filter(user -> User.Role.ADMIN.equals(user.getRole()))
                .findFirst();
    }

    private Path resolveDocumentPath() {
        Path path = Path.of(bootstrapDocumentPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }

    private String calculateMd5(Path documentPath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            return DigestUtils.md5Hex(inputStream);
        }
    }
}
