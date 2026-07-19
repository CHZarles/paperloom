package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.PaperPublication;
import io.github.chzarles.paperloom.service.PaperPublicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/v1/admin/papers")
public class PaperPublicationAdminController {

    private final PaperPublicationService publicationService;

    public PaperPublicationAdminController(PaperPublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @PostMapping("/{paperId}/publication")
    public ResponseEntity<?> publish(@PathVariable String paperId,
                                     @RequestAttribute("userId") String userId) {
        try {
            PaperPublication publication = publicationService.publish(paperId, userId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("paperId", publication.getPaperId());
            response.put("publishedBy", publication.getPublishedBy());
            response.put("publishedAt", publication.getPublishedAt());
            return ResponseEntity.ok(response);
        } catch (CustomException error) {
            return ResponseEntity.status(error.getStatus()).body(Map.of(
                    "code", error.getStatus().value(),
                    "message", error.getMessage()
            ));
        }
    }

    @DeleteMapping("/{paperId}/publication")
    public ResponseEntity<?> unpublish(@PathVariable String paperId,
                                       @RequestAttribute("userId") String userId) {
        try {
            publicationService.unpublish(paperId, userId);
            return ResponseEntity.ok(Map.of("paperId", paperId, "published", false));
        } catch (CustomException error) {
            return ResponseEntity.status(error.getStatus()).body(Map.of(
                    "code", error.getStatus().value(),
                    "message", error.getMessage()
            ));
        }
    }
}
