package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.controller.dto.CollectionRequests.AddCollectionPapersRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.CreateCollectionRequest;
import io.github.chzarles.paperloom.controller.dto.CollectionRequests.UpdateCollectionRequest;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.PaperCollectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/paper-collections")
public class PaperCollectionController {

    private final PaperCollectionService paperCollectionService;

    public PaperCollectionController(PaperCollectionService paperCollectionService) {
        this.paperCollectionService = paperCollectionService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCollections(@RequestAttribute("userId") String userId) {
        return handleWithData("获取论文集合成功",
                () -> paperCollectionService.listCollections(parseUserId(userId)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCollection(@RequestBody CreateCollectionRequest request,
                                                               @RequestAttribute("userId") String userId) {
        return handleWithData("论文集合创建成功",
                () -> paperCollectionService.createCollection(parseUserId(userId), request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCollection(@PathVariable Long id,
                                                            @RequestAttribute("userId") String userId) {
        return handleWithData("获取论文集合详情成功",
                () -> paperCollectionService.getCollection(parseUserId(userId), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateCollection(@PathVariable Long id,
                                                               @RequestBody UpdateCollectionRequest request,
                                                               @RequestAttribute("userId") String userId) {
        return handleWithData("论文集合更新成功",
                () -> paperCollectionService.updateCollection(parseUserId(userId), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCollection(@PathVariable Long id,
                                                               @RequestAttribute("userId") String userId) {
        return handleWithoutData("论文集合删除成功",
                () -> paperCollectionService.deleteCollection(parseUserId(userId), id));
    }

    @PostMapping("/{id}/papers")
    public ResponseEntity<Map<String, Object>> addPapers(@PathVariable Long id,
                                                        @RequestBody AddCollectionPapersRequest request,
                                                        @RequestAttribute("userId") String userId) {
        return handleWithData("论文集合成员已添加",
                () -> paperCollectionService.addPapers(parseUserId(userId), id, request));
    }

    @DeleteMapping("/{id}/papers/{paperId}")
    public ResponseEntity<Map<String, Object>> removePaper(@PathVariable Long id,
                                                          @PathVariable String paperId,
                                                          @RequestAttribute("userId") String userId) {
        return handleWithoutData("论文集合成员已移除",
                () -> paperCollectionService.removePaper(parseUserId(userId), id, paperId));
    }

    private Long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException | NullPointerException e) {
            throw new CustomException("Invalid user id", HttpStatus.BAD_REQUEST);
        }
    }

    private ResponseEntity<Map<String, Object>> handleWithData(String message, DataOperation operation) {
        try {
            return ResponseEntity.ok(response(HttpStatus.OK, message, operation.execute()));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(response(e.getStatus(), e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage(), null));
        }
    }

    private ResponseEntity<Map<String, Object>> handleWithoutData(String message, VoidOperation operation) {
        try {
            operation.execute();
            return ResponseEntity.ok(response(HttpStatus.OK, message, null));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(response(e.getStatus(), e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage(), null));
        }
    }

    private Map<String, Object> response(HttpStatus status, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        if (data != null) {
            body.put("data", data);
        }
        return body;
    }

    @FunctionalInterface
    private interface DataOperation {
        Object execute();
    }

    @FunctionalInterface
    private interface VoidOperation {
        void execute();
    }
}
