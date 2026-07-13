package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.MinioConfig;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.ChunkInfo;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.repository.ChunkInfoRepository;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.micrometer.common.util.StringUtils;
import io.minio.*;
import io.minio.http.Method;
import io.minio.GetObjectResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class UploadService {

    private static final Logger logger = LoggerFactory.getLogger(UploadService.class);

    // 用于缓存已上传分片的信息
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 用于与 MinIO 服务器交互
    @Autowired
    private MinioClient minioClient;

    // 用于操作文件上传记录的 Repository
    @Autowired
    private PaperRepository paperRepository;

    // 用于操作分片信息的 Repository
    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioConfig minioConfig;

    /**
     * 上传论文 PDF 分片
     *
     * @param paperId 论文 PDF 的 paperId，用于唯一标识文件
     * @param chunkIndex 分片索引，表示这是文件的第几个分片
     * @param totalSize PDF 总大小
     * @param originalFilename 原始 PDF 文件名
     * @param file 要上传的分片文件
     * @param orgTag 组织标签，指定文件所属的组织
     * @param isPublic 是否公开，标识文件访问权限
     * @param userId 上传用户ID
     * @throws IOException 如果文件读取失败
     */
    public void uploadChunk(String paperId, int chunkIndex, long totalSize, String originalFilename,
                           MultipartFile file, String orgTag, boolean isPublic, String userId) throws IOException {
        // 获取文件类型信息
        String fileType = getFileType(originalFilename);
        String contentType = file.getContentType();

        logger.info("[uploadChunk] 开始处理分片上传请求 => paperId: {}, chunkIndex: {}, totalSize: {}, originalFilename: {}, fileType: {}, contentType: {}, fileSize: {}, orgTag: {}, isPublic: {}, userId: {}",
                   paperId, chunkIndex, totalSize, originalFilename, fileType, contentType, file.getSize(), orgTag, isPublic, userId);

        try {
            Paper paper = getOrCreatePaper(paperId, totalSize, originalFilename, orgTag, isPublic, userId, fileType);
            logger.debug("检查论文记录是否存在 => paperId: {}, originalFilename: {}, fileType: {}, status: {}", paperId, originalFilename, fileType, paper.getStatus());

            if (paper.getStatus() == Paper.STATUS_MERGING) {
                throw new CustomException("论文 PDF 正在合并中，请稍后重试", HttpStatus.CONFLICT);
            }
            if (paper.getStatus() == Paper.STATUS_COMPLETED) {
                throw new CustomException("论文 PDF 已完成合并，不允许继续上传分片", HttpStatus.CONFLICT);
            }

            // Redis Bitmap 是上传进度快路径；数据库是最终可合并的事实来源。
            boolean chunkUploaded = isChunkUploaded(paperId, chunkIndex, userId);
            logger.debug("检查分片是否已上传 => paperId: {}, originalFilename: {}, chunkIndex: {}, isUploaded: {}",
                      paperId, originalFilename, chunkIndex, chunkUploaded);

            if (chunkUploaded) {
                logger.info("分片已在Redis中标记为已上传，按幂等成功处理 => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}", paperId, originalFilename, fileType, chunkIndex);
                return;
            }

            if (chunkInfoRepository.existsByPaperIdAndChunkIndex(paperId, chunkIndex)) {
                logger.info("Redis未命中但数据库已有分片信息，回填Redis后按幂等成功处理 => paperId: {}, originalFilename: {}, chunkIndex: {}", paperId, originalFilename, chunkIndex);
                markChunkUploadedQuietly(paperId, chunkIndex, userId, originalFilename);
                return;
            }

            logger.debug("计算分片MD5 => paperId: {}, originalFilename: {}, chunkIndex: {}", paperId, originalFilename, chunkIndex);
            byte[] fileBytes = file.getBytes();
            String chunkMd5 = DigestUtils.md5Hex(fileBytes);
            logger.debug("分片MD5计算完成 => paperId: {}, originalFilename: {}, chunkIndex: {}, chunkMd5: {}",
                       paperId, originalFilename, chunkIndex, chunkMd5);

            String storagePath = "chunks/" + paperId + "/" + chunkIndex;
            logger.debug("构建分片存储路径 => originalFilename: {}, path: {}", originalFilename, storagePath);

            try {
                logger.info("开始上传分片到MinIO => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}, bucket: uploads, path: {}, size: {}, contentType: {}",
                          paperId, originalFilename, fileType, chunkIndex, storagePath, file.getSize(), contentType);

                PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                        .bucket("uploads")
                        .object(storagePath)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build();

                minioClient.putObject(putObjectArgs);
                logger.info("分片上传到MinIO成功 => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}", paperId, originalFilename, fileType, chunkIndex);
            } catch (Exception e) {
                logger.error("分片上传到MinIO失败 => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}, 错误类型: {}, 错误信息: {}",
                          paperId, originalFilename, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);

                if (e instanceof io.minio.errors.ErrorResponseException) {
                    io.minio.errors.ErrorResponseException ere = (io.minio.errors.ErrorResponseException) e;
                    logger.error("MinIO错误响应详情 => originalFilename: {}, code: {}, message: {}, resource: {}, requestId: {}",
                             originalFilename, ere.errorResponse().code(), ere.errorResponse().message(),
                             ere.errorResponse().resource(), ere.errorResponse().requestId());
                }

                throw new RuntimeException("上传分片到MinIO失败: " + e.getMessage(), e);
            }

            logger.debug("保存分片信息到数据库 => paperId: {}, originalFilename: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                      paperId, originalFilename, chunkIndex, chunkMd5, storagePath);
            saveChunkInfo(paperId, chunkIndex, chunkMd5, storagePath);
            logger.info("分片信息已保存到数据库 => paperId: {}, originalFilename: {}, chunkIndex: {}", paperId, originalFilename, chunkIndex);

            markChunkUploadedQuietly(paperId, chunkIndex, userId, originalFilename);

            logger.info("分片处理完成 => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}", paperId, originalFilename, fileType, chunkIndex);
        } catch (Exception e) {
            logger.error("分片上传过程中发生错误 => paperId: {}, originalFilename: {}, fileType: {}, chunkIndex: {}, 错误类型: {}, 错误信息: {}",
                       paperId, originalFilename, fileType, chunkIndex, e.getClass().getName(), e.getMessage(), e);
            throw e; // 重新抛出异常供上层处理
        }
    }

    /**
     * 根据原始 PDF 文件名获取 PaperLoom 上传类型。
     *
     * @param originalFilename 上传 PDF 原始 PDF 文件名
     * @return 上传类型
     */
    private String getFileType(String originalFilename) {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "unknown";
        }

        int lastDotIndex = originalFilename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == originalFilename.length() - 1) {
            return "unknown";
        }

        String extension = originalFilename.substring(lastDotIndex + 1).toLowerCase();

        return "pdf".equals(extension) ? "PDF论文" : "unsupported";
    }

    /**
     * 检查指定分片是否已上传（单个查询版本，性能较低）
     * 注意：对于批量查询建议使用 getUploadedChunks() 方法
     *
     * @param paperId 论文 PDF 的 paperId
     * @param chunkIndex 分片索引
     * @param userId 用户ID
     * @return 分片是否已上传
     */
    public boolean isChunkUploaded(String paperId, int chunkIndex, String userId) {
        logger.debug("检查分片是否已上传 => paperId: {}, chunkIndex: {}, userId: {}", paperId, chunkIndex, userId);
        try {
            if (chunkIndex < 0) {
                logger.error("无效的分片索引 => paperId: {}, chunkIndex: {}", paperId, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + userId + ":" + paperId;
            boolean isUploaded = redisTemplate.opsForValue().getBit(redisKey, chunkIndex);
            logger.debug("分片上传状态 => paperId: {}, chunkIndex: {}, userId: {}, isUploaded: {}",
                      paperId, chunkIndex, userId, isUploaded);
            return isUploaded;
        } catch (Exception e) {
            logger.error("检查分片上传状态失败 => paperId: {}, chunkIndex: {}, userId: {}, 错误: {}",
                      paperId, chunkIndex, userId, e.getMessage(), e);
            return false; // 或者根据业务需求返回其他值
        }
    }

    /**
     * 标记指定分片为已上传
     *
     * @param paperId 论文 PDF 的 paperId
     * @param chunkIndex 分片索引
     * @param userId 用户ID
     */
    public void markChunkUploaded(String paperId, int chunkIndex, String userId) {
        logger.debug("标记分片为已上传 => paperId: {}, chunkIndex: {}, userId: {}", paperId, chunkIndex, userId);
        try {
            if (chunkIndex < 0) {
                logger.error("无效的分片索引 => paperId: {}, chunkIndex: {}", paperId, chunkIndex);
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            String redisKey = "upload:" + userId + ":" + paperId;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, true);
            logger.debug("分片已标记为已上传 => paperId: {}, chunkIndex: {}, userId: {}", paperId, chunkIndex, userId);
        } catch (Exception e) {
            logger.error("标记分片为已上传失败 => paperId: {}, chunkIndex: {}, userId: {}, 错误: {}",
                      paperId, chunkIndex, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to mark chunk as uploaded", e);
        }
    }

    /**
     * 删除文件所有分片上传标记
     *
     * @param paperId 论文 PDF 的 paperId
     * @param userId 用户ID
     */
    public void deleteFileMark(String paperId, String userId) {
        logger.debug("删除文件所有分片上传标记 => paperId: {}, userId: {}", paperId, userId);
        try {
            String redisKey = "upload:" + userId + ":" + paperId;
            redisTemplate.delete(redisKey);
            logger.info("论文 PDF 分片上传标记已删除 => paperId: {}, userId: {}", paperId, userId);
        } catch (Exception e) {
            logger.error("删除论文 PDF 分片上传标记失败 => paperId: {}, userId: {}, 错误: {}", paperId, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete file mark", e);
        }
    }


    /**
     * 获取已上传的分片列表
     *
     * @param paperId 论文 PDF 的 paperId
     * @param userId 用户ID
     * @return 包含已上传分片索引的列表
     */
    public List<Integer> getUploadedChunks(String paperId, String userId) {
        logger.info("获取已上传分片列表 => paperId: {}, userId: {}", paperId, userId);
        List<Integer> uploadedChunks = new ArrayList<>();
        try {
            int totalChunks = getTotalChunks(paperId, userId);
            logger.debug("文件总分片数 => paperId: {}, userId: {}, totalChunks: {}", paperId, userId, totalChunks);

            if (totalChunks == 0) {
                logger.warn("文件总分片数为0 => paperId: {}, userId: {}", paperId, userId);
                return uploadedChunks;
            }

            // 优化：一次性获取所有分片状态
            String redisKey = "upload:" + userId + ":" + paperId;
            byte[] bitmapData = redisTemplate.execute((RedisCallback<byte[]>) connection -> {
                return connection.get(redisKey.getBytes());
            });

            if (bitmapData == null) {
                logger.info("Redis中无分片状态记录 => paperId: {}, userId: {}", paperId, userId);
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(paperId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(paperId, dbUploadedChunks, userId);
                    logger.info("已从数据库回填Redis分片状态 => paperId: {}, userId: {}, 已上传数量: {}",
                              paperId, userId, dbUploadedChunks.size());
                }
                return dbUploadedChunks;
            }

            if (bitmapData.length == 0) {
                logger.info("Redis中分片状态为空，尝试从数据库回源 => paperId: {}, userId: {}", paperId, userId);
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(paperId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(paperId, dbUploadedChunks, userId);
                }
                return dbUploadedChunks;
            }

            // 解析bitmap，找出已上传的分片
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                if (isBitSet(bitmapData, chunkIndex)) {
                    uploadedChunks.add(chunkIndex);
                }
            }

            if (uploadedChunks.isEmpty()) {
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(paperId);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(paperId, dbUploadedChunks, userId);
                    logger.info("Redis无有效分片位，已从数据库回填 => paperId: {}, userId: {}, 已上传数量: {}",
                              paperId, userId, dbUploadedChunks.size());
                    return dbUploadedChunks;
                }
                return uploadedChunks;
            }

            logger.info("获取到已上传分片列表 => paperId: {}, userId: {}, 已上传数量: {}, 总分片数: {}, 优化方式: 一次性获取",
                      paperId, userId, uploadedChunks.size(), totalChunks);
            return uploadedChunks;
        } catch (Exception e) {
            logger.error("获取已上传分片列表失败 => paperId: {}, userId: {}, 错误: {}", paperId, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to get uploaded chunks", e);
        }
    }

    private List<Integer> getUploadedChunksFromDatabase(String paperId) {
        logger.debug("从数据库查询已上传分片列表 => paperId: {}", paperId);
        return chunkInfoRepository.findChunkIndexesByPaperId(paperId);
    }

    private void backfillUploadedChunks(String paperId, List<Integer> uploadedChunks, String userId) {
        for (Integer chunkIndex : uploadedChunks) {
            if (chunkIndex != null && chunkIndex >= 0) {
                markChunkUploadedQuietly(paperId, chunkIndex, userId, "unknown");
            }
        }
    }

    private void markChunkUploadedQuietly(String paperId, int chunkIndex, String userId, String originalFilename) {
        try {
            logger.debug("标记分片为已上传 => paperId: {}, originalFilename: {}, chunkIndex: {}", paperId, originalFilename, chunkIndex);
            markChunkUploaded(paperId, chunkIndex, userId);
            logger.debug("分片标记完成 => paperId: {}, originalFilename: {}, chunkIndex: {}", paperId, originalFilename, chunkIndex);
        } catch (Exception e) {
            logger.error("标记分片已上传失败，数据库仍作为事实来源 => paperId: {}, originalFilename: {}, chunkIndex: {}, 错误: {}",
                      paperId, originalFilename, chunkIndex, e.getMessage(), e);
        }
    }

    /**
     * 检查bitmap中指定位置是否为1
     *
     * @param bitmapData bitmap数据
     * @param bitIndex 位索引
     * @return 指定位置是否为1
     */
    private boolean isBitSet(byte[] bitmapData, int bitIndex) {
        try {
            int byteIndex = bitIndex / 8;
            int bitPosition = 7 - (bitIndex % 8); // Redis bitmap的位顺序是从高位到低位

            if (byteIndex >= bitmapData.length) {
                return false; // 超出范围的位默认为0
            }

            return (bitmapData[byteIndex] & (1 << bitPosition)) != 0;
        } catch (Exception e) {
            logger.error("检查bitmap位状态失败 => bitIndex: {}, 错误: {}", bitIndex, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取文件的总分片数
     *
     * @param paperId 论文 PDF 的 paperId
     * @param userId 用户ID
     * @return 文件的总分片数
     */
    public int getTotalChunks(String paperId, String userId) {
        logger.info("计算文件总分片数 => paperId: {}, userId: {}", paperId, userId);
        try {
            Optional<Paper> paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);

            if (paper.isEmpty()) {
                logger.warn("论文记录不存在，无法计算分片数 => paperId: {}, userId: {}", paperId, userId);
                return 0;
            }

            long totalSize = paper.get().getTotalSize();
            // 默认每个分片5MB
            int chunkSize = 5 * 1024 * 1024;
            int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);

            logger.info("文件总分片数计算结果 => paperId: {}, userId: {}, totalSize: {}, chunkSize: {}, totalChunks: {}",
                      paperId, userId, totalSize, chunkSize, totalChunks);
            return totalChunks;
        } catch (Exception e) {
            logger.error("计算文件总分片数失败 => paperId: {}, userId: {}, 错误: {}", paperId, userId, e.getMessage(), e);
            throw new RuntimeException("Failed to calculate total chunks", e);
        }
    }

    /**
     * 保存分片信息到数据库
     *
     * @param paperId 论文 PDF 的 paperId
     * @param chunkIndex 分片索引
     * @param chunkMd5 分片的 MD5 值
     * @param storagePath 分片的存储路径
     */
    private void saveChunkInfo(String paperId, int chunkIndex, String chunkMd5, String storagePath) {
        logger.debug("保存分片信息到数据库 => paperId: {}, chunkIndex: {}, chunkMd5: {}, storagePath: {}",
                   paperId, chunkIndex, chunkMd5, storagePath);
        try {
            ChunkInfo chunkInfo = new ChunkInfo();
            chunkInfo.setPaperId(paperId);
            chunkInfo.setChunkIndex(chunkIndex);
            chunkInfo.setChunkMd5(chunkMd5);
            chunkInfo.setStoragePath(storagePath);

            chunkInfoRepository.save(chunkInfo);
            logger.debug("分片信息已保存 => paperId: {}, chunkIndex: {}", paperId, chunkIndex);
        } catch (DataIntegrityViolationException e) {
            logger.info("分片信息已存在，按幂等成功处理 => paperId: {}, chunkIndex: {}", paperId, chunkIndex);
        } catch (Exception e) {
            logger.error("保存分片信息失败 => paperId: {}, chunkIndex: {}, 错误: {}",
                      paperId, chunkIndex, e.getMessage(), e);
            throw new RuntimeException("Failed to save chunk info", e);
        }
    }

    /**
     * 合并所有分片
     *
     * @param paperId 论文 PDF 的 paperId
     * @param originalFilename 原始 PDF 文件名
     * @param userId 用户ID
     * @return 合成文件的访问 URL
     */
    public String mergeChunks(String paperId, String originalFilename, String userId) {
        String fileType = getFileType(originalFilename);
        logger.info("开始合并论文 PDF 分片 => paperId: {}, originalFilename: {}, fileType: {}, userId: {}", paperId, originalFilename, fileType, userId);
        try {
            // 查询所有分片信息
            logger.debug("查询分片信息 => paperId: {}, originalFilename: {}", paperId, originalFilename);
            List<ChunkInfo> chunks = chunkInfoRepository.findByPaperIdOrderByChunkIndexAsc(paperId);
            logger.info("查询到分片信息 => paperId: {}, originalFilename: {}, fileType: {}, 分片数量: {}", paperId, originalFilename, fileType, chunks.size());

            // 检查分片数量是否与预期一致
            int expectedChunks = getTotalChunks(paperId, userId);
            if (chunks.size() != expectedChunks) {
                logger.error("分片数量不匹配 => paperId: {}, originalFilename: {}, fileType: {}, 期望: {}, 实际: {}",
                          paperId, originalFilename, fileType, expectedChunks, chunks.size());
                throw new RuntimeException(String.format(
                    "分片数量不匹配，期望: %d, 实际: %d", expectedChunks, chunks.size()));
            }

            List<String> partPaths = chunks.stream()
                    .map(ChunkInfo::getStoragePath)
                    .collect(Collectors.toList());
            logger.debug("分片路径列表 => paperId: {}, originalFilename: {}, 路径数量: {}", paperId, originalFilename, partPaths.size());

            // 检查每个分片是否存在
            logger.info("开始检查每个分片是否存在 => paperId: {}, originalFilename: {}, fileType: {}", paperId, originalFilename, fileType);
            for (int i = 0; i < partPaths.size(); i++) {
                String path = partPaths.get(i);
                try {
                    StatObjectResponse stat = minioClient.statObject(
                        StatObjectArgs.builder()
                            .bucket("uploads")
                            .object(path)
                            .build()
                    );
                    logger.debug("分片存在 => originalFilename: {}, index: {}, path: {}, size: {}", originalFilename, i, path, stat.size());
                } catch (Exception e) {
                    logger.error("分片不存在或无法访问 => originalFilename: {}, index: {}, path: {}, 错误: {}",
                              originalFilename, i, path, e.getMessage(), e);
                    throw new RuntimeException("分片 " + i + " 不存在或无法访问: " + e.getMessage(), e);
                }
            }
            logger.info("分片检查完成，所有分片都存在 => paperId: {}, originalFilename: {}, fileType: {}", paperId, originalFilename, fileType);

            // 使用 MD5 作为 MinIO 对象路径，确保同名不同内容的文件不会互相覆盖
            String mergedPath = "merged/" + paperId;
            logger.info("开始合并分片 => paperId: {}, originalFilename: {}, fileType: {}, 合并后路径: {}", paperId, originalFilename, fileType, mergedPath);

            try {
                // 合并分片
                List<ComposeSource> sources = partPaths.stream()
                        .map(path -> ComposeSource.builder().bucket("uploads").object(path).build())
                        .collect(Collectors.toList());

                logger.debug("构建合并请求 => paperId: {}, originalFilename: {}, targetPath: {}, sourcePaths: {}",
                          paperId, originalFilename, mergedPath, partPaths);

                minioClient.composeObject(
                        ComposeObjectArgs.builder()
                                .bucket("uploads")
                                .object(mergedPath)
                                .sources(sources)
                                .headers(PdfDownloadHeaders.objectHeaders())
                                .build()
                );
                logger.info("分片合并成功 => paperId: {}, originalFilename: {}, fileType: {}, mergedPath: {}", paperId, originalFilename, fileType, mergedPath);

                // 检查合并后的文件
                StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                        .bucket("uploads")
                        .object(mergedPath)
                        .build()
                );
                logger.info("合并论文信息 => paperId: {}, originalFilename: {}, fileType: {}, path: {}, size: {}", paperId, originalFilename, fileType, mergedPath, stat.size());

                // 清理分片文件
                logger.info("开始清理分片文件 => paperId: {}, originalFilename: {}, 分片数量: {}", paperId, originalFilename, partPaths.size());
                for (String path : partPaths) {
                    try {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket("uploads")
                                        .object(path)
                                        .build()
                        );
                        logger.debug("分片文件已删除 => originalFilename: {}, path: {}", originalFilename, path);
                    } catch (Exception e) {
                        // 记录错误但不中断流程
                        logger.warn("删除分片文件失败，将继续处理 => originalFilename: {}, path: {}, 错误: {}", originalFilename, path, e.getMessage());
                    }
                }
                logger.info("分片文件清理完成 => paperId: {}, originalFilename: {}, fileType: {}", paperId, originalFilename, fileType);

                // 删除 Redis 中的分片状态记录
                logger.info("删除Redis中的分片状态记录 => paperId: {}, originalFilename: {}, userId: {}", paperId, originalFilename, userId);
                deleteFileMark(paperId, userId);
                logger.info("分片状态记录已删除 => paperId: {}, originalFilename: {}, userId: {}", paperId, originalFilename, userId);

                // 更新论文状态
                logger.info("更新论文状态为已完成 => paperId: {}, originalFilename: {}, fileType: {}, userId: {}", paperId, originalFilename, fileType, userId);
                Paper paper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId)
                        .orElseThrow(() -> {
                            logger.error("更新论文状态失败，论文记录不存在 => paperId: {}, originalFilename: {}", paperId, originalFilename);
                            return new RuntimeException("论文记录不存在: " + paperId);
                        });
                paper.setStatus(Paper.STATUS_COMPLETED);
                paper.setMergedAt(LocalDateTime.now());
                paperRepository.save(paper);
                logger.info("论文状态已更新为已完成 => paperId: {}, originalFilename: {}, fileType: {}", paperId, originalFilename, fileType);

                // 生成预签名 URL（有效期为 1 小时）
                logger.info("开始生成预签名URL => paperId: {}, originalFilename: {}, path: {}", paperId, originalFilename, mergedPath);
                String presignedUrl = generateMergedObjectUrl(paperId);
                logger.info("预签名URL已生成 => paperId: {}, originalFilename: {}, fileType: {}, URL: {}", paperId, originalFilename, fileType, presignedUrl);

                return presignedUrl;
            } catch (Exception e) {
                logger.error("合并文件失败 => paperId: {}, originalFilename: {}, fileType: {}, 错误类型: {}, 错误信息: {}",
                          paperId, originalFilename, fileType, e.getClass().getName(), e.getMessage(), e);
                throw new RuntimeException("合并文件失败: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            logger.error("文件合并过程中发生错误 => paperId: {}, originalFilename: {}, fileType: {}, 错误类型: {}, 错误信息: {}",
                      paperId, originalFilename, fileType, e.getClass().getName(), e.getMessage(), e);
            throw new RuntimeException("文件合并失败: " + e.getMessage(), e);
        }
    }

    public GetObjectResponse getMergedFileStream(String paperId) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object("merged/" + paperId)
                        .build()
        );
    }

    public InputStream getMergedFileRangeStream(String paperId, long offset, long length) throws Exception {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("offset and length must be non-negative");
        }

        return minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("uploads")
                        .object("merged/" + paperId)
                        .offset(offset)
                        .length(length)
                        .build()
        );
    }

    public String generateMergedObjectUrl(String paperId) throws Exception {
        String originalFilename = paperRepository.findFirstByPaperIdOrderByCreatedAtDesc(paperId)
                .map(Paper::getOriginalFilename)
                .orElse(null);
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket("uploads")
                        .object("merged/" + paperId)
                        .expiry(1, TimeUnit.HOURS)
                        .extraQueryParams(PdfDownloadHeaders.presignedQueryParams(originalFilename, paperId))
                        .build()
        );
    }

    /**
     * 转换为公开 URL
     * @param minioUrl
     * @return
     */
    public String transToPublicUrl(String minioUrl) {
        if (StringUtils.isBlank(minioUrl) || Objects.equals(minioConfig.getEndpoint(), minioConfig.getPublicUrl())) {
            return minioUrl;
        }
        return minioUrl.replaceFirst(minioConfig.getEndpoint(), minioConfig.getPublicUrl());
    }

    private Paper getOrCreatePaper(String paperId,
                                   long totalSize,
                                   String originalFilename,
                                   String orgTag,
                                   boolean isPublic,
                                   String userId,
                                   String fileType) {
        Optional<Paper> existingPaper = paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId);
        if (existingPaper.isPresent()) {
            return existingPaper.get();
        }

        logger.info("创建新的论文记录 => paperId: {}, originalFilename: {}, fileType: {}, totalSize: {}, userId: {}, orgTag: {}, isPublic: {}",
                paperId, originalFilename, fileType, totalSize, userId, orgTag, isPublic);

        Paper paper = new Paper();
        paper.setPaperId(paperId);
        paper.setOriginalFilename(originalFilename);
        paper.setPaperTitle(originalFilename);
        paper.setTotalSize(totalSize);
        paper.setStatus(Paper.STATUS_UPLOADING);
        paper.setUserId(userId);
        paper.setOrgTag(orgTag);
        paper.setPublic(isPublic);
        paper.setVectorizationStatus(Paper.VECTORIZATION_STATUS_PENDING);
        paper.setVectorizationErrorMessage(null);

        try {
            return paperRepository.save(paper);
        } catch (DataIntegrityViolationException e) {
            logger.info("论文记录已存在，按幂等成功处理 => paperId: {}, userId: {}", paperId, userId);
            return paperRepository.findFirstByPaperIdAndUserIdOrderByCreatedAtDesc(paperId, userId)
                    .orElseThrow(() -> new RuntimeException("论文记录并发创建后查询失败", e));
        } catch (Exception e) {
            logger.error("创建论文记录失败 => paperId: {}, originalFilename: {}, fileType: {}, 错误: {}", paperId, originalFilename, fileType, e.getMessage(), e);
            throw new RuntimeException("创建论文记录失败: " + e.getMessage(), e);
        }
    }
}
