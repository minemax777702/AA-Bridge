package dev.caraalink.link

import org.junit.Assert.*
import org.junit.Test

class InstrumentWireTest {
    private fun mcu(type: Int, size: Int = 121, edit: (ByteArray) -> Unit = {}): ByteArray {
        val data = ByteArray(size)
        data[0] = 0x53; data[1] = 0xca.toByte()
        val len = size - 4
        data[2] = len.toByte(); data[3] = (len ushr 8).toByte()
        data[4] = type.toByte(); data[5] = (type ushr 8).toByte()
        edit(data)
        val crc = InstrumentWire.crc16(data, size - 2)
        data[size - 2] = crc.toByte(); data[size - 1] = (crc ushr 8).toByte()
        return data
    }

    @Test fun `standard CRC check value`() {
        assertEquals(0x31c3, InstrumentWire.crc16("123456789".toByteArray()))
    }

    @Test fun `outer frames accept splits and concatenation`() {
        val framer = InstrumentWire.Framer()
        val frames = mutableListOf<InstrumentWire.Frame>()
        val a = InstrumentWire.encode(InstrumentWire.C2P)
        val b = InstrumentWire.configReply(7)
        (a + b).forEach { framer.feed(byteArrayOf(it)) { frame -> frames.add(frame) } }
        assertFalse(framer.failed)
        assertEquals(2, frames.size)
        assertEquals(InstrumentWire.C2P, frames[0].command)
        assertEquals(InstrumentWire.CONFIG + 1, frames[1].command)
        assertEquals(7, frames[1].extra)
        assertEquals(20, frames[1].body.size)
        assertArrayEquals("1.0.0".toByteArray().copyOf(20), frames[1].body)
    }

    @Test fun `reject corrupt outer header`() {
        val framer = InstrumentWire.Framer()
        val data = InstrumentWire.encode(InstrumentWire.C2P)
        data[8] = (data[8].toInt() xor 1).toByte()
        framer.feed(data) { fail("invalid frame emitted") }
        assertTrue(framer.failed)
    }

    @Test fun `MCU fields and validity flags independently decoded`() {
        val bytes = mcu(1) {
            it[9] = 0x34; it[10] = 0x92.toByte()
            it[11] = 0x85.toByte()
            it[12] = 0x55; it[13] = 0x01 // RPM invalid
            it[14] = 150.toByte(); it[15] = 1
            it[52] = 3; it[66] = 2 // near valid; far invalid
            it[84] = 0xda.toByte()
            it[87] = 6
        }
        val frames = mutableListOf<InstrumentWire.McuFrame>()
        val framer = InstrumentWire.McuFramer()
        bytes.forEach { framer.feed(byteArrayOf(it)) { f -> frames.add(f) } }
        val signals = frames.single().signals.associateBy { it.name }
        assertEquals(InstrumentWire.Signal("SPEED", 0x1234, true), signals["SPEED"])
        assertEquals(InstrumentWire.Signal("GEAR", 5, true), signals["GEAR"])
        assertEquals(InstrumentWire.Signal("RPM", 0x155, false), signals["RPM"])
        assertEquals(InstrumentWire.Signal("TEMP_WATER", 90, true), signals["TEMP_WATER"])
        assertEquals(InstrumentWire.Signal("LIGHT_NEAR", 1, true), signals["LIGHT_NEAR"])
        assertEquals(InstrumentWire.Signal("LIGHT_FAR", 1, false), signals["LIGHT_FAR"])
        assertEquals(InstrumentWire.Signal("DRIVE_MODE", 2, true), signals["DRIVE_MODE"])
        assertEquals(InstrumentWire.Signal("BACKLIGHT_LEVEL", 5, true), signals["BACKLIGHT_LEVEL"])
        assertEquals(InstrumentWire.Signal("IGN", 1, true), signals["IGN"])
    }

    @Test fun `bad CRC never produces signals and next frame recovers`() {
        val bad = mcu(1, 18).also { it[9] = 22 }
        val good = mcu(2, 12) { it[6] = 42; it[8] = 0x80.toByte() }
        val frames = mutableListOf<InstrumentWire.McuFrame>()
        val framer = InstrumentWire.McuFramer()
        framer.feed(bad + good) { frames.add(it) }
        assertEquals(1L, framer.badCrc)
        assertEquals(2, frames.single().type)
        assertEquals(InstrumentWire.Signal("ODO", 42, true), frames.single().signals.first())
        assertFalse(frames.single().signals.any { it.name == "TRIP1" })
    }

    @Test fun `short valid frame cannot read CRC as telemetry`() {
        val framer = InstrumentWire.McuFramer()
        framer.feed(mcu(1, 11)) { assertTrue(it.signals.isEmpty()) }
    }
}
