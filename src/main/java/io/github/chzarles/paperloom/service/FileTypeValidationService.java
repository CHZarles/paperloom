package io.github.chzarles.paperloom.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 论文 PDF 类型验证服务。
 */
@Service
public class FileTypeValidationService {

    private static final Logger logger = LoggerFactory.getLogger(FileTypeValidationService.class);

    private static final Set<String> SUPPORTED_DOCUMENT_EXTENSIONS = Set.of("pdf");

    /**
     * 验证上传内容是否为 PaperLoom 当前支持的 PDF 论文。
     *
     * @param fileName 上传 PDF 文件名
     * @return 验证结果
     */
    public FileTypeValidationResult validateFileType(String fileName) {
        logger.debug("开始验证文件类型: fileName={}", fileName);
        
        if (fileName == null || fileName.trim().isEmpty()) {
            logger.warn("文件名为空或null");
            return new FileTypeValidationResult(false, "论文 PDF 文件名不能为空", "unknown", null);
        }

        // 提取文件扩展名
        String extension = extractFileExtension(fileName);
        if (extension == null) {
            logger.warn("无法提取文件扩展名: fileName={}", fileName);
            return new FileTypeValidationResult(false, "论文 PDF 文件必须有扩展名", "unknown", null);
        }

        String fileType = getFileTypeDescription(extension);
        logger.debug("文件类型识别结果: fileName={}, extension={}, fileType={}", fileName, extension, fileType);

        if (SUPPORTED_DOCUMENT_EXTENSIONS.contains(extension)) {
            logger.info("论文 PDF 类型验证通过: fileName={}, extension={}, fileType={}", fileName, extension, fileType);
            return new FileTypeValidationResult(true, "支持的论文 PDF 格式", fileType, extension);
        }

        String message = String.format("不支持的论文格式：%s。PaperLoom 当前仅支持 PDF 论文。", fileType);
        logger.warn("文件类型验证失败: fileName={}, extension={}, fileType={}, reason=unknown_type", 
                  fileName, extension, fileType);
        return new FileTypeValidationResult(false, message, fileType, extension);
    }

    /**
     * 提取 PDF 文件扩展名。
     *
     * @param fileName 上传 PDF 文件名
     * @return 小写的文件扩展名，如果没有扩展名则返回null
     */
    private String extractFileExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 根据扩展名获取 PaperLoom 上传类型描述。
     *
     * @param extension 文件扩展名
     * @return 文件类型描述
     */
    private String getFileTypeDescription(String extension) {
        if (extension == null) {
            return "unknown";
        }

        if ("pdf".equalsIgnoreCase(extension)) {
            return "PDF论文";
        }
        return "不支持的上传格式";
    }

    /**
     * 获取支持的论文格式列表（用于前端显示）。
     *
     * @return 支持的文件类型描述列表
     */
    public Set<String> getSupportedFileTypes() {
        Set<String> supportedTypes = new HashSet<>();
        for (String extension : SUPPORTED_DOCUMENT_EXTENSIONS) {
            supportedTypes.add(getFileTypeDescription(extension));
        }
        return supportedTypes;
    }

    /**
     * 获取支持的论文扩展名列表。
     *
     * @return 支持的文件扩展名集合
     */
    public Set<String> getSupportedExtensions() {
        return new HashSet<>(SUPPORTED_DOCUMENT_EXTENSIONS);
    }

    /**
     * 文件类型验证结果类
     */
    public static class FileTypeValidationResult {
        private final boolean valid;
        private final String message;
        private final String fileType;
        private final String extension;

        public FileTypeValidationResult(boolean valid, String message, String fileType, String extension) {
            this.valid = valid;
            this.message = message;
            this.fileType = fileType;
            this.extension = extension;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getFileType() {
            return fileType;
        }

        public String getExtension() {
            return extension;
        }

        @Override
        public String toString() {
            return String.format("FileTypeValidationResult{valid=%s, message='%s', fileType='%s', extension='%s'}", 
                               valid, message, fileType, extension);
        }
    }
} 
