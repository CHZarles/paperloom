package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.ReadingModelQdrantIndexService;
import io.github.chzarles.paperloom.service.QdrantReadingModelReindexService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retrieval")
public class QdrantAdminController {

    private final QdrantReadingModelReindexService reindexService;
    private final ReadingModelQdrantIndexService indexService;

    public QdrantAdminController(QdrantReadingModelReindexService reindexService,
                                 ReadingModelQdrantIndexService indexService) {
        this.reindexService = reindexService;
        this.indexService = indexService;
    }

    @PostMapping("/rebuild-all")
    public ResponseEntity<?> reindexCurrent(
            @RequestAttribute("userId") String requesterId) {
        try {
            QdrantReadingModelReindexService.ReindexResult result = reindexService.reindexAllCurrent(requesterId);
            return ResponseEntity.status(result.completed() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(result);
        } catch (IllegalStateException error) {
            return maintenanceError(error);
        }
    }

    @PostMapping("/papers/{paperId}/rebuild")
    public ResponseEntity<?> rebuildPaper(
            @PathVariable String paperId,
            @RequestAttribute("userId") String requesterId) {
        try {
            return ResponseEntity.ok(indexService.rebuildCurrentModel(paperId, requesterId));
        } catch (IllegalStateException error) {
            return maintenanceError(error);
        }
    }

    @GetMapping("/rebuild-all/status")
    public ResponseEntity<?> rebuildStatus() {
        return reindexService.fullRebuildStatus()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> maintenanceError(IllegalStateException error) {
        String message = error.getMessage() == null ? "RETRIEVAL_BACKEND_UNAVAILABLE" : error.getMessage();
        HttpStatus status = message.contains("RUNNING") || message.contains("CONFLICT")
                ? HttpStatus.CONFLICT
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(java.util.Map.of(
                "code", status.value(),
                "message", message
        ));
    }
}
