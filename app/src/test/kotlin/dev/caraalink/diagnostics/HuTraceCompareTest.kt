package dev.caraalink.diagnostics

import org.junit.Assert.*
import org.junit.Test

class HuTraceCompareTest {
    @Test fun recognizesBothFormatsAndIgnoresMedia() {
        val reference = HuTraceCompare.parse("RX PXC 0x10010 len=80\nTX PXC 0x10011 len=20\nRX PXC 0x10060 len=640")
        val bridge = HuTraceCompare.parse("PXC C2P 0x10010 len=90\nHU TX PXC:0x10011 len=20\nHU RX MIRROR:0x10 len=32")
        assertEquals(2,reference.commands.size)
        val diff = HuTraceCompare.compare(reference,bridge)
        assertEquals(2,diff.size)
        assertTrue(diff.any { it.reference == setOf(80) && it.bridge == setOf(90) })
        assertTrue(diff.any { it.reference == null && it.bridge == setOf(32) })
    }
    @Test fun emptyAndUnrecognizedTracesAreNotEvidence() {
        assertEquals(0,HuTraceCompare.parse("unrelated log").recognized)
    }
}
