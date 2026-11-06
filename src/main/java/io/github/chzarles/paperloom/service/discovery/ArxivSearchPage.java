package io.github.chzarles.paperloom.service.discovery;

import java.util.List;

/**
 * 一页 arXiv 检索结果。
 *
 * @param total  arXiv 报告的命中总数，未知时为 -1
 * @param start  本页起始偏移
 * @param pageSize 本页请求条数
 */
public record ArxivSearchPage(List<ArxivSearchHit> items, int total, int start, int pageSize) {

    public boolean hasNext() {
        if (total < 0) {
            return items.size() == pageSize;
        }
        return start + items.size() < total;
    }
}
