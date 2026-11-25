package dev.caraalink.emulator

import dev.caraalink.protocol.EmulatorLanAuth
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class EmulatorLanAuthTest {
    private val key = checkNotNull(EmulatorLanAuth.parseCode("0123456789abcdef0123456789abcdef"))
    private val wrong = checkNotNull(EmulatorLanAuth.parseCode("ffffffffffffffffffffffffffffffff"))

    private fun handshake(clientKey: ByteArray, clientPort: Int = 10922, admitted: Boolean = true): Pair<Boolean, Boolean> {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 3000
                val result = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        EmulatorLanAuth.accept(socket.getInputStream(), socket.getOutputStream(), key, 10922) { admitted }
                    }
                }
                val clientResult = Socket(server.inetAddress, server.localPort).use { socket ->
                    socket.soTimeout = 3000
                    runCatching { EmulatorLanAuth.connect(socket.getInputStream(), socket.getOutputStream(), clientKey, clientPort) }
                        .getOrDefault(false)
                }
                return result.get(4, TimeUnit.SECONDS) to clientResult
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun matchingCodeAuthenticatesBothEndpoints() {
        assertEquals(true to true, handshake(key))
    }
    @Test fun wrongCodeRejected() {
        assertEquals(false to false, handshake(wrong))
    }
    @Test fun wrongPortRejected() {
        assertEquals(false to false, handshake(key, 10921))
    }
    @Test fun correctCodeCannotBypassPeerAdmission() {
        assertEquals(false to false, handshake(key, admitted = false))
    }
    @Test fun codeFormat() {
        val code = EmulatorLanAuth.newCode()
        assertEquals(32, code.length)
        assertEquals(16, EmulatorLanAuth.parseCode(code)?.size)
        assertArrayEquals(key, EmulatorLanAuth.parseCode("0123456789ABCDEF0123456789ABCDEF"))
        assertNull(EmulatorLanAuth.parseCode("123456"))
        assertNull(EmulatorLanAuth.parseCode("z".repeat(32)))
    }
}
