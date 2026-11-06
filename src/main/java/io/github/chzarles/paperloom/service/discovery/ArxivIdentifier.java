package io.github.chzarles.paperloom.service.discovery;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * arXiv 标识规范化。
 *
 * <p>接受 `2409.04701`、`2409.04701v2`、`https://arxiv.org/abs/2409.04701v2` 和旧式
 * `hep-th/9901001`。返回值去掉版本号和 URL 前缀，作为检索与下载使用的稳定标识。
 */
public final class ArxivIdentifier {

    private static final Pattern NEW_STYLE = Pattern.compile("\\d{4}\\.\\d{4,5}");
    private static final Pattern OLD_STYLE = Pattern.compile("[a-z][a-z-]*(\\.[A-Za-z]{2})?/\\d{7}");
    private static final Pattern VERSION_SUFFIX = Pattern.compile("v\\d+$");

    private ArxivIdentifier() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        int absIndex = value.indexOf("/abs/");
        if (absIndex >= 0) {
            value = value.substring(absIndex + "/abs/".length());
        } else if (value.startsWith("arXiv:")) {
            value = value.substring("arXiv:".length());
        }
        int slash = value.indexOf('?');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        // 旧式标识自身包含一个斜杠，只去掉末尾的 pdf 后缀
        if (value.endsWith("/pdf")) {
            value = value.substring(0, value.length() - "/pdf".length());
        }
        value = VERSION_SUFFIX.matcher(value).replaceFirst("");

        String candidate = value.toLowerCase(Locale.ROOT);
        if (NEW_STYLE.matcher(candidate).matches() || OLD_STYLE.matcher(value).matches()) {
            return Optional.of(value);
        }
        return Optional.empty();
    }

    public static String requireValid(String raw) {
        return normalize(raw).orElseThrow(() -> new IllegalArgumentException("无法识别的 arXiv 标识: " + raw));
    }
}
