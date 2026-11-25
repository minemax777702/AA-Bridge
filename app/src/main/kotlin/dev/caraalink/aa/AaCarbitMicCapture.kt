package dev.caraalink.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.link.CarbitAudioTransport

/** A single AA voice-input request: car mic first, then local capture on failure. */
internal class AaCarbitMicCapture(
    private val context: Context,
    private val transport: CarbitAudioTransport,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val logger = DiagnosticLogger(context, "aa-carbit-mic")
    private val huSession = dev.caraalink.diagnostics.HuCompatibility.state.value.sessionId
    @Volatile private var running = false
    private var first: ByteArray? = null
    private var worker: Thread? = null
    private var fallback: AaMicCapture? = null

    /** Require both the protocol ACK and real PCM before reporting success to AA. */
    @Synchronized
    fun prepare(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!transport.requestMic()) return false
        val chunk = try {
            transport.pollMic(550)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (chunk == null) {
            transport.stopMic()
            logger.log("Carbit microphone unavailable; using local input")
            return false
        }
        first = chunk
        dev.caraalink.diagnostics.HuCompatibility.microphone(huSession, "CARBIT_PCM")
        running = true
        return true
    }

    @Synchronized
    fun startStreaming() {
        if (!running || worker != null) return
        val initial = first
        first = null
        worker = Thread({
            try {
                if (running && initial != null) onChunk(initial)
                while (running) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        stop()
                        break
                    }
                    val pcm = transport.pollMic(750)
                    if (pcm == null) {
                        switchToLocal()
                        break
                    }
                    if (running) onChunk(pcm)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                logger.log("Carbit microphone failed: $e")
                switchToLocal()
            }
        }, "AaCarbitMicCapture").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun switchToLocal() {
        if (!running) return
        transport.stopMic()
        val local = AaMicCapture(context) { pcm -> if (running) onChunk(pcm) }
        if (local.prepare()) {
            fallback = local
            local.startStreaming()
            logger.log("Carbit microphone stalled; switched to local input")
        } else {
            running = false
            dev.caraalink.diagnostics.HuCompatibility.microphone(huSession, "UNAVAILABLE")
            logger.log("Carbit microphone and local fallback unavailable")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        first = null
        worker?.interrupt()
        worker = null
        transport.stopMic()
        fallback?.stop()
        fallback = null
    }
}
