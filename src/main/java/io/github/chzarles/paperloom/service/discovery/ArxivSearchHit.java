package io.github.chzarles.paperloom.service.discovery;

/**
 * 检索命中项：arXiv 元数据加上"当前用户的文献库里有没有这篇"。
 *
 * @param paper         arXiv 元数据
 * @param libraryStatus NONE 未入库、PROCESSING 解析中、READY 已可检索、FAILED 当前不可检索但可重试
 */
public record ArxivSearchHit(ArxivPaper paper, String libraryStatus) {

    public static final String LIBRARY_NONE = "NONE";
    public static final String LIBRARY_PROCESSING = "PROCESSING";
    public static final String LIBRARY_READY = "READY";
    public static final String LIBRARY_FAILED = "FAILED";
}
