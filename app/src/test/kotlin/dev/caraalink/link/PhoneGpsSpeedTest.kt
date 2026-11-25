package dev.caraalink.link

import org.junit.Assert.*
import org.junit.Test

class PhoneGpsSpeedTest {
    @Test fun validatesFreshMeasurementsWithoutInventingZero() {
        val now = 10_000_000_000L
        assertEquals(0f, PhoneGpsSpeed.validate(0f, .5f, now, now)?.metersPerSecond)
        assertEquals(12f, PhoneGpsSpeed.validate(12f, .5f, now, now)?.metersPerSecond)
        assertNull(PhoneGpsSpeed.validate(Float.NaN, .5f, now, now))
        assertNull(PhoneGpsSpeed.validate(-1f, .5f, now, now))
        assertNull(PhoneGpsSpeed.validate(0f, 2f, now, now))
        assertNull(PhoneGpsSpeed.validate(0f, .5f, now - 3_000_000_001L, now))
        assertNull(PhoneGpsSpeed.validate(0f, .5f, now + 1, now))
        assertFalse(checkNotNull(PhoneGpsSpeed.validate(0f, .5f, now, now)).freshAt(now + 4_000_000_000L))
    }
}
