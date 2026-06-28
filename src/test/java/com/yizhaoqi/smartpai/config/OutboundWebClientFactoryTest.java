package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundWebClientFactoryTest {

    @Test
    void resolvesHttpsProxyForExternalHttpsTarget() {
        Map<String, String> env = Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:7890",
                "NO_PROXY", "localhost,127.0.0.0/8"
        );

        OutboundWebClientFactory.ProxySettings proxy = OutboundWebClientFactory
                .resolveProxyForTarget(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"), env)
                .orElseThrow();

        assertEquals("127.0.0.1", proxy.host());
        assertEquals(7890, proxy.port());
    }

    @Test
    void honorsNoProxyForLocalhostAndLoopbackCidr() {
        Map<String, String> env = Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:7890",
                "HTTP_PROXY", "http://127.0.0.1:7890",
                "NO_PROXY", "localhost,127.0.0.0/8"
        );

        assertTrue(OutboundWebClientFactory
                .resolveProxyForTarget(URI.create("http://localhost:8000"), env)
                .isEmpty());
        assertTrue(OutboundWebClientFactory
                .resolveProxyForTarget(URI.create("http://127.0.0.1:8000"), env)
                .isEmpty());
    }

    @Test
    void honorsNoProxyDomainSuffix() {
        Map<String, String> env = Map.of(
                "HTTPS_PROXY", "http://127.0.0.1:7890",
                "NO_PROXY", ".internal.example"
        );

        assertTrue(OutboundWebClientFactory
                .resolveProxyForTarget(URI.create("https://api.internal.example/v1"), env)
                .isEmpty());
    }

    @Test
    void fallsBackToAllProxyWhenSchemeProxyIsAbsent() {
        Map<String, String> env = Map.of("ALL_PROXY", "http://127.0.0.1:7890");

        OutboundWebClientFactory.ProxySettings proxy = OutboundWebClientFactory
                .resolveProxyForTarget(URI.create("https://api.deepseek.com/v1"), env)
                .orElseThrow();

        assertEquals("127.0.0.1", proxy.host());
        assertEquals(7890, proxy.port());
    }
}
