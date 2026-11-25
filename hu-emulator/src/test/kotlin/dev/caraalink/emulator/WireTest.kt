package dev.caraalink.emulator

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class WireTest {
    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
    @Test fun pxcRoundTrip() {
        val body = byteArrayOf(1,2,3,4)
        val message = Wire.readPxc(input(Wire.pxc(0x60000030, body, 41)))
        assertEquals(0x60000030, message.command)
        assertEquals(41, message.extra)
        assertArrayEquals(body, message.body)
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidPxcLength() {
        Wire.readPxc(input(Wire.little(16).putInt(1).putInt(Int.MAX_VALUE).putInt(0).putInt(0).array()))
    }
    @Test fun captureConfigurationAndTouchSlot() {
        val capture = Wire.little(Wire.capture(1920,720))
        assertEquals(1920, capture.short.toInt())
        assertEquals(720, capture.short.toInt())
        assertEquals(30, capture.int)
        val touch = Wire.readMirror(input(Wire.touch(2, 300, 400, 7)))
        assertEquals(0x20, touch.command)
        val data = Wire.little(touch.body)
        assertEquals(2, data.short.toInt())
        assertEquals(300, data.short.toInt())
        assertEquals(400, data.short.toInt())
        assertEquals(7, data.short.toInt())
    }
    @Test fun frameRoundTrip() {
        val pcm = byteArrayOf(5,6,7)
        assertArrayEquals(pcm, Wire.readFrame(input(Wire.little(7).putInt(3).put(pcm).array())))
        assertArrayEquals(ByteArray(0), Wire.readFrame(input(ByteArray(4))))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizeFrame() {
        Wire.readFrame(input(Wire.little(4).putInt(Int.MAX_VALUE).array()))
    }

    @Test fun acceptsNormalIpv4AndRejectsInvalidHosts() {
        assertTrue(Wire.isIpv4("192.168.1.100"))
        assertTrue(Wire.isIpv4("10.0.0.2"))
        assertFalse(Wire.isIpv4("192.168.1.256"))
        assertFalse(Wire.isIpv4("192.168.1"))
        assertFalse(Wire.isIpv4("192.168..1"))
        assertFalse(Wire.isIpv4("localhost"))
        assertFalse(Wire.isIpv4("192.168.1.+2"))
    }
}
