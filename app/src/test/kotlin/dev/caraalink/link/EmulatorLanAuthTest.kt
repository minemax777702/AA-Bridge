package dev.caraalink.link

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

class EmulatorLanAuthTest {
    @Test fun shortCodeMatchesWindowsClient() {
        val key = checkNotNull(EmulatorLanAuth.parseCode(" 7kmp3x9wrt "))
        assertEquals("79df28b8e8769666abe7461f129f4484", key.joinToString("") { "%02x".format(it.toInt() and 255) })
        assertNull(EmulatorLanAuth.parseCode("123456"))
        assertNull(EmulatorLanAuth.parseCode("7KMP3X9WRI"))
        assertNull(EmulatorLanAuth.parseCode("7KMP3X9WR0"))
        repeat(50) {
            val code = EmulatorLanAuth.newCode()
            assertEquals(10, code.length)
            assertNotNull(EmulatorLanAuth.parseCode(code))
        }
    }

    @Test fun mutualAuthentication() {
        val key = checkNotNull(EmulatorLanAuth.parseCode("7KMP3X9WRT"))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val result = FutureTask<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    EmulatorLanAuth.accept(socket.getInputStream(), socket.getOutputStream(), key, 10922) { true }
                }
            }
            thread(isDaemon = true) { result.run() }
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.soTimeout = 3000
                assertTrue(EmulatorLanAuth.connect(socket.getInputStream(), socket.getOutputStream(), key, 10922))
            }
            assertTrue(result.get(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun subnetRestriction() {
        val local = InetAddress.getByName("192.168.1.10")
        assertTrue(EmulatorWifi.sameSubnet(local, InetAddress.getByName("192.168.1.20"), 24))
        assertFalse(EmulatorWifi.sameSubnet(local, InetAddress.getByName("192.168.2.20"), 24))
    }
}
