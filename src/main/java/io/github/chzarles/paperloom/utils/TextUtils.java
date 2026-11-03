package io.github.chzarles.paperloom.utils;

/**
 * 研究 harness 报文里反复出现的取值规则：值转字符串后去空白，取第一个非空项。
 */
public final class TextUtils {

    private TextUtils() {
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
