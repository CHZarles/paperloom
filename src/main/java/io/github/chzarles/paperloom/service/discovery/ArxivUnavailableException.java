package io.github.chzarles.paperloom.service.discovery;

/**
 * arXiv 当前不可用。与 {@link ArxivResponseException} 区分：这里表达的是上游可达性或限流问题。
 */
public class ArxivUnavailableException extends RuntimeException {

    public ArxivUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
