package dev.caraalink.diagnostics

import org.junit.Assert.*
import org.junit.Test

class HuCalibrationTest {
    @Test fun identityAndSwappedAxes() {
        val points = HuCalibration.targets
        val identity = checkNotNull(HuCalibration.fit(points))
        assertTrue(identity.valid)
        assertEquals(0.0,identity.maxError,1e-8)
        val swapped = checkNotNull(HuCalibration.fit(points.map { HuCalibration.Point(1-it.y,it.x) }))
        assertTrue(swapped.valid)
        assertTrue(swapped.mapping.swap)
        assertEquals(0.0,swapped.maxError,1e-8)
    }
    @Test fun independentCheckRejectsWrongPoint() {
        val points = HuCalibration.targets.toMutableList()
        points[8] = HuCalibration.Point(.9,.9)
        assertFalse(checkNotNull(HuCalibration.fit(points)).valid)
        assertNull(HuCalibration.fit(List(9) { HuCalibration.Point(.5,.5) }))
    }
}
