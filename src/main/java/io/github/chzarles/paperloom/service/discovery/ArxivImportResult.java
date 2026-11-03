package io.github.chzarles.paperloom.service.discovery;

/**
 * 一次 arXiv 导入的产品结果。
 *
 * @param paperId 入库后的论文 ID（PDF 内容 MD5）
 * @param arxivId 规范化后的 arXiv 标识
 * @param title   论文标题
 * @param status  IMPORTED（本次已入库并排队解析）、PROCESSING（已在解析中）、READY（已可检索）
 * @param message 面向用户的状态说明
 */
public record ArxivImportResult(
        String paperId,
        String arxivId,
        String title,
        String status,
        String message
) {
    public static final String STATUS_IMPORTED = "IMPORTED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
}
