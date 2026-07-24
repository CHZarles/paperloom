package io.github.chzarles.paperloom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chzarles.paperloom.model.PaperSourceQuote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SourceQuoteVisualRegionContract {

    static final String UNIT = "mineru_1000";
    static final String COORDINATE_SYSTEM = "top_left_1000";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SourceQuoteVisualRegionContract() {
    }

    static List<Map<String, Object>> visualRegions(PaperSourceQuote quote) {
        Map<String, Object> sourceSpan = parseJsonObject(quote == null ? null : quote.getSourceSpanJson());
        if (sourceSpan.isEmpty()) {
            return List.of();
        }

        Map<String, Object> box = singleBbox(sourceSpan.get("bbox"));
        if (box.isEmpty() || !isMineruTopLeft1000Box(box) || !bboxPageMatchesQuote(box, quote)) {
            return List.of();
        }

        Object pageNumber = integerObject(box.get("pageNumber"), quote == null ? null : quote.getPageNumber());
        if (pageNumber == null) {
            return List.of();
        }

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("pageNumber", pageNumber);
        region.put("left", box.get("left"));
        region.put("top", box.get("top"));
        region.put("right", box.get("right"));
        region.put("bottom", box.get("bottom"));
        region.put("unit", UNIT);
        region.put("coordinateSystem", COORDINATE_SYSTEM);
        region.put("targetKind", sourceSpan.containsKey("targetReadingElementId") ? "ELEMENT" : "LOCATION");
        region.put("confidence", confidence(sourceSpan, quote));
        return List.of(region);
    }

    private static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(
                    json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, Object> singleBbox(Object rawBbox) {
        Map<String, Object> directBox = objectMap(rawBbox);
        if (!directBox.isEmpty()) {
            return directBox;
        }
        if (!(rawBbox instanceof List<?> boxes) || boxes.size() != 1) {
            return Map.of();
        }
        return objectMap(boxes.get(0));
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static boolean isMineruTopLeft1000Box(Map<String, Object> box) {
        return isFiniteNumber(box.get("left"))
                && isFiniteNumber(box.get("top"))
                && isFiniteNumber(box.get("right"))
                && isFiniteNumber(box.get("bottom"))
                && doubleValue(box.get("right")) > doubleValue(box.get("left"))
                && doubleValue(box.get("bottom")) > doubleValue(box.get("top"))
                && COORDINATE_SYSTEM.equals(stringValue(box.get("coordinateSystem")))
                && UNIT.equals(stringValue(box.get("unit")));
    }

    private static boolean bboxPageMatchesQuote(Map<String, Object> box, PaperSourceQuote quote) {
        if (quote == null || quote.getPageNumber() == null) {
            return true;
        }
        Object rawPage = box.get("pageNumber");
        if (rawPage == null) {
            return true;
        }
        Integer boxPageNumber = rawPage instanceof Number number
                ? number.intValue()
                : integerValue(String.valueOf(rawPage));
        return boxPageNumber != null && boxPageNumber.equals(quote.getPageNumber());
    }

    private static String confidence(Map<String, Object> sourceSpan, PaperSourceQuote quote) {
        String locationType = firstNonBlank(
                stringValue(sourceSpan.get("locationType")),
                quote == null ? "" : stringValue(quote.getLocationType())
        ).toUpperCase();
        return "TABLE".equals(locationType)
                || "FIGURE".equals(locationType)
                || sourceSpan.containsKey("targetReadingElementId")
                ? "EXACT"
                : "APPROXIMATE";
    }

    private static Object integerObject(Object value, Integer fallback) {
        Integer integer = value instanceof Number number ? number.intValue() : integerValue(stringValue(value));
        return integer == null ? fallback : integer;
    }

    private static Integer integerValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isFiniteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
