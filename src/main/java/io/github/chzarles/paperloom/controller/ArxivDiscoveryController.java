package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.discovery.ArxivDiscoveryService;
import io.github.chzarles.paperloom.service.discovery.ArxivSearchPage;
import io.github.chzarles.paperloom.service.discovery.ArxivImportResult;
import io.github.chzarles.paperloom.service.discovery.ArxivPaper;
import io.github.chzarles.paperloom.service.discovery.ArxivPdfTooLargeException;
import io.github.chzarles.paperloom.service.discovery.ArxivResponseException;
import io.github.chzarles.paperloom.service.discovery.ArxivSearchHit;
import io.github.chzarles.paperloom.service.discovery.ArxivUnavailableException;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * arXiv 论文发现接口：检索元数据、把用户点选的一篇论文导入文献库。
 *
 * <p>这里的返回值只是元数据导航，不能当作论文内容证据；导入后仍走现有 PDF 解析与索引链路。
 */
@RestController
@RequestMapping("/api/v1/discovery/arxiv")
public class ArxivDiscoveryController {

    private final ArxivDiscoveryService arxivDiscoveryService;

    public ArxivDiscoveryController(ArxivDiscoveryService arxivDiscoveryService) {
        this.arxivDiscoveryService = arxivDiscoveryService;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam("query") String query,
            @RequestParam(value = "start", required = false) Integer start,
            @RequestParam(value = "max", required = false) Integer max,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestAttribute("userId") String userId) {
        try {
            ArxivSearchPage page = arxivDiscoveryService.search(query, start, max, sort, userId);
            List<Map<String, Object>> items = page.items().stream().map(this::toItem).toList();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", page.total());
            data.put("hasNext", page.hasNext());
            data.put("items", items);
            return ResponseEntity.ok(success(data));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ArxivUnavailableException | ArxivResponseException e) {
            return error(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "arXiv 检索失败: " + e.getMessage());
        }
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importPaper(
            @RequestBody(required = false) ImportRequest request,
            @RequestAttribute("userId") String userId) {
        if (request == null || request.arxivId() == null || request.arxivId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "arxivId 不能为空");
        }
        try {
            ArxivImportResult result = arxivDiscoveryService.importPaper(request.arxivId(), userId);
            LogUtils.logBusiness("ARXIV_IMPORT", userId,
                    "arXiv 导入结果: arxivId=%s, paperId=%s, status=%s",
                    result.arxivId(), result.paperId(), result.status());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("paperId", result.paperId());
            data.put("arxivId", result.arxivId());
            data.put("paperTitle", result.title());
            data.put("status", result.status());
            data.put("message", result.message());
            return ResponseEntity.ok(success(data));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ArxivPdfTooLargeException e) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (ArxivUnavailableException | ArxivResponseException e) {
            return error(HttpStatus.BAD_GATEWAY, e.getMessage());
        } catch (CustomException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "arXiv 导入失败: " + e.getMessage());
        }
    }

    private Map<String, Object> toItem(ArxivSearchHit hit) {
        ArxivPaper paper = hit.paper();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("arxivId", paper.arxivId());
        item.put("title", paper.title());
        item.put("summary", paper.summary());
        item.put("authors", paper.authors());
        item.put("categories", paper.categories());
        item.put("publishedYear", paper.publishedYear());
        item.put("absUrl", paper.absUrl());
        item.put("pdfUrl", paper.pdfUrl());
        item.put("libraryStatus", hit.libraryStatus());
        return item;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", HttpStatus.OK.value());
        response.put("message", "success");
        response.put("data", data);
        return response;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", status.value());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    public record ImportRequest(String arxivId) {
    }
}
