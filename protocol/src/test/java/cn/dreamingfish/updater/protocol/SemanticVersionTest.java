package cn.dreamingfish.updater.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {
    @Test
    void ordersReleaseAfterPreRelease() {
        assertTrue(SemanticVersion.parse("1.0.0").compareTo(SemanticVersion.parse("1.0.0-rc.1")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-rc.2").compareTo(SemanticVersion.parse("1.0.0-rc.1")) > 0);
        assertTrue(SemanticVersion.parse("2.0.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
    }

    @Test
    void comparesArbitrarilyLargeNumericPreReleaseIdentifiersWithoutOverflow() {
        SemanticVersion smaller = SemanticVersion.parse("1.0.0-999999999999999999999999999999");
        SemanticVersion larger = SemanticVersion.parse("1.0.0-1000000000000000000000000000000");
        assertTrue(smaller.compareTo(larger) < 0);
    }

    @Test
    void rejectsInvalidPreReleaseIdentifiers() {
        assertThrows(ProtocolException.class, () -> SemanticVersion.parse("1.0.0-alpha..1"));
        assertThrows(ProtocolException.class, () -> SemanticVersion.parse("1.0.0-01"));
    }
}
