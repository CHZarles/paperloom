package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperParserArtifact;
import com.yizhaoqi.smartpai.model.PaperReadingElement;
import com.yizhaoqi.smartpai.model.PaperVisualAsset;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperArtifactPayload;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFigure;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperFormula;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperTable;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.repository.PaperReadingElementRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private PaperReadingElementRepository paperReadingElementRepository;

    @Value("${paper.visual.page-dpi:144}")
    private int pageDpi = 144;

    @Transactional
    public List<PaperVisualAsset> replaceVisualAssets(String paperId,
                                                      String modelVersion,
                                                      byte[] pdfBytes,
                                                      ParsedPaper parsedPaper,
                                                      String userId,
                                                      String orgTag,
                                                      boolean isPublic) {
        deleteVisualAssets(paperId);

        ReadingElementIndex readingElementIndex = readingElementIndex(paperId, modelVersion);
        saveParserImages(paperId, modelVersion, parsedPaper, readingElementIndex, userId, orgTag, isPublic);

        if (pdfBytes == null || pdfBytes.length == 0) {
            return paperVisualAssetRepository.findByPaperId(paperId);
        }

        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            Map<Integer, ImageBytes> pageImages = new HashMap<>();
            int pageCount = resolvePageCount(parsedPaper, document);

            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                try {
                    ImageBytes pageImage = renderPagePng(renderer, pageNumber);
                    pageImages.put(pageNumber, pageImage);
                    try {
                        saveVisualAsset(
                                paperId,
                                PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                                pageNumber,
                                null,
                                "paper-assets/%s/pages/page-%d.png".formatted(paperId, pageNumber),
                                pageImage,
                                modelVersion,
                                "page-" + pageNumber,
                                null,
                                null,
                                null,
                                userId,
                                orgTag,
                                isPublic
                        );
                    } catch (Exception e) {
                        logger.warn("保存页面截图失败: paperId={}, pageNumber={}, error={}",
                                paperId, pageNumber, e.getMessage());
                        saveVisualAssetGap(
                                paperId,
                                PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                                PaperVisualAsset.STATUS_STORAGE_FAILED,
                                pageNumber,
                                null,
                                modelVersion,
                                "page-" + pageNumber,
                                null,
                                null,
                                null,
                                e.getMessage(),
                                userId,
                                orgTag,
                                isPublic
                        );
                    }
                } catch (Exception e) {
                    logger.warn("渲染页面截图失败: paperId={}, pageNumber={}, error={}",
                            paperId, pageNumber, e.getMessage());
                    saveVisualAssetGap(
                            paperId,
                            PaperVisualAsset.TYPE_PAGE_SCREENSHOT,
                            PaperVisualAsset.STATUS_RENDER_FAILED,
                            pageNumber,
                            null,
                            modelVersion,
                            "page-" + pageNumber,
                            null,
                            null,
                            null,
                            e.getMessage(),
                            userId,
                            orgTag,
                            isPublic
                    );
                }
            }

            saveReadingElementCrops(paperId, modelVersion, readingElementIndex, pageImages, userId, orgTag, isPublic);

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

    public Optional<PaperVisualAsset> findTableCropByReadingElementId(String paperId, String readingElementId) {
        Optional<PaperVisualAsset> tableCrop = paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                paperId,
                PaperVisualAsset.TYPE_TABLE_CROP,
                readingElementId
        );
        if (tableCrop.isPresent()) {
            return tableCrop;
        }
        return paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                paperId,
                PaperVisualAsset.TYPE_PARSER_IMAGE,
                readingElementId
        );
    }

    public Optional<PaperVisualAsset> findFigureCropByReadingElementId(String paperId, String readingElementId) {
        Optional<PaperVisualAsset> figureCrop = paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                paperId,
                PaperVisualAsset.TYPE_FIGURE_CROP,
                readingElementId
        );
        if (figureCrop.isPresent()) {
            return figureCrop;
        }
        return paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                paperId,
                PaperVisualAsset.TYPE_CHART_CROP,
                readingElementId
        ).or(() -> paperVisualAssetRepository.findFirstByPaperIdAndAssetTypeAndReadingElementId(
                paperId,
                PaperVisualAsset.TYPE_PARSER_IMAGE,
                readingElementId
        ));
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

    private void saveParserImages(String paperId,
                                  String modelVersion,
                                  ParsedPaper parsedPaper,
                                  ReadingElementIndex readingElementIndex,
                                  String userId,
                                  String orgTag,
                                  boolean isPublic) {
        Map<String, byte[]> imageBytesByPath = parserImageBytes(parsedPaper);
        for (ParserImageSource source : parserImageSources(parsedPaper)) {
            PaperReadingElement readingElement = readingElementIndex.find(source);
            String sourceObjectId = firstNonBlank(
                    source.sourceObjectId(),
                    readingElement == null ? null : readingElement.getSourceObjectId(),
                    source.parserElementId()
            );
            byte[] bytes = findParserImageBytes(imageBytesByPath, source.parserImagePath());
            if (bytes == null || bytes.length == 0) {
                logger.warn("MinerU parser image missing from raw result zip: paperId={}, imgPath={}",
                        paperId, source.parserImagePath());
                saveVisualAssetGap(
                        paperId,
                        PaperVisualAsset.TYPE_PARSER_IMAGE,
                        PaperVisualAsset.STATUS_MISSING_IN_ARTIFACT,
                        source.pageNumber(),
                        firstNonBlank(source.bboxJson(), readingElement == null ? null : readingElement.getBboxJson()),
                        modelVersion,
                        sourceObjectId,
                        readingElement == null ? null : readingElement.getReadingElementId(),
                        source.parserElementId(),
                        source.parserImagePath(),
                        "Parser image referenced by content_list but absent from MinerU artifact",
                        userId,
                        orgTag,
                        isPublic
                );
                continue;
            }
            try {
                ImageBytes imageBytes = imageBytes(bytes);
                saveVisualAsset(
                        paperId,
                        PaperVisualAsset.TYPE_PARSER_IMAGE,
                        source.pageNumber(),
                        firstNonBlank(source.bboxJson(), readingElement == null ? null : readingElement.getBboxJson()),
                        parserImageObjectKey(paperId, source),
                        imageBytes,
                        contentTypeFor(source.parserImagePath()),
                        modelVersion,
                        sourceObjectId,
                        readingElement == null ? null : readingElement.getReadingElementId(),
                        source.parserElementId(),
                        source.parserImagePath(),
                        userId,
                        orgTag,
                        isPublic
                );
            } catch (Exception e) {
                logger.warn("保存 MinerU parser image 失败: paperId={}, imgPath={}, error={}",
                        paperId, source.parserImagePath(), e.getMessage());
                saveVisualAssetGap(
                        paperId,
                        PaperVisualAsset.TYPE_PARSER_IMAGE,
                        PaperVisualAsset.STATUS_STORAGE_FAILED,
                        source.pageNumber(),
                        firstNonBlank(source.bboxJson(), readingElement == null ? null : readingElement.getBboxJson()),
                        modelVersion,
                        sourceObjectId,
                        readingElement == null ? null : readingElement.getReadingElementId(),
                        source.parserElementId(),
                        source.parserImagePath(),
                        e.getMessage(),
                        userId,
                        orgTag,
                        isPublic
                );
            }
        }
    }

    private Map<String, byte[]> parserImageBytes(ParsedPaper parsedPaper) {
        if (parsedPaper == null || parsedPaper.artifacts() == null) {
            return Map.of();
        }
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (ParsedPaperArtifactPayload artifact : parsedPaper.artifacts()) {
            if (artifact == null || artifact.bytes() == null || artifact.bytes().length == 0
                    || !PaperParserArtifact.TYPE_MINERU_RESULT_ZIP.equals(artifact.artifactType())) {
                continue;
            }
            try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(artifact.bytes()))) {
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    if (entry.isDirectory() || !isImagePath(entry.getName())) {
                        continue;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    zipInputStream.transferTo(bytes);
                    images.put(normalizeZipPath(entry.getName()), bytes.toByteArray());
                }
            } catch (Exception e) {
                logger.warn("读取 MinerU raw result zip 中的图片失败: artifact={}, error={}",
                        artifact.filename(), e.getMessage());
            }
        }
        return images;
    }

    private byte[] findParserImageBytes(Map<String, byte[]> imageBytesByPath, String parserImagePath) {
        String normalized = normalizeZipPath(parserImagePath);
        if (normalized.isBlank()) {
            return null;
        }
        byte[] exact = imageBytesByPath.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, byte[]> entry : imageBytesByPath.entrySet()) {
            String imagePath = entry.getKey();
            if (imagePath.endsWith("/" + normalized)
                    || basename(imagePath).equals(basename(normalized))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<ParserImageSource> parserImageSources(ParsedPaper parsedPaper) {
        if (parsedPaper == null) {
            return List.of();
        }
        Map<String, ParserImageSource> sources = new LinkedHashMap<>();
        if (parsedPaper.elements() != null) {
            for (ParsedPaperElement element : parsedPaper.elements()) {
                if (element == null) {
                    continue;
                }
                putParserImageSource(sources, new ParserImageSource(
                        element.elementId(),
                        element.elementId(),
                        elementType(element.elementType()),
                        element.pageNumber(),
                        element.readingOrder(),
                        bboxJson(element.boundingBox()),
                        parserImagePath(element.rawAttributes())
                ));
            }
        }
        if (parsedPaper.tables() != null) {
            for (ParsedPaperTable table : parsedPaper.tables()) {
                if (table == null) {
                    continue;
                }
                putParserImageSource(sources, new ParserImageSource(
                        table.elementId(),
                        table.tableId(),
                        "TABLE",
                        table.pageNumber(),
                        table.readingOrder(),
                        bboxJson(table.boundingBox()),
                        parserImagePath(table.rawAttributes())
                ));
            }
        }
        if (parsedPaper.figures() != null) {
            for (ParsedPaperFigure figure : parsedPaper.figures()) {
                if (figure == null) {
                    continue;
                }
                String type = figure.detectionSource() != null && figure.detectionSource().toUpperCase(Locale.ROOT).contains("CHART")
                        ? "CHART"
                        : "FIGURE";
                putParserImageSource(sources, new ParserImageSource(
                        figure.elementId(),
                        figure.figureId(),
                        type,
                        figure.pageNumber(),
                        figure.readingOrder(),
                        bboxJson(figure.boundingBox()),
                        parserImagePath(figure.rawAttributes())
                ));
            }
        }
        if (parsedPaper.formulas() != null) {
            for (ParsedPaperFormula formula : parsedPaper.formulas()) {
                if (formula == null) {
                    continue;
                }
                putParserImageSource(sources, new ParserImageSource(
                        formula.elementId(),
                        formula.formulaId(),
                        "FORMULA",
                        formula.pageNumber(),
                        formula.readingOrder(),
                        bboxJson(formula.boundingBox()),
                        parserImagePath(formula.rawAttributes())
                ));
            }
        }
        return new ArrayList<>(sources.values());
    }

    private void putParserImageSource(Map<String, ParserImageSource> sources, ParserImageSource source) {
        if (source == null || source.parserImagePath() == null || source.parserImagePath().isBlank()) {
            return;
        }
        sources.put(parserImageSourceKey(source), source);
    }

    private String parserImageSourceKey(ParserImageSource source) {
        if (source.parserElementId() != null && !source.parserElementId().isBlank()) {
            return "element:" + source.parserElementId();
        }
        return "image:%s:%s:%s".formatted(source.parserImagePath(), source.pageNumber(), source.readingOrder());
    }

    private ReadingElementIndex readingElementIndex(String paperId, String modelVersion) {
        if (paperId == null || paperId.isBlank() || modelVersion == null || modelVersion.isBlank()
                || paperReadingElementRepository == null) {
            return ReadingElementIndex.empty();
        }
        return ReadingElementIndex.of(paperReadingElementRepository
                .findByPaperIdAndModelVersionOrderByPageNumberAscReadingOrderAscIdAsc(paperId, modelVersion));
    }

    private ImageBytes imageBytes(byte[] bytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            return new ImageBytes(bytes, 0, 0);
        }
        return new ImageBytes(bytes, image.getWidth(), image.getHeight());
    }

    private String parserImageObjectKey(String paperId, ParserImageSource source) {
        String filename = safeKey(firstNonBlank(source.parserElementId(), "image") + "-" + basename(source.parserImagePath()));
        return "paper-assets/%s/parser-images/%s".formatted(paperId, filename);
    }

    private ImageBytes renderPagePng(PDFRenderer renderer, int pageNumber) throws Exception {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be 1-based");
        }
        BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, pageDpi, ImageType.RGB);
        return toPngBytes(image);
    }

    private void saveReadingElementCrops(String paperId,
                                         String modelVersion,
                                         ReadingElementIndex readingElementIndex,
                                         Map<Integer, ImageBytes> pageImages,
                                         String userId,
                                         String orgTag,
                                         boolean isPublic) {
        for (PaperReadingElement element : readingElementIndex.elements()) {
            if (element == null || element.getPageNumber() == null || element.getBboxJson() == null
                    || element.getBboxJson().isBlank()) {
                continue;
            }
            String assetType = cropAssetType(element.getElementType());
            if (assetType == null) {
                continue;
            }
            ImageBytes pageImage = pageImages.get(element.getPageNumber());
            if (pageImage == null) {
                saveVisualAssetGap(
                        paperId,
                        assetType,
                        PaperVisualAsset.STATUS_RENDER_FAILED,
                        element.getPageNumber(),
                        element.getBboxJson(),
                        modelVersion,
                        element.getSourceObjectId(),
                        element.getReadingElementId(),
                        element.getParserElementId(),
                        element.getParserImagePath(),
                        "PAGE_IMAGE_NOT_RENDERED",
                        userId,
                        orgTag,
                        isPublic
                );
                continue;
            }
            try {
                BoundingBox box = objectMapper.readValue(element.getBboxJson(), BoundingBox.class);
                ImageBytes crop = cropPdfBoundingBox(pageImage.bytes(), box);
                String folder = switch (assetType) {
                    case PaperVisualAsset.TYPE_TABLE_CROP -> "tables";
                    case PaperVisualAsset.TYPE_CHART_CROP -> "charts";
                    default -> "figures";
                };
                String objectKey = "paper-assets/%s/%s/%s.png".formatted(
                        paperId,
                        folder,
                        safeKey(element.getReadingElementId())
                );
                saveVisualAsset(
                        paperId,
                        assetType,
                        element.getPageNumber(),
                        element.getBboxJson(),
                        objectKey,
                        crop,
                        modelVersion,
                        element.getSourceObjectId(),
                        element.getReadingElementId(),
                        element.getParserElementId(),
                        element.getParserImagePath(),
                        userId,
                        orgTag,
                        isPublic
                );
            } catch (Exception e) {
                logger.warn("生成阅读元素裁剪图失败: paperId={}, readingElementId={}, error={}",
                        paperId, element.getReadingElementId(), e.getMessage());
                saveVisualAssetGap(
                        paperId,
                        assetType,
                        PaperVisualAsset.STATUS_RENDER_FAILED,
                        element.getPageNumber(),
                        element.getBboxJson(),
                        modelVersion,
                        element.getSourceObjectId(),
                        element.getReadingElementId(),
                        element.getParserElementId(),
                        element.getParserImagePath(),
                        e.getMessage(),
                        userId,
                        orgTag,
                        isPublic
                );
            }
        }
    }

    private String cropAssetType(String elementType) {
        return switch (elementType) {
            case "TABLE" -> PaperVisualAsset.TYPE_TABLE_CROP;
            case "CHART" -> PaperVisualAsset.TYPE_CHART_CROP;
            case "IMAGE" -> PaperVisualAsset.TYPE_FIGURE_CROP;
            default -> null;
        };
    }

    private PaperVisualAsset saveVisualAsset(String paperId,
                                             String assetType,
                                             Integer pageNumber,
                                             String bboxJson,
                                             String objectKey,
                                             ImageBytes imageBytes,
                                             String modelVersion,
                                             String sourceObjectId,
                                             String readingElementId,
                                             String parserElementId,
                                             String parserImagePath,
                                             String userId,
                                             String orgTag,
                                             boolean isPublic) throws Exception {
        return saveVisualAsset(
                paperId,
                assetType,
                pageNumber,
                bboxJson,
                objectKey,
                imageBytes,
                CONTENT_TYPE_PNG,
                modelVersion,
                sourceObjectId,
                readingElementId,
                parserElementId,
                parserImagePath,
                userId,
                orgTag,
                isPublic
        );
    }

    private PaperVisualAsset saveVisualAsset(String paperId,
                                             String assetType,
                                             Integer pageNumber,
                                             String bboxJson,
                                             String objectKey,
                                             ImageBytes imageBytes,
                                             String contentType,
                                             String modelVersion,
                                             String sourceObjectId,
                                             String readingElementId,
                                             String parserElementId,
                                             String parserImagePath,
                                             String userId,
                                             String orgTag,
                                             boolean isPublic) throws Exception {
        String effectiveContentType = contentType == null || contentType.isBlank() ? CONTENT_TYPE_PNG : contentType;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(BUCKET)
                .object(objectKey)
                .stream(new ByteArrayInputStream(imageBytes.bytes()), imageBytes.bytes().length, -1)
                .contentType(effectiveContentType)
                .build());

        PaperVisualAsset asset = new PaperVisualAsset();
        asset.setPaperId(paperId);
        asset.setAssetType(assetType);
        asset.setAssetStatus(PaperVisualAsset.STATUS_AVAILABLE);
        asset.setModelVersion(modelVersion);
        asset.setPageNumber(pageNumber);
        asset.setSourceObjectId(sourceObjectId);
        asset.setReadingElementId(readingElementId);
        asset.setParserElementId(parserElementId);
        asset.setParserImagePath(parserImagePath);
        asset.setBboxJson(bboxJson);
        asset.setObjectKey(objectKey);
        asset.setContentType(effectiveContentType);
        asset.setWidthPx(imageBytes.widthPx());
        asset.setHeightPx(imageBytes.heightPx());
        asset.setSha256(sha256(imageBytes.bytes()));
        asset.setUserId(userId);
        asset.setOrgTag(orgTag);
        asset.setPublic(isPublic);
        return paperVisualAssetRepository.save(asset);
    }

    private PaperVisualAsset saveVisualAssetGap(String paperId,
                                                String assetType,
                                                String assetStatus,
                                                Integer pageNumber,
                                                String bboxJson,
                                                String modelVersion,
                                                String sourceObjectId,
                                                String readingElementId,
                                                String parserElementId,
                                                String parserImagePath,
                                                String failureReason,
                                                String userId,
                                                String orgTag,
                                                boolean isPublic) {
        PaperVisualAsset asset = new PaperVisualAsset();
        asset.setPaperId(paperId);
        asset.setAssetType(assetType);
        asset.setAssetStatus(assetStatus);
        asset.setModelVersion(modelVersion);
        asset.setPageNumber(pageNumber);
        asset.setSourceObjectId(sourceObjectId);
        asset.setReadingElementId(readingElementId);
        asset.setParserElementId(parserElementId);
        asset.setParserImagePath(parserImagePath);
        asset.setBboxJson(bboxJson);
        asset.setObjectKey(null);
        asset.setContentType(null);
        asset.setWidthPx(null);
        asset.setHeightPx(null);
        asset.setSha256(null);
        asset.setFailureReason(failureReason);
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

    private String parserImagePath(Map<String, Object> rawAttributes) {
        if (rawAttributes == null) {
            return null;
        }
        return firstNonBlank(
                rawText(rawAttributes, "img_path"),
                rawText(rawAttributes, "image_path")
        );
    }

    private String rawText(Map<String, Object> rawAttributes, String field) {
        Object value = rawAttributes.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private String bboxJson(Object bbox) {
        if (bbox == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(bbox);
        } catch (Exception e) {
            return null;
        }
    }

    private String elementType(ParsedPaperElementType elementType) {
        if (elementType == null) {
            return "UNKNOWN";
        }
        return switch (elementType) {
            case TABLE -> "TABLE";
            case FIGURE, IMAGE -> "FIGURE";
            case CHART -> "CHART";
            case FORMULA -> "FORMULA";
            default -> elementType.name();
        };
    }

    private boolean isImagePath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".png")
                || normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".gif")
                || normalized.endsWith(".webp")
                || normalized.endsWith(".svg");
    }

    private String contentTypeFor(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (normalized.endsWith(".gif")) {
            return "image/gif";
        }
        if (normalized.endsWith(".webp")) {
            return "image/webp";
        }
        if (normalized.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private String normalizeZipPath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String basename(String path) {
        String normalized = normalizeZipPath(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private String safeKey(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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

    private record ParserImageSource(String parserElementId,
                                     String sourceObjectId,
                                     String elementType,
                                     Integer pageNumber,
                                     Integer readingOrder,
                                     String bboxJson,
                                     String parserImagePath) {
    }

    private record ReadingElementIndex(List<PaperReadingElement> elements,
                                       Map<String, PaperReadingElement> byParserElementId,
                                       Map<String, PaperReadingElement> bySourceObjectId,
                                       Map<String, PaperReadingElement> byParserImagePath) {
        static ReadingElementIndex empty() {
            return new ReadingElementIndex(List.of(), Map.of(), Map.of(), Map.of());
        }

        static ReadingElementIndex of(List<PaperReadingElement> elements) {
            if (elements == null || elements.isEmpty()) {
                return empty();
            }
            Map<String, PaperReadingElement> byParserElementId = new LinkedHashMap<>();
            Map<String, PaperReadingElement> bySourceObjectId = new LinkedHashMap<>();
            Map<String, PaperReadingElement> byParserImagePath = new LinkedHashMap<>();
            for (PaperReadingElement element : elements) {
                if (element == null) {
                    continue;
                }
                putIfPresent(byParserElementId, element.getParserElementId(), element);
                putIfPresent(bySourceObjectId, element.getSourceObjectId(), element);
                putIfPresent(byParserImagePath, element.getParserImagePath(), element);
            }
            return new ReadingElementIndex(elements, byParserElementId, bySourceObjectId, byParserImagePath);
        }

        PaperReadingElement bySourceObjectId(String sourceObjectId) {
            if (sourceObjectId == null || sourceObjectId.isBlank()) {
                return null;
            }
            return bySourceObjectId.get(sourceObjectId);
        }

        PaperReadingElement find(ParserImageSource source) {
            if (source == null) {
                return null;
            }
            PaperReadingElement byParserElement = byParserElementId.get(source.parserElementId());
            if (byParserElement != null) {
                return byParserElement;
            }
            PaperReadingElement bySource = bySourceObjectId.get(source.sourceObjectId());
            if (bySource != null) {
                return bySource;
            }
            return byParserImagePath.get(source.parserImagePath());
        }

        private static void putIfPresent(Map<String, PaperReadingElement> map,
                                         String key,
                                         PaperReadingElement element) {
            if (key != null && !key.isBlank()) {
                map.putIfAbsent(key, element);
            }
        }
    }
}
