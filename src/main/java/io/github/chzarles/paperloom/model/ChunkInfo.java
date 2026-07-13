package io.github.chzarles.paperloom.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * ChunkInfo 类用于表示论文 PDF 分片上传的信息
 * 它是一个实体类，与数据库中的 'chunk_info' 表对应
 * 该类用来存储每个论文 PDF 分片的元数据，包括分片的唯一标识、所属 paperId、分片顺序、校验码和存储位置
 */
@Data
@Entity
@Table(
        name = "chunk_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_file_md5_chunk_index", columnNames = {"file_md5", "chunk_index"})
)
public class ChunkInfo {
    /**
     * 分块信息的唯一标识符
     * 由数据库自动生成，用于唯一确定一个分块信息
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 论文 PDF 的内容哈希。
     */
    @Column(name = "file_md5", nullable = false, length = 32)
    private String paperId;

    /**
     * 分块的索引号
     * 表示文件中的第几个分块，用于保持分块的顺序
     */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /**
     * 分块的MD5值
     * 每个分块的唯一标识，用于校验分块的完整性和正确性
     */
    @Column(name = "chunk_md5", nullable = false, length = 32)
    private String chunkMd5;

    /**
     * 分块的存储路径
     * 表示分块在系统中的存储位置，可以是绝对路径或相对路径
     */
    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;
}
