package dev.caraalink.aa

import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryBenchPolicyTest {
    @Test fun onlyEnabledAuthenticatedBenchCanReportStationary() {
        val bench = ConnectionSnapshot(emulatorActive = true)
        assertTrue(StationaryBenchPolicy.canReportStationary(true, true, bench))
        assertFalse(StationaryBenchPolicy.canReportStationary(true, false, bench))
        assertFalse(StationaryBenchPolicy.canReportStationary(false, true, bench))
        assertFalse(StationaryBenchPolicy.canReportStationary(true, true, ConnectionSnapshot()))
        for (link in listOf(CarLink.CABLE, CarLink.WIRELESS)) {
            assertFalse(StationaryBenchPolicy.canReportStationary(true, true, bench.copy(link = link)))
        }
    }
}
