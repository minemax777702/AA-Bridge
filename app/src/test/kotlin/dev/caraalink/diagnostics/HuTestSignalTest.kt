package dev.caraalink.diagnostics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class HuTestSignalTest {
    @Test fun stereoSequenceAndAmplitude() {
        val left = HuTestSignal.pcm(48000,2,1000,1000)
        assertTrue(left.indices.filter { it%2 == 0 }.any { left[it].toInt() != 0 })
        assertTrue(left.indices.filter { it%2 == 1 }.all { left[it].toInt() == 0 })
        val right = HuTestSignal.pcm(48000,2,37000,1000)
        assertTrue(right.indices.filter { it%2 == 0 }.all { right[it].toInt() == 0 })
        assertTrue(right.indices.filter { it%2 == 1 }.any { right[it].toInt() != 0 })
        assertTrue((left+right).all { abs(it.toInt()) <= 2600 })
    }
}
