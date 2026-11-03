package io.github.chzarles.paperloom.service.discovery;

import java.util.List;

/**
 * arXiv 元数据条目。只描述检索结果，不是论文正文，也不能作为回答证据。
 *
 * @param arxivId       去掉版本号后的 arXiv 标识，例如 2409.04701
 * @param title         标题
 * @param summary       摘要原文
 * @param authors       作者名列表
 * @param categories    arXiv 分类，主分类在前
 * @param publishedYear 发布年份，无法解析时为 null
 * @param absUrl        摘要页地址
 * @param pdfUrl        PDF 地址
 */
public record ArxivPaper(
        String arxivId,
        String title,
        String summary,
        List<String> authors,
        List<String> categories,
        Integer publishedYear,
        String absUrl,
        String pdfUrl
) {

    public ArxivPaper {
        authors = authors == null ? List.of() : List.copyOf(authors);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public String authorsText() {
        return String.join(", ", authors);
    }
}
