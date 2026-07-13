package io.github.chzarles.paperloom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class OutboundWebClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(OutboundWebClientFactory.class);

    public WebClient.Builder builder(String baseUrl) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        resolveProxyForTarget(toUri(baseUrl), System.getenv()).ifPresent(proxy -> {
            HttpClient httpClient = HttpClient.create()
                    .proxy(spec -> {
                        ProxyProvider.Builder proxyBuilder = spec
                                .type(ProxyProvider.Proxy.HTTP)
                                .host(proxy.host())
                                .port(proxy.port());
                        if (proxy.username() != null && !proxy.username().isBlank()) {
                            proxyBuilder.username(proxy.username());
                            if (proxy.password() != null) {
                                proxyBuilder.password(s -> proxy.password());
                            }
                        }
                    });
            logger.info("Outbound WebClient proxy enabled for {} via {}:{}", hostOf(baseUrl), proxy.host(), proxy.port());
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        });
        return builder;
    }

    static Optional<ProxySettings> resolveProxyForTarget(URI target, Map<String, String> env) {
        if (target == null || target.getHost() == null || env == null || shouldBypassProxy(target.getHost(), env)) {
            return Optional.empty();
        }
        String rawProxy = firstNonBlank(env, proxyKeysFor(target.getScheme()));
        if (rawProxy == null) {
            return Optional.empty();
        }
        return parseProxy(rawProxy);
    }

    private static URI toUri(String baseUrl) {
        try {
            return URI.create(baseUrl);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String hostOf(String baseUrl) {
        URI uri = toUri(baseUrl);
        return uri == null || uri.getHost() == null ? baseUrl : uri.getHost();
    }

    private static String[] proxyKeysFor(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return new String[]{"HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"};
        }
        return new String[]{"HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"};
    }

    private static Optional<ProxySettings> parseProxy(String rawProxy) {
        String value = rawProxy == null ? "" : rawProxy.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "http://" + value);
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (uri.getHost() == null) {
            return Optional.empty();
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        String username = null;
        String password = null;
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            String[] parts = uri.getUserInfo().split(":", 2);
            username = parts[0];
            password = parts.length > 1 ? parts[1] : "";
        }
        return Optional.of(new ProxySettings(uri.getHost(), port, username, password));
    }

    private static boolean shouldBypassProxy(String host, Map<String, String> env) {
        String noProxy = firstNonBlank(env, "NO_PROXY", "no_proxy");
        if (noProxy == null || noProxy.isBlank()) {
            return false;
        }
        String normalizedHost = stripBrackets(host).toLowerCase(Locale.ROOT);
        for (String rawRule : noProxy.split(",")) {
            String rule = normalizeNoProxyRule(rawRule);
            if (rule.isEmpty()) {
                continue;
            }
            if ("*".equals(rule)
                    || normalizedHost.equals(rule)
                    || domainSuffixMatches(normalizedHost, rule)
                    || ipv4CidrMatches(normalizedHost, rule)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeNoProxyRule(String rawRule) {
        String rule = rawRule == null ? "" : rawRule.trim().toLowerCase(Locale.ROOT);
        if (rule.startsWith("*.")) {
            rule = rule.substring(1);
        }
        if (rule.startsWith("[") && rule.contains("]")) {
            rule = rule.substring(1, rule.indexOf(']'));
        } else if (rule.indexOf(':') > 0 && rule.indexOf(':') == rule.lastIndexOf(':') && !rule.contains("/")) {
            rule = rule.substring(0, rule.indexOf(':'));
        }
        return rule;
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host == null ? "" : host;
    }

    private static boolean domainSuffixMatches(String host, String rule) {
        if (rule.startsWith(".")) {
            String suffix = rule.substring(1);
            return host.equals(suffix) || host.endsWith(rule);
        }
        return host.endsWith("." + rule);
    }

    private static boolean ipv4CidrMatches(String host, String rule) {
        int slash = rule.indexOf('/');
        if (slash <= 0 || !isIpv4(host)) {
            return false;
        }
        String network = rule.substring(0, slash);
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(rule.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (!isIpv4(network) || prefixLength < 0 || prefixLength > 32) {
            return false;
        }
        long mask = prefixLength == 0 ? 0 : 0xffffffffL << (32 - prefixLength);
        return (ipv4ToLong(host) & mask) == (ipv4ToLong(network) & mask);
    }

    private static boolean isIpv4(String value) {
        return value != null && value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static long ipv4ToLong(String value) {
        String[] parts = value.split("\\.");
        long result = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255) {
                return -1;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static String firstNonBlank(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ProxySettings(String host, int port, String username, String password) {
    }
}
