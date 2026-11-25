package dev.caraalink.settings

import org.junit.Assert.*
import org.junit.Test

class HuTestProfilesTest {
    @Test fun allDistinctManualModesAreCovered() {
        val profiles = HuTestProfiles.manual
        assertEquals(16, profiles.size)
        assertEquals(17, HuTestProfiles.all.map { it.signature }.toSet().size)
        for (route in HuTestProfiles.routes) {
            for (mic in listOf(false, true)) {
                for (gesture in listOf("MULTI", "SINGLE")) {
                    assertEquals(1, profiles.count { it.matches(false, route, mic, gesture) })
                }
            }
        }
    }

    @Test fun previousAutomaticTouchSettingMatchesNativeMultiTouch() {
        val selected = HuTestProfiles.all.single { it.matches(false, "phone_direct", false, "AUTO") }
        assertEquals("MULTI", selected.gestures)
        assertFalse(selected.auto)
    }

    @Test fun automaticPresetIsDistinctFromManualModes() {
        assertEquals(listOf(HuTestProfiles.automatic),
            HuTestProfiles.all.filter { it.matches(true, "bt_preferred", false, "AUTO") })
    }
}
