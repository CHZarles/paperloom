package io.github.chzarles.paperloom.service;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * Turns a research-harness failure into the message shown to the product user.
 *
 * <p>An unreachable harness is an operational condition, not an internal defect: the user should be
 * told to retry in a moment instead of being told the service is broken.
 */
final class ResearchHarnessFailureMessages {

    static final String UNAVAILABLE =
            "The research service is temporarily unavailable. Your conversation is preserved; retry this turn in a moment.";

    static final String GENERIC =
            "The research run stopped because of an internal service error. Your conversation is preserved; retry this turn.";

    private ResearchHarnessFailureMessages() {
    }

    static String message(Throwable error) {
        return isUnavailable(error) ? UNAVAILABLE : GENERIC;
    }

    static boolean isUnavailable(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof ConnectException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof UnresolvedAddressException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
