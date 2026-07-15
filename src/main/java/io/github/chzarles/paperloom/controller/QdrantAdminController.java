package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.service.QdrantReadingModelReindexService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retrieval")
public class QdrantAdminController {

    private final QdrantReadingModelReindexService reindexService;

    public QdrantAdminController(QdrantReadingModelReindexService reindexService) {
        this.reindexService = reindexService;
    }

    @PostMapping("/reindex-current")
    public ResponseEntity<QdrantReadingModelReindexService.ReindexResult> reindexCurrent(
            @RequestAttribute("userId") String requesterId) {
        QdrantReadingModelReindexService.ReindexResult result = reindexService.reindexAllCurrent(requesterId);
        return ResponseEntity.status(result.completed() ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(result);
    }
}
