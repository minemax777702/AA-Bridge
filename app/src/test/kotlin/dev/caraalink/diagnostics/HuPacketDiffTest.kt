package dev.caraalink.diagnostics

import org.junit.Assert.*
import org.junit.Test

class HuPacketDiffTest {
    @Test fun `reports changed byte offsets and unsigned values`() {
        val result = HuPacketDiff.describe(byteArrayOf(0, -1, 5), byteArrayOf(0, 1, 5))
        assertTrue(result.contains("0x0001:FF→01"))
        assertTrue(result.contains("changed aligned bytes=1"))
    }

    @Test fun `reports length changes without assigning semantics`() {
        val result = HuPacketDiff.describe(byteArrayOf(1), byteArrayOf(1, 2, 3))
        assertTrue(result.contains("len 1→3"))
        assertTrue(result.contains("appended@1: 02 03"))
    }

    @Test fun `bounds repetitive diff output`() {
        val result = HuPacketDiff.describe(ByteArray(4096), ByteArray(4096) { 1 })
        assertTrue(result.contains("changed aligned bytes=4096"))
        assertTrue(result.contains("+4064 more"))
        assertTrue(result.length < 1000)
    }
}
