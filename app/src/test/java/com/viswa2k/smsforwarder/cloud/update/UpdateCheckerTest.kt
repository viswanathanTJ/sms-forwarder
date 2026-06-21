package com.viswa2k.smsforwarder.cloud.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun higherPatchIsNewer() = assertTrue(UpdateChecker.isNewer("1.1.3", "1.1.2"))
    @Test fun higherMinorIsNewer() = assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.9"))
    @Test fun higherMajorIsNewer() = assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    @Test fun sameVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.1.2", "1.1.2"))
    @Test fun olderIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.1.1", "1.1.2"))

    @Test fun ignoresLeadingVAndDebugSuffix() {
        assertTrue(UpdateChecker.isNewer("v1.1.3", "1.1.2"))
        assertTrue(UpdateChecker.isNewer("1.1.3", "1.1.2-debug"))
        assertFalse(UpdateChecker.isNewer("1.1.2", "1.1.2-debug")) // 1.1.2 == 1.1.2
    }

    @Test fun shorterCurrentTreatedAsZeros() {
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1"))   // 1.1.1 > 1.1.0
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1"))  // equal
    }
}
