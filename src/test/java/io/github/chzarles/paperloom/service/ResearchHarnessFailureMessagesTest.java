package io.github.chzarles.paperloom.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchHarnessFailureMessagesTest {

    @Test
    void connectionFailureIsReportedAsTemporaryUnavailability() {
        Throwable error = new CompletionException(new ConnectException("Connection refused"));

        assertEquals(ResearchHarnessFailureMessages.UNAVAILABLE, ResearchHarnessFailureMessages.message(error));
    }

    @Test
    void timeoutIsReportedAsTemporaryUnavailability() {
        Throwable error = new HttpTimeoutException("request timed out");

        assertEquals(ResearchHarnessFailureMessages.UNAVAILABLE, ResearchHarnessFailureMessages.message(error));
    }

    @Test
    void otherFailuresKeepTheGenericMessage() {
        Throwable error = new IllegalStateException("The Python research harness stream ended without a result");

        assertEquals(ResearchHarnessFailureMessages.GENERIC, ResearchHarnessFailureMessages.message(error));
        assertFalse(ResearchHarnessFailureMessages.isUnavailable(error));
    }

    @Test
    void nestedCauseIsInspected() {
        Throwable error = new CompletionException(new IOException("wrapper", new ConnectException("refused")));

        assertTrue(ResearchHarnessFailureMessages.isUnavailable(error));
    }
}
