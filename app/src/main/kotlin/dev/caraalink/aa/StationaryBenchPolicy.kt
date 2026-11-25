package dev.caraalink.aa

import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot

/** The settings preference cannot substitute for real vehicle parking data. */
internal object StationaryBenchPolicy {
    fun canReportStationary(
        isLanTestBench: Boolean,
        enabled: Boolean,
        snapshot: ConnectionSnapshot,
    ): Boolean = isLanTestBench && enabled && snapshot.emulatorActive && snapshot.link == CarLink.NONE
}
