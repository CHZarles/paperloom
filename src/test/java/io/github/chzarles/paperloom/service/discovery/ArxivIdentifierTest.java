package io.github.chzarles.paperloom.service.discovery;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArxivIdentifierTest {

    @Test
    void acceptsPlainAndVersionedIdentifiers() {
        assertEquals(Optional.of("2409.04701"), ArxivIdentifier.normalize("2409.04701"));
        assertEquals(Optional.of("2409.04701"), ArxivIdentifier.normalize(" 2409.04701v3 "));
        assertEquals(Optional.of("2409.04701"), ArxivIdentifier.normalize("https://arxiv.org/abs/2409.04701v2"));
        assertEquals(Optional.of("2409.04701"), ArxivIdentifier.normalize("arXiv:2409.04701v1"));
    }

    @Test
    void acceptsOldStyleIdentifiers() {
        assertEquals(Optional.of("hep-th/9901001"), ArxivIdentifier.normalize("hep-th/9901001v1"));
        assertEquals(Optional.of("math.GT/0309136"), ArxivIdentifier.normalize("https://arxiv.org/abs/math.GT/0309136"));
    }

    @Test
    void rejectsGarbage() {
        assertTrue(ArxivIdentifier.normalize(null).isEmpty());
        assertTrue(ArxivIdentifier.normalize("   ").isEmpty());
        assertTrue(ArxivIdentifier.normalize("not-an-id").isEmpty());
        assertTrue(ArxivIdentifier.normalize("2409.047").isEmpty());
    }

    @Test
    void requireValidExplainsRejection() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ArxivIdentifier.requireValid("nope"));
        assertEquals("无法识别的 arXiv 标识: nope", error.getMessage());
    }
}
