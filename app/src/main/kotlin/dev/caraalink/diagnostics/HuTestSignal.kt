package dev.caraalink.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.caraalink.link.CarbitAudioTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sin
import kotlin.math.PI

/** Short, low-amplitude synthetic tone; no volume changes and no voice recording. */
internal object HuTestSignal {
    enum class Route { SYSTEM, SPEAKER, BLUETOOTH, PCM_MUSIC, PCM_TTS, PCM_VR, PCM_TALKIE }
    data class Result(val sent: Boolean, val device: Int? = null, val frames: Int = 0)
    fun pcm(rate: Int, channels: Int, firstFrame: Int, frames: Int): ShortArray {
        require(rate in listOf(16000,48000) && channels in 1..2 && firstFrame >= 0 && frames in 1..rate*3)
        return ShortArray(frames*channels) { i ->
            val frame = firstFrame+i/channels
            val section = frame/(rate*3/4)
            val t = frame%(rate*3/4)
            val ch = i%channels
            val sound = section in 0..2 && t < rate/2 && (channels == 1 || section == 2 || ch == section)
            if (!sound) 0 else {
                val ramp = minOf(1.0,t/(rate*.02),(rate/2-t)/(rate*.02))
                (2600*ramp*sin(2*PI*(440+220*section)*frame/rate)).toInt().toShort()
            }
        }
    }
    suspend fun play(context: Context, session: String, route: Route): Result {
        if (!HuDiagnosticTools.ready(session)) return Result(false)
        val logger = DiagnosticLogger(context,"hu-test-signal")
        val remoteStream = when(route) {
            Route.PCM_MUSIC -> CarbitAudioTransport.Stream.MUSIC
            Route.PCM_TTS -> CarbitAudioTransport.Stream.TTS
            Route.PCM_VR -> CarbitAudioTransport.Stream.VR
            Route.PCM_TALKIE -> CarbitAudioTransport.Stream.TALKIE
            else -> null
        }
        val result = if (remoteStream != null) {
            val transport = HuDiagnosticTools.audio(session) ?: return Result(false)
            val stream = remoteStream
            if (!transport.diagnosticAvailable(stream)) return Result(false)
            var sent = 0
            try {
                transport.startStream(stream)
                repeat(25) {
                    currentCoroutineContext().ensureActive()
                    if (!HuDiagnosticTools.ready(session)) return Result(false)
                    if (!transport.diagnosticStarted(stream)) delay(40)
                }
                if (!transport.diagnosticStarted(stream)) return Result(false)
                val chunk = stream.rate/50
                repeat(112) { index ->
                    currentCoroutineContext().ensureActive()
                    if (!HuDiagnosticTools.ready(session)) return Result(false,frames=sent)
                    val samples = pcm(stream.rate,stream.channels,index*chunk,chunk)
                    val bytes = ByteArray(samples.size*2)
                    samples.forEachIndexed { i,s -> bytes[i*2] = s.toByte(); bytes[i*2+1] = (s.toInt() shr 8).toByte() }
                    if (!transport.play(stream,bytes)) return Result(false,frames=sent)
                    sent += chunk
                    delay(20)
                }
                Result(true,frames=sent)
            } finally { transport.stopStream(stream) }
        } else {
            val manager = context.getSystemService(AudioManager::class.java) ?: return Result(false)
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val desired = when(route) {
                Route.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return Result(false)
                Route.BLUETOOTH -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?: return Result(false)
                else -> null
            }
            val samples = pcm(48000,2,0,108000)
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build()
            try {
                if (track.state != AudioTrack.STATE_INITIALIZED && track.state != AudioTrack.STATE_NO_STATIC_DATA) return Result(false)
                if (desired != null && !track.setPreferredDevice(desired)) return Result(false)
                if (track.write(samples,0,samples.size) != samples.size) return Result(false)
                track.play()
                var device: Int? = null
                repeat(23) {
                    if (!HuDiagnosticTools.ready(session)) return Result(false,device)
                    delay(100)
                    device = track.routedDevice?.type ?: device
                }
                Result(desired == null || device == desired.type,device,samples.size/2)
            } finally { runCatching { track.stop() }; track.release() }
        }
        logger.log("Test signal route=$route sent=${result.sent} device=${result.device} frames=${result.frames}")
        return result
    }
}
