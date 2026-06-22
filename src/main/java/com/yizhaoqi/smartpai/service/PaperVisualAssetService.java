package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperFigure;
import com.yizhaoqi.smartpai.model.PaperTable;
import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.repository.PaperVisualAssetRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PaperVisualAssetService {

    private static final Logger logger = LoggerFactory.getLogger(PaperVisualAssetService.class);
    private static final String BUCKET = "uploads";
    private static final String CONTENT_TYPE_PNG = "image/png";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private PaperVisualAssetRepository paperVisualAssetRepository;

    @Autowired
    private PaperTableService paperTableService;

    @Autowired
    private PaperFigureService paperFigureService;

    @Value("${paper.visual.page-dpi:144}")
    private int pageDpi = 144;

    @Transactional
    public List<PaperVisualAsset> replaceVisualAssets(String paperId,
                                                      byte[] pdfBytes,
                                                      ParsedPaper parsedPaper,
                                                      List<PaperTable> tables,
                                                      List<PaperFigure> figures,
                                                      String userId,
                                                      String orgTag,
                                                      boolean isPublic) {
        deleteVisualAssets(paperId);
        if (pdfBytes == null || pdfBytes.length == 0) {
            return List.of();
        }

        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            Map<Integer, ImageBytes> pageImages = new HashMap<>();
            int pageCount = resolvePageCount(parsedPaper, document);

            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                ImageBytes pageImage = renderPagePng(renderer, pageNumber);
                pageImages.put(pageNumber, pageImage);
                saveVisualAsset(
                        paperId,
                        PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                        pageNumber,
                        null,
                        null,
                        null,
                        "paper-assets/%s/pages/page-%d.png".formatted(paperId, pageNumber),
                        pageImage,
                        userId,
                        orgTag,
                        isPublic
                );
            }

            if (tables != null) {
                for (PaperTable table : tables) {
                    saveTableCrop(paperId, table, pageImages, userId, orgTag, isPublic);
                }
            }
            if (figures != null) {
                for (PaperFigure figure : figures) {
                    saveFigureCrop(paperId, figure, pageImages, userId, orgTag, isPublic);
                }
            }

            return paperVisualAssetRepository.findByPaperId(paperId);
        } catch (Exception e) {
            logger.warn("生成论文截图资产失败，将继续保留解析和检索结果: paperId={}, error={}", paperId, e.getMessage(), e);
            return paperVisualAssetRepository.findByPaperId(paperId);
        }
    }

    public ImageBytes renderPagePng(byte[] pdfBytes, int pageNumber) throws Exception {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return renderPagePng(new PDFRenderer(document), pageNumber);
        }
    }

    public ImageBytes cropPdfBoundingBox(byte[] pageImageBytes, BoundingBox box) throws Exception {
        if (pageImageBytes == null || pageImageBytes.length == 0 || box == null) {
            throw new IllegalArgumentException("page image and bounding box are required");
        }
        BufferedImage pageImage = ImageIO.read(new ByteArrayInputStream(pageImageBytes));
        if (pageImage == null) {
            throw new IllegalArgumentException("invalid page image bytes");
        }

        int x;
        int y;
        int right;
        int bottom;
        if ("top_left_1000".equalsIgnoreCase(box.coordinateSystem())) {
            x = clamp((int) Math.round(box.left() / 1000.0d * pageImage.getWidth()), 0, pageImage.getWidth() - 1);
            y = clamp((int) Math.round(box.top() / 1000.0d * pageImage.getHeight()), 0, pageImage.getHeight() - 1);
            right = clamp((int) Math.round(box.right() / 1000.0d * pageImage.getWidth()), x + 1, pageImage.getWidth());
            bottom = clamp((int) Math.round(box.bottom() / 1000.0d * pageImage.getHeight()), y + 1, pageImage.getHeight());
        } else {
            double scale = Math.max(1, pageDpi) / 72.0d;
            x = clamp((int) Math.round(box.left() * scale), 0, pageImage.getWidth() - 1);
            y = clamp((int) Math.round(pageImage.getHeight() - box.top() * scale), 0, pageImage.getHeight() - 1);
            right = clamp((int) Math.round(box.right() * scale), x + 1, pageImage.getWidth());
            bottom = clamp((int) Math.round(pageImage.getHeight() - box.bottom() * scale), y + 1, pageImage.getHeight());
        }
        int width = Math.max(1, right - x);
        int height = Math.max(1, bottom - y);

        BufferedImage crop = pageImage.getSubimage(x, y, width, height);
        return toPngBytes(crop);
    }

    public Optional<PaperVisualAsset> findPageScreenshot(String paperId, Integer pageNumber) {
        return paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndPageNumber(
                paperId,
                PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                pageNumber
        );
    }

    public Optional<PaperVisualAsset> findTableCrop(String paperId, String tableId) {
        return paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndTableId(
                paperId,
                PaperVisualAsset.TYPE_TABLE_CROP,
                tableId
        );
    }

    public Optional<PaperVisualAsset> findFigureCrop(String paperId, String figureId) {
        Optional<PaperVisualAsset> figureCrop = paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndFigureId(
                paperId,
                PaperVisualAsset.TYPE_FIGURE_CROP,
                figureId
        );
        if (figureCrop.isPresent()) {
            return figureCrop;
        }
        return paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndFigureId(
                paperId,
                PaperVisualAsset.TYPE_CHART_CROP,
                figureId
        );
    }

    public long countPageScreenshots(String paperId) {
        return paperVisualAssetRepository.countByPaperIdAndAssetType(paperId, PaperVisualAsset.TYPE_PAGE_SCREENSHOT);
    }

    public long countTableCrops(String paperId) {
        return paperVisualAssetRepository.countByPaperIdAndAssetType(paperId, PaperVisualAsset.TYPE_TABLE_CROP);
    }

    public long countFigureCrops(String paperId) {
        return paperVisualAssetRepository.countByPaperIdAndAssetType(paperId, PaperVisualAsset.TYPE_FIGURE_CROP)
                + paperVisualAssetRepository.countByPaperIdAndAssetType(paperId, PaperVisualAsset.TYPE_CHART_CROP);
    }

    public String generateDownloadUrl(PaperVisualAsset asset) {
        if (asset == null || asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            return null;
        }
        try {
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(BUCKET)
                            .object(asset.getObjectKey())
                            .expiry(3600)
                            .build()
            );
            return uploadService.transToPublicUrl(presignedUrl);
        } catch (Exception e) {
            throw new RuntimeException("生成截图资产下载链接失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteVisualAssets(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return;
        }
        for (PaperVisualAsset asset : paperVisualAssetRepository.findByPaperId(paperId)) {
            removeObjectQuietly(asset.getObjectKey());
        }
        paperVisualAssetRepository.deleteByPaperId(paperId);
    }

    private int resolvePageCount(ParsedPaper parsedPaper, PDDocument document) {
        if (parsedPaper != null
                && parsedPaper.metadata() != null
                && parsedPaper.metadata().pageCount() != null
                && parsedPaper.metadata().pageCount() > 0) {
            return Math.min(parsedPaper.metadata().pageCount(), document.getNumberOfPages());
        }
        return document.getNumberOfPages();
    }

    private ImageBytes renderPagePng(PDFRenderer renderer, int pageNumber) throws Exception {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be 1-based");
        }
        BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, pageDpi, ImageType.RGB);
        return toPngBytes(image);
    }

    private void saveTableCrop(String paperId,
                               PaperTable table,
                               Map<Integer, ImageBytes> pageImages,
                               String userId,
                               String orgTag,
                               boolean isPublic) {
        if (table == null || table.getTableId() == null || table.getPageNumber() == null || table.getBboxJson() == null) {
            return;
        }
        ImageBytes pageImage = pageImages.get(table.getPageNumber());
        if (pageImage == null) {
            return;
        }
        try {
            BoundingBox box = objectMapper.readValue(table.getBboxJson(), BoundingBox.class);
            ImageBytes crop = cropPdfBoundingBox(pageImage.bytes(), box);
            String objectKey = "paper-assets/%s/tables/%s.png".formatted(paperId, table.getTableId());
            saveVisualAsset(
                    paperId,
                    PaperVisualAsset.TYPE_TABLE_CROP,
                    table.getPageNumber(),
                    table.getTableId(),
                    null,
                    table.getBboxJson(),
                    objectKey,
                    crop,
                    userId,
                    orgTag,
                    isPublic
            );
            paperTableService.updateTableScreenshot(paperId, table.getTableId(), objectKey);
        } catch (Exception e) {
            logger.warn("生成表格裁剪图失败: paperId={}, tableId={}, error={}",
                    paperId, table.getTableId(), e.getMessage());
        }
    }

    private void saveFigureCrop(String paperId,
                                PaperFigure figure,
                                Map<Integer, ImageBytes> pageImages,
                                String userId,
                                String orgTag,
                                boolean isPublic) {
        if (figure == null || figure.getFigureId() == null || figure.getPageNumber() == null || figure.getBboxJson() == null) {
            return;
        }
        ImageBytes pageImage = pageImages.get(figure.getPageNumber());
        if (pageImage == null) {
            return;
        }
        try {
            BoundingBox box = objectMapper.readValue(figure.getBboxJson(), BoundingBox.class);
            ImageBytes crop = cropPdfBoundingBox(pageImage.bytes(), box);
            boolean chart = figure.getDetectionSource() != null && figure.getDetectionSource().toUpperCase().contains("CHART");
            String folder = chart ? "charts" : "figures";
            String objectKey = "paper-assets/%s/%s/%s.png".formatted(paperId, folder, figure.getFigureId());
            saveVisualAsset(
                    paperId,
                    chart ? PaperVisualAsset.TYPE_CHART_CROP : PaperVisualAsset.TYPE_FIGURE_CROP,
                    figure.getPageNumber(),
                    null,
                    figure.getFigureId(),
                    figure.getBboxJson(),
                    objectKey,
                    crop,
                    userId,
                    orgTag,
                    isPublic
            );
            paperFigureService.updateFigureScreenshot(paperId, figure.getFigureId(), objectKey);
        } catch (Exception e) {
            logger.warn("生成图像裁剪图失败: paperId={}, figureId={}, error={}",
                    paperId, figure.getFigureId(), e.getMessage());
        }
    }

    private PaperVisualAsset saveVisualAsset(String paperId,
                                             String assetType,
                                             Integer pageNumber,
                                             String tableId,
                                             String figureId,
                                             String bboxJson,
                                             String objectKey,
                                             ImageBytes imageBytes,
                                             String userId,
                                             String orgTag,
                                             boolean isPublic) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .stream(new ByteArrayInputStream(imageBytes.bytes()), imageBytes.bytes().length, -1)
                .contentType(CONTENT_TYPE_PNG)
                .build());

        PaperVisualAsset asset = new PaperVisualAsset();
        asset.setPaperId(paperId);
        asset.setAssetType(assetType);
        asset.setPageNumber(pageNumber);
        asset.setTableId(tableId);
        asset.setFigureId(figureId);
        asset.setBboxJson(bboxJson);
        asset.setObjectKey(objectKey);
        asset.setContentType(CONTENT_TYPE_PNG);
        asset.setWidthPx(imageBytes.widthPx());
        asset.setHeightPx(imageBytes.heightPx());
        asset.setSha256(sha256(imageBytes.bytes()));
        asset.setUserId(userId);
        asset.setOrgTag(orgTag);
        asset.setPublic(isPublic);
        return paperVisualAssetRepository.save(asset);
    }

    private ImageBytes toPngBytes(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return new ImageBytes(outputStream.toByteArray(), image.getWidth(), image.getHeight());
        }
    }

    private int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void removeObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // MinIO cleanup is best-effort; database metadata is still removed below.
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException("计算视觉资产 SHA-256 失败", e);
        }
    }

    public record ImageBytes(byte[] bytes, int widthPx, int heightPx) {
    }
}
