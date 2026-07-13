package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.Paper;
import io.github.chzarles.paperloom.model.PaperReadingModelStatus;
import io.github.chzarles.paperloom.repository.PaperRepository;
import io.github.chzarles.paperloom.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ProductPaperHandleService {

    private static final String HANDLE_PREFIX = "paper_handle_";
    private static final int HANDLE_HASH_LENGTH = 22;

    private final PaperService paperService;
    private final PaperReadingModelRepository modelRepository;
    private final PaperRepository paperRepository;
    private final ConcurrentMap<String, String> handlesByPaperId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> paperIdsByHandle = new ConcurrentHashMap<>();

    public ProductPaperHandleService(PaperService paperService,
                                     PaperReadingModelRepository modelRepository,
                                     PaperRepository paperRepository) {
        this.paperService = paperService;
        this.modelRepository = modelRepository;
        this.paperRepository = paperRepository;
    }

    public String handleForPaperId(String paperId) {
        String normalizedPaperId = normalize(paperId);
        if (normalizedPaperId.isBlank()) {
            throw new IllegalArgumentException("paperId must not be blank");
        }
        return handlesByPaperId.computeIfAbsent(normalizedPaperId, key -> {
            String handle = HANDLE_PREFIX + hashToken(key);
            paperIdsByHandle.putIfAbsent(handle, key);
            return handle;
        });
    }

    public Optional<String> resolvePaperHandle(String paperHandle) {
        String handle = normalize(paperHandle);
        if (!handle.startsWith(HANDLE_PREFIX)) {
            return Optional.empty();
        }
        String cachedPaperId = paperIdsByHandle.get(handle);
        if (cachedPaperId != null) {
            return Optional.of(cachedPaperId);
        }
        return resolvePaperHandleFromRepository(handle);
    }

    @Transactional(readOnly = true)
    public boolean isPaperVisibleToUser(String paperId, Long userId, SourceScope lockedScope) {
        String normalizedPaperId = normalize(paperId);
        if (normalizedPaperId.isBlank()) {
            return false;
        }
        SourceScope scope = lockedScope == null ? SourceScope.auto() : lockedScope;
        if (!scope.paperIds().isEmpty() && !scope.paperIds().contains(normalizedPaperId)) {
            return false;
        }

        List<Paper> accessiblePapers = paperService.getAccessiblePapers(userId(userId), null);
        return accessiblePapers != null && accessiblePapers.stream()
                .anyMatch(paper -> paper != null && normalizedPaperId.equals(normalize(paper.getPaperId())));
    }

    @Transactional(readOnly = true)
    public boolean hasCurrentReadyReadingModel(String paperId) {
        String normalizedPaperId = normalize(paperId);
        if (normalizedPaperId.isBlank()) {
            return false;
        }
        return modelRepository.findFirstByPaperIdAndIsCurrentTrue(normalizedPaperId)
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY)
                .isPresent();
    }

    private String hashToken(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return encoded.substring(0, Math.min(HANDLE_HASH_LENGTH, encoded.length()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Optional<String> resolvePaperHandleFromRepository(String paperHandle) {
        if (paperRepository == null) {
            return Optional.empty();
        }
        List<String> paperIds = paperRepository.findDistinctPaperIds();
        if (paperIds == null) {
            return Optional.empty();
        }
        for (String paperId : paperIds) {
            String normalizedPaperId = normalize(paperId);
            if (normalizedPaperId.isBlank()) {
                continue;
            }
            String candidateHandle = HANDLE_PREFIX + hashToken(normalizedPaperId);
            handlesByPaperId.putIfAbsent(normalizedPaperId, candidateHandle);
            paperIdsByHandle.putIfAbsent(candidateHandle, normalizedPaperId);
            if (candidateHandle.equals(paperHandle)) {
                return Optional.of(normalizedPaperId);
            }
        }
        return Optional.empty();
    }

    private String userId(Long userId) {
        return userId == null ? "" : String.valueOf(userId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
