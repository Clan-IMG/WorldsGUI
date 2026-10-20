package net.clanimg.worldsGUI.idle;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleUnloadPolicyTest {
    private static final long NOW = 1_000_000_000L;

    @Test
    void doesNotUnloadBeforeTheDelayHasPassed() {
        long emptySince = NOW - 100 * 1000L;
        assertFalse(IdleUnloadPolicy.shouldUnload(emptySince, NOW, 300));
    }

    @Test
    void unloadsExactlyAtTheDelayBoundary() {
        long emptySince = NOW - 300 * 1000L;
        assertTrue(IdleUnloadPolicy.shouldUnload(emptySince, NOW, 300));
    }

    @Test
    void unloadsWellAfterTheDelay() {
        long emptySince = NOW - 3600 * 1000L;
        assertTrue(IdleUnloadPolicy.shouldUnload(emptySince, NOW, 300));
    }

    @Test
    void zeroOrNegativeDelayMeansUnloadAsSoonAsEmpty() {
        assertTrue(IdleUnloadPolicy.shouldUnload(NOW, NOW, 0));
        assertTrue(IdleUnloadPolicy.shouldUnload(NOW - 1, NOW, -5));
    }
}
