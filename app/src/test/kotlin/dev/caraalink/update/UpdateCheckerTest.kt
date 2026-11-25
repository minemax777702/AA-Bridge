package dev.caraalink.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun acceptsOnlyNewerStableVersions() {
        assertTrue(UpdateChecker.isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.3.0", "1.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.9", "1.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("2.0.0-beta", "1.3.0"))
        assertFalse(UpdateChecker.isNewerVersion("invalid", "1.3.0"))
    }
}
