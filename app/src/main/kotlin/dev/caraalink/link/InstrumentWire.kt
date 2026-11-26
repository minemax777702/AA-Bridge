package dev.caraalink.link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Instrument framing independently recovered from EPSDK arm64.
 * MCU values are deliberately unscaled; a false validity bit is not a zero reading.
 */
internal object InstrumentWire {
    const val PORT = 10926
    const val C2P = 0x01060000
    const val P2C = 0x01070000
    const val CONFIG = 0x01060010
    const val MXC = 0x01060020
    const val OVERSPEED = 0x01060040
    const val HEARTBEAT = 0x70000000
    private const val MAX_FRAME = 64 * 1024
    data class Frame(val command: Int, val extra: Int, val body: ByteArray)
    data class Signal(val name: String, val raw: Long, val valid: Boolean)
    data class McuFrame(val type: Int, val bytes: ByteArray, val signals: List<Signal>)

    fun encode(command: Int, extra: Int = 0, body: ByteArray = ByteArray(0)): ByteArray {
        require(body.size <= MAX_FRAME - 16)
        val len = body.size + 16
        return ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(len).putInt(command xor len).putInt(extra).put(body).array()
    }

    fun configReply(extra: Int): ByteArray =
        encode(CONFIG + 1, extra, "1.0.0".toByteArray(Charsets.US_ASCII).copyOf(20))

    /** Strict outer framing: no guessing a new header inside corrupt binary content. */
    class Framer {
        private var pending = ByteArray(0)
        var failed = false
            private set

        fun feed(chunk: ByteArray, onFrame: (Frame) -> Unit) {
            if (failed || chunk.isEmpty()) return
            if (chunk.size > MAX_FRAME || pending.size + chunk.size > MAX_FRAME * 2) {
                failed = true; pending = ByteArray(0); return
            }
            val data = pending + chunk
            var pos = 0
            while (data.size - pos >= 16) {
                val h = ByteBuffer.wrap(data, pos, 16).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = h.int
                val len = h.int
                val check = h.int
                val extra = h.int
                if (len !in 16..MAX_FRAME || (cmd xor len) != check) {
                    failed = true; pending = ByteArray(0); return
                }
                if (data.size - pos < len) break
                onFrame(Frame(cmd, extra, data.copyOfRange(pos + 16, pos + len)))
                pos += len
            }
            pending = data.copyOfRange(pos, data.size)
        }
    }

    /** CRC table in SDK starts 0000,1021,2042; seed zero, no reflection. */
    fun crc16(data: ByteArray, end: Int = data.size): Int {
        var crc = 0
        for (i in 0 until end) {
            crc = crc xor ((data[i].toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        return crc
    }

    /** Nested 53 CA, u16 LE length (total=length+4), u16 LE type, CRC16 LE.
     * Handles fragmentation across MXC messages and bounded resynchronization.
     */
    class McuFramer {
        private var pending = ByteArray(0)
        var badCrc = 0L
            private set
        var discarded = 0L
            private set

        fun feed(chunk: ByteArray, onFrame: (McuFrame) -> Unit) {
            if (chunk.size > MAX_FRAME) { discarded += chunk.size; pending = ByteArray(0); return }
            val data = pending + chunk
            var pos = 0
            fun u(i: Int) = data[i].toInt() and 255
            while (data.size - pos >= 4) {
                if (u(pos) != 0x53 || u(pos + 1) != 0xca) { pos++; discarded++; continue }
                val len = (u(pos + 2) or (u(pos + 3) shl 8)) + 4
                if (len !in 8..4096) { pos++; discarded++; continue }
                if (data.size - pos < len) break
                val frame = data.copyOfRange(pos, pos + len)
                val crc = u(pos + len - 2) or (u(pos + len - 1) shl 8)
                if (crc16(frame, len - 2) != crc) { badCrc++; pos++; discarded++; continue }
                val type = u(pos + 4) or (u(pos + 5) shl 8)
                onFrame(McuFrame(type, frame, decode(type, frame)))
                pos += len
            }
            pending = data.copyOfRange(pos, data.size)
        }
    }

    /** Offsets are relative to MCU sync byte, NOT PXC payload or CAN identifiers.
     * Only fields traced to named EPMInstDataEnum entries are returned.
     */
    private fun decode(type: Int, data: ByteArray): List<Signal> {
        val out = mutableListOf<Signal>()
        val end = data.size - 2 // never read CRC as a signal
        fun bits(byte: Int, shift: Int, width: Int): Long? {
            val start = byte * 8 + shift
            if (start + width > end * 8) return null
            var result = 0L
            for (i in 0 until width) {
                val bit = start + i
                result = result or (((data[bit / 8].toInt() ushr (bit % 8)) and 1).toLong() shl i)
            }
            return result
        }
        fun field(name: String, byte: Int, shift: Int, width: Int, flagByte: Int, flagBit: Int, bias: Long = 0) {
            val raw = bits(byte, shift, width) ?: return
            val flag = bits(flagByte, flagBit, 1) ?: return
            out += Signal(name, raw + bias, flag == 1L)
        }
        when (type) {
            1 -> {
                field("SPEED", 9, 0, 15, 10, 7)
                field("GEAR", 11, 0, 7, 11, 7)
                field("RPM", 12, 0, 15, 13, 7)
                field("TEMP_WATER", 14, 0, 8, 15, 0, -60)
                field("TEMP_WATER_LEVEL", 15, 1, 4, 15, 5)
                field("TEMP_AIR", 17, 0, 10, 18, 2, -30)
                field("FUEL_PERCENT", 25, 0, 7, 25, 7)
                field("FUEL_REMAIN", 29, 0, 16, 31, 1)
                val lights = listOf("LIGHT_FAR", "LIGHT_NEAR", "LIGHT_TURN_LEFT", "LIGHT_TURN_RIGHT",
                    "LIGHT_EDGE", "LIGHT_AUTO", "LIGHT_AUTO_NEAR", "LIGHT_OIL_PRESSURE",
                    "LIGHT_SIDE_STAND", "LIGHT_READY", "LIGHT_POWEROFF", "LIGHT_MTC",
                    "LIGHT_GEAR_R", "LIGHT_BRAKE", "LIGHT_PARK", "LIGHT_TIRE_PRESS",
                    "LIGHT_EPS", "LIGHT_NOS", "LIGHT_WARNING", "LIGHT_OPC", "LOCK_HEAD",
                    "CUSHION", "LIGHT_CRUISE", "LIGHT_LIMP", "LIGHT_REVERSE", "LIGHT_TOPPLE_OVER",
                    "LIGHT_BT1", "LIGHT_BT2", "LIGHT_USB", "SMART_KEY", "LIGHT_MOTOR_FAULT",
                    "LIGHT_ABS", "LIGHT_STT", "LIGHT_MAINTAIN", "LIGHT_HANDHOLD", "LIGHT_ECU_ERROR",
                    "LIGHT_ACCELE_ERROR", "LIGHT_BATTERY_ERROR", "LIGHT_MOTOR_HOT", "LIGHT_SEAT_HEATING",
                    "LIGHT_DVR", "LIGHT_UPSHIFT", "LIGHT_BRAKE_ERROR", "LIGHT_TCS")
                // The first 44 light values and flags are separate packed bitmaps.
                lights.forEachIndexed { i, name -> field(name, 52, i, 1, 66, i) }
                field("DRIVE_MODE", 84, 0, 3, 84, 3)
                field("BACKLIGHT_LEVEL", 84, 4, 3, 84, 7)
                field("IGN", 87, 1, 1, 87, 2)
            }
            2 -> {
                field("ODO", 6, 0, 23, 8, 7)
                field("TRIP1", 9, 0, 15, 10, 7)
                field("TRIP2", 11, 0, 15, 12, 7)
                field("RANGE_LEFT", 47, 0, 15, 48, 7)
            }
        }
        return out
    }
}
