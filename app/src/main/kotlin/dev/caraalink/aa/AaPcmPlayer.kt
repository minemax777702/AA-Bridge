package dev.caraalink.aa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.caraalink.diagnostics.DiagnosticLogger

/** Plays the 16 kHz mono PCM system stream negotiated in PHONE_DIRECT mode. */
internal class AaPcmPlayer(context: Context, preview: Boolean) {
    private val logger = DiagnosticLogger(context, "aa-system-audio")
    private val route = AaOutputRoute(context, preview, logger)
    private var track: AudioTrack? = null
    private var closed = false

    @Synchronized
    fun feed(pcm: ByteArray) {
        if (closed || pcm.isEmpty() || pcm.size % 2 != 0) return
        runCatching {
            val output = track ?: createTrack().also { track = it }
            route.refresh(output)
            val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) logger.log("PCM write failed: $written")
        }.onFailure { logger.log("PCM playback failed: $it") }
    }

    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Invalid PCM buffer size: $minimum" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minimum.coerceAtLeast(2048) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED)
            route.refresh(output, force = true)
            output.play()
            return output
        } catch (e: Exception) {
            output.release()
            throw e
        }
    }

    @Synchronized
    fun stop() {
        closed = true
        track?.let { output ->
            runCatching { output.stop() }
            runCatching { output.release() }
        }
        track = null
    }
}
