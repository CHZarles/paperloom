package io.github.chzarles.paperloom.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 Authorization 头取 JWT。取不到时返回 {@code null}。
 */
public final class BearerToken {

    private BearerToken() {
    }

    public static String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
