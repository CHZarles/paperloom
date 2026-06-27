package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.HybridSearchService;
import com.yizhaoqi.smartpai.service.RetrievalBudget;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

// 提供论文混合检索接口
@RestController
@RequestMapping("/api/v1/papers/search")
public class PaperSearchController {

    @Autowired
    private HybridSearchService hybridSearchService;

    /**
     * 论文混合检索接口
     * 
     * URL: /api/v1/papers/search/hybrid
     * Method: GET
     * Parameters:
     *   - query: 搜索查询字符串（必需）
     *   - pageBatchSize: ES 技术分页批次（可选，默认32）
     * 
     * 示例: /api/v1/papers/search/hybrid?query=attention mechanism&pageBatchSize=32
     * 
     * Response:
     * [
     *   {
     *     "paperId": "abc123...",
     *     "chunkId": 1,
     *     "textContent": "人工智能是未来科技发展的核心方向。",
     *     "score": 0.92,
     *     "userId": "user123",
     *     "orgTag": "TECH_DEPT",
     *     "isPublic": true
     *   }
     * ]
     */
    @GetMapping("/hybrid")
    public Map<String, Object> hybridSearch(@RequestParam String query,
                                            @RequestParam(defaultValue = "32") int pageBatchSize,
                                            @RequestAttribute("userId") String userId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("HYBRID_SEARCH");
        try {
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "开始论文混合检索: query=%s, pageBatchSize=%d", query, pageBatchSize);
            
            HybridSearchService.AdaptiveSearchResult searchResult = hybridSearchService.adaptiveSearchWithPermission(
                    query,
                    userId,
                    RetrievalBudget.forPageBatch(pageBatchSize)
            );
            List<SearchResult> results = searchResult.results();
            
            LogUtils.logUserOperation(userId != null ? userId : "anonymous", "HYBRID_SEARCH", 
                    "search_query", "SUCCESS");
            LogUtils.logBusiness("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "论文混合检索完成: 返回结果数量=%d", results.size());
            monitor.end("论文混合检索成功");
            
            // 构造统一响应结构
            Map<String, Object> responseBody = new HashMap<>(4);
            responseBody.put("code", 200);
            responseBody.put("message", "success");
            responseBody.put("data", results);
            responseBody.put("diagnostics", Map.of(
                    "scannedCount", searchResult.scannedCount(),
                    "acceptedEvidenceCount", searchResult.acceptedEvidenceCount(),
                    "sourceCount", searchResult.sourceCount(),
                    "stopReason", searchResult.stopReason().name()
            ));
            
            return responseBody;
        } catch (Exception e) {
            LogUtils.logBusinessError("HYBRID_SEARCH", userId != null ? userId : "anonymous", 
                    "论文混合检索失败: query=%s", e, query);
            monitor.end("论文混合检索失败: " + e.getMessage());
            
            // 构造错误响应结构，保持与前端解析一致
            Map<String, Object> errorBody = new HashMap<>(4);
            errorBody.put("code", 500);
            errorBody.put("message", e.getMessage());
            errorBody.put("data", Collections.emptyList());
            return errorBody;
        }
    }
}
