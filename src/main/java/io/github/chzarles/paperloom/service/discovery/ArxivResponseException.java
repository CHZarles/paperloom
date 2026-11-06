package io.github.chzarles.paperloom.service.discovery;

/**
 * arXiv 返回了无法使用的响应。该异常必须冒泡到接口层，不能降级成空结果。
 */
public class ArxivResponseException extends RuntimeException {

    public ArxivResponseException(String message) {
        super(message);
    }

    public ArxivResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
