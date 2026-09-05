package io.github.chzarles.paperloom.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.chzarles.paperloom.config.KafkaConfig;
import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperProcessingTask;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.service.ArxivClient;
import io.github.chzarles.paperloom.service.PaperSearchabilityService;
import io.github.chzarles.paperloom.model.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/arxiv")
public class ArxivImportController {

    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final PaperSearchabilityService searchabilityService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaConfig kafkaConfig;

    public ArxivImportController(UserRepository userRepository,
                                 PaperRepository paperRepository,
                                 PaperSearchabilityService searchabilityService,
                                 KafkaTemplate<String, Object> kafkaTemplate,
                                 KafkaConfig kafkaConfig) {
        this.userRepository = userRepository;
        this.paperRepository = paperRepository;
        this.searchabilityService = searchabilityService;
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaConfig = kafkaConfig;
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importPaper(
            Authentication authentication,
            @Valid @RequestBody ArxivImportRequest request) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required"));
        String arxivId = normalizeVersionedId(request.arxivId());

        Paper existing = paperRepository.findFirstByArxivIdAndUserIdOrderByCreatedAtDesc(
                arxivId, String.valueOf(user.getId())).orElse(null);
        if (existing != null) {
            String status = searchabilityService.isSearchable(existing) ? "ready" :
                    Paper.VECTORIZATION_STATUS_FAILED.equalsIgnoreCase(existing.getVectorizationStatus())
                            ? "failed" : "processing";
            return ResponseEntity.ok(response(arxivId, status, existing.getPaperId()));
        }

        PaperProcessingTask task = new PaperProcessingTask(
                arxivId,
                null,
                arxivId,
                String.valueOf(user.getId()),
                PaperProcessingTask.TASK_TYPE_ARXIV_IMPORT,
                String.valueOf(user.getId())
        );
        task.setArxivId(arxivId);
        kafkaTemplate.executeInTransaction(template -> {
            template.send(kafkaConfig.getPaperProcessingTopic(), arxivId, task);
            return true;
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response(arxivId, "queued", null));
    }

    private String normalizeVersionedId(String raw) {
        String value = ArxivClient.normalizeVersionedId(raw);
        if (value == null || !value.matches(".*v\\d+$")) {
            throw new IllegalArgumentException("a versioned arXiv ID is required");
        }
        return value;
    }

    private Map<String, Object> response(String arxivId, String status, String paperId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("arxiv_id", arxivId);
        response.put("status", status);
        if (paperId != null) {
            response.put("paper_id", paperId);
        }
        return response;
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "ARXIV_ARGUMENTS_INVALID",
                "message", "A versioned arXiv ID is required"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> authenticationFailure(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of(
                "error", error.getStatusCode().value() == 401 ? "UNAUTHORIZED" : "ARXIV_IMPORT_UNAVAILABLE",
                "message", error.getReason() == null ? "Request failed" : error.getReason()));
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ArxivImportRequest(
            @NotBlank @Size(max = 64) @JsonProperty("arxiv_id") String arxivId,
            @JsonAnySetter Map<String, Object> unexpected) {
        public ArxivImportRequest {
            arxivId = arxivId == null ? "" : arxivId.trim();
            if (unexpected != null && !unexpected.isEmpty()) {
                throw new IllegalArgumentException("unexpected arXiv import fields");
            }
        }
    }
}
