package dev.caraalink.emulator

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Wire {
    const val PXC = 10922
    const val CONTROL = 10921
    const val VIDEO = 10920
    const val MAX_FRAME = 4 * 1024 * 1024

    data class Packet(val command: Int, val extra: Int, val body: ByteArray)

    fun pxc(command: Int, body: ByteArray = ByteArray(0), extra: Int = 0): ByteArray {
        require(body.size <= MAX_FRAME)
        val total = body.size + 16
        return little(total).putInt(command).putInt(total).putInt(command xor total)
            .putInt(extra).put(body).array()
    }

    fun readPxc(input: DataInputStream): Packet {
        val head = little(input.readExactly(16))
        val command = head.int
        val total = head.int
        val check = head.int
        val extra = head.int
        require(total in 16..MAX_FRAME + 16 && check == command xor total) { "Invalid PXC header" }
        return Packet(command, extra, input.readExactly(total - 16))
    }

    fun mirror(command: Int, body: ByteArray = ByteArray(0)): ByteArray {
        require(body.size <= 65535)
        return little(8 + body.size).putShort(command.toShort())
            .putShort(body.size.toShort()).putInt(0).put(body).array()
    }

    fun readMirror(input: DataInputStream): Packet {
        val head = little(input.readExactly(8))
        val command = head.short.toInt() and 65535
        val size = head.short.toInt() and 65535
        return Packet(command, head.int, input.readExactly(size))
    }

    fun capture(width: Int, height: Int, fps: Int = 30): ByteArray {
        require(width in 16..2560 && height in 16..1600 && fps in 1..60)
        return little(32).putShort(width.toShort()).putShort(height.toShort())
            .putInt(fps).putInt(2).putInt(0).putShort(0).putShort(0)
            .putInt(4_194_304).put(0).put(1).put(if (width >= height) 1 else 0)
            .put(0).put(0).put(0).putShort(0).array()
    }

    fun touch(event: Int, x: Int, y: Int, slot: Int, key: Int = 0): ByteArray =
        mirror(0x20, little(10).putShort(event.toShort()).putShort(x.toShort())
            .putShort(y.toShort()).putShort(slot.toShort()).putShort(key.toShort()).array())

    fun readFrame(input: DataInputStream): ByteArray {
        val length = little(input.readExactly(4)).int
        require(length in 0..MAX_FRAME) { "Invalid video size: $length" }
        return input.readExactly(length)
    }

    fun little(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun little(data: ByteArray) = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private fun DataInputStream.readExactly(size: Int) = ByteArray(size).also { readFully(it) }
}
