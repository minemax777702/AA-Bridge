package dev.caraalink.aa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import kotlin.math.abs

/** Captures 16 kHz / 16-bit / mono only during an Android Auto microphone request. */
class AaMicCapture(private val context: Context, private val onChunk: (ByteArray) -> Unit) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = (SAMPLE_RATE / 50) * 2
    }

    private val logger = DiagnosticLogger(context, "aa-mic")
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Start recording before reporting success, but wait for the protocol response before sending data. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun prepare(): Boolean {
        if (record != null) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            logger.log("Microphone permission not granted")
            return false
        }
        var candidate: AudioRecord? = null
        return try {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Invalid microphone buffer size: $minimum" }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minimum.coerceAtLeast(CHUNK_BYTES * 4),
            )
            candidate = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            selectInput(rec)
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
            record = rec
            logger.log("Microphone started, device=${rec.routedDevice?.type}")
            true
        } catch (e: Exception) {
            logger.log("Microphone start failed: $e")
            runCatching { candidate?.release() }
            false
        }
    }

    private fun selectInput(rec: AudioRecord) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val useBluetooth = AppSettings.aaAudioRoute(context) != AppSettings.AaAudioRoute.DEVICE_ONLY
        val bluetooth = if (useBluetooth) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } else null
        val device = bluetooth ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (device != null) {
            val accepted = rec.setPreferredDevice(device)
            logger.log("Microphone preferred type=${device.type} accepted=$accepted")
        }
    }

    @Synchronized
    fun startStreaming() {
        val rec = record ?: return
        if (thread != null) return
        thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            var chunks = 0
            var peak = 0
            try {
                while (record === rec) {
                    val count = rec.read(buffer, 0, buffer.size)
                    if (count < 0) {
                        logger.log("Microphone read failed: $count")
                        break
                    }
                    if (count == 0) {
                        Thread.sleep(10)
                        continue
                    }
                    for (i in 0 until count - 1 step 2) {
                        peak = maxOf(peak, abs((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)))
                    }
                    if (++chunks % 50 == 0) {
                        runCatching {
                            // Re-evaluate the input after Bluetooth is disconnected.
                            if (rec.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                                (AppSettings.aaAudioRoute(context) == AppSettings.AaAudioRoute.DEVICE_ONLY ||
                                    context.getSystemService(AudioManager::class.java)
                                        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                        ?.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } == true)
                            ) selectInput(rec)
                            logger.log("peak=$peak silenced=${rec.activeRecordingConfiguration?.isClientSilenced} device=${rec.routedDevice?.type}")
                        }
                        peak = 0
                    }
                    if (record === rec) onChunk(buffer.copyOf(count))
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                logger.log("Microphone capture failed: $e")
            } finally {
                synchronized(this) {
                    if (record === rec) stop()
                }
            }
        }, "AaMicCapture").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        val rec = record
        record = null
        thread?.interrupt()
        thread = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
    }
}
