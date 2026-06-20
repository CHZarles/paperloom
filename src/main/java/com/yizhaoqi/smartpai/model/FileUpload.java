package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 论文 PDF 上传实体。
 * 现有表名和列名保留 file 前缀，业务层统一把 file_md5 映射为 paperId。
 */
@Data
@Entity
@Table(name = "file_upload")
public class FileUpload {
    public static final int STATUS_UPLOADING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_MERGING = 2;
    public static final String VECTORIZATION_STATUS_PENDING = "PENDING";
    public static final String VECTORIZATION_STATUS_PROCESSING = "PROCESSING";
    public static final String VECTORIZATION_STATUS_COMPLETED = "COMPLETED";
    public static final String VECTORIZATION_STATUS_FAILED = "FAILED";

    /**
     * 论文 PDF 的内容哈希。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 自增主键

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    /**
     * 上传时的 PDF 文件名，当前作为 paperTitle。
     */
    private String fileName;

    /**
     * PDF 文件大小，单位为字节。
     */
    private long totalSize;

    /**
     * PDF 上传状态。
     * 0表示正在上传，1表示已合并完成，2表示正在合并。
     */
    private int status; // 0-上传中 1-已完成 2-合并中

    /**
     * 上传论文的用户标识。
     */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;
    
    /**
     * 论文所属组织标签。
     */
    @Column(name = "org_tag")
    private String orgTag;

    /**
     * 论文是否公开。
     * true表示所有用户可访问，false表示仅组织内用户可访问
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "estimated_embedding_tokens")
    private Long estimatedEmbeddingTokens;

    @Column(name = "estimated_chunk_count")
    private Integer estimatedChunkCount;

    @Column(name = "actual_embedding_tokens")
    private Long actualEmbeddingTokens;

    @Column(name = "actual_chunk_count")
    private Integer actualChunkCount;

    @Column(name = "vectorization_status", length = 32)
    private String vectorizationStatus;

    @Column(name = "vectorization_error_message", length = 1000)
    private String vectorizationErrorMessage;

    /**
     * PDF 上传开始时间。
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * PDF 合并完成时间。
     */
    @UpdateTimestamp
    private LocalDateTime mergedAt;
}
