package dev.caraalink.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Debug-only LAN emulator handshake. A fresh nonce per connection and mutual
 * HMAC proofs prevent replay and sending the session secret over the wire.
 * The subsequent CarbitLink traffic is NOT encrypted: use a trusted Wi-Fi LAN.
 * This framing is independent of the original CarbitLink protocol.
 */
object EmulatorLanAuth {
    private val random = SecureRandom()
    private const val NONCE_SIZE = 32
    private const val KEY_SIZE = 16
    private const val PROOF_SIZE = 32
    private val label = "AA-Bridge/HU/LAN/v1".toByteArray(Charsets.UTF_8)
    private val hex = "0123456789abcdef"

    fun newCode(): String = ByteArray(KEY_SIZE).also(random::nextBytes).joinToString("") {
        val n = it.toInt() and 255
        "${hex[n ushr 4]}${hex[n and 15]}"
    }

    fun parseCode(code: String): ByteArray? {
        val normalized = code.trim().lowercase(java.util.Locale.ROOT)
        if (normalized.length != KEY_SIZE * 2 || normalized.any { it !in hex }) return null
        return ByteArray(KEY_SIZE) { i ->
            ((hex.indexOf(normalized[i * 2]) shl 4) or hex.indexOf(normalized[i * 2 + 1])).toByte()
        }
    }

    private fun proof(key: ByteArray, port: Int, nonce: ByteArray, direction: Byte): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE && port in 1..65535)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label)
        mac.update(ByteBuffer.allocate(4).putInt(port).array())
        mac.update(direction)
        return mac.doFinal(nonce)
    }

    /** Server reads only the fixed-size proof, never an attacker-supplied length. */
    fun accept(input: InputStream, output: OutputStream, key: ByteArray, port: Int,
        admit: () -> Boolean): Boolean {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        output.write(nonce)
        output.flush()
        val actual = ByteArray(PROOF_SIZE).also { DataInputStream(input).readFully(it) }
        if (!MessageDigest.isEqual(actual, proof(key, port, nonce, 1)) || !admit()) return false
        output.write(proof(key, port, nonce, 2))
        output.flush()
        return true
    }

    fun connect(input: InputStream, output: OutputStream, key: ByteArray, port: Int): Boolean {
        val nonce = ByteArray(NONCE_SIZE).also { DataInputStream(input).readFully(it) }
        output.write(proof(key, port, nonce, 1))
        output.flush()
        val reply = ByteArray(PROOF_SIZE).also { DataInputStream(input).readFully(it) }
        return MessageDigest.isEqual(reply, proof(key, port, nonce, 2))
    }
}
