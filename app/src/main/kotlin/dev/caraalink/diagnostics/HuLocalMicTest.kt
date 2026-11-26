package dev.caraalink.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Isolated microphone test: measures PCM and routed input, never retains local PCM. */
internal object HuLocalMicTest {
    private val btTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    fun run(context: Context, bluetooth: Boolean, keepGoing: () -> Boolean): JSONObject {
        val out = JSONObject().put("requested", if (bluetooth) "bluetooth-input" else "phone-input")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return out.put("status", "permission-required")
        var recorder: AudioRecord? = null
        try {
            val devices = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            val candidates = devices.filter { if (bluetooth) it.type in btTypes else it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (candidates.isEmpty()) return out.put("status", "input-not-present")
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return out.put("status", "buffer-unavailable")
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, 6400)).build()
            recorder = input
            if (input.state != AudioRecord.STATE_INITIALIZED) return out.put("status", "not-initialized")
            if (!input.setPreferredDevice(candidates.first())) return out.put("status", "route-request-rejected")
            if (!keepGoing()) return out.put("status", "cancelled")
            input.startRecording()
            val samples = ShortArray(320)
            val routes = linkedSetOf<Int>()
            var count = 0L
            var peak = 0
            var squares = 0.0
            val end = SystemClock.elapsedRealtime() + 3000
            while (keepGoing() && SystemClock.elapsedRealtime() < end) {
                input.routedDevice?.let { routes.add(it.type) }
                val n = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) return out.put("status", "read-error").put("code", n)
                for (i in 0 until n) {
                    val v = samples[i].toInt()
                    peak = maxOf(peak, abs(v))
                    squares += v.toDouble() * v
                }
                count += n
                if (n == 0) Thread.sleep(10)
            }
            return out.put("status", if (!keepGoing()) "cancelled" else if (count > 0) "pcm-received" else "no-pcm")
                .put("routeVerified", routes.isNotEmpty() && routes.all {
                    if (bluetooth) it in btTypes else it == AudioDeviceInfo.TYPE_BUILTIN_MIC
                }).put("observedRouteTypes", JSONArray(routes.toList()))
                .put("samples", count).put("peak", peak).put("rms", if (count > 0) sqrt(squares / count) else 0.0)
        } catch (e: Exception) {
            return out.put("status", "error").put("error", e.javaClass.simpleName)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }
}
