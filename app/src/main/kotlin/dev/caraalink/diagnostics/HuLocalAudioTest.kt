package dev.caraalink.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** Tests the Android media path, not the PXC protocol. Never changes global volume or routing. */
internal object HuLocalAudioTest {
    private val bluetoothTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST
    )
    fun bluetoothOutputs(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in bluetoothTypes }

    fun run(context: Context, bluetooth: Boolean, keepGoing: () -> Boolean): JSONObject {
        val out = JSONObject().put("requested", if (bluetooth) "bluetooth-media" else "android-default-no-bt")
        var track: AudioTrack? = null
        try {
            val devices = bluetoothOutputs(context)
            if ((!bluetooth && devices.isNotEmpty()) || (bluetooth && devices.size != 1))
                return out.put("status", "prerequisite-not-met")
            val rate = 48000
            val samples = ShortArray(rate * 2) { n ->
                val envelope = minOf(1.0, n / 2400.0, (rate * 2 - 1 - n) / 2400.0)
                (sin(2 * PI * 440 * n / rate) * 650 * envelope).toInt().toShort()
            }
            val audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            track = audio
            check(audio.state == AudioTrack.STATE_INITIALIZED)
            if (bluetooth) {
                out.put("preferredType", devices.single().type)
                if (!audio.setPreferredDevice(devices.single())) return out.put("status", "route-request-rejected")
            }
            if (audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) != samples.size)
                return out.put("status", "write-failed")
            if (!keepGoing()) return out.put("status", "cancelled")
            audio.play()
            val routes = linkedSetOf<Int>()
            val end = SystemClock.elapsedRealtime() + 2200
            while (keepGoing() && SystemClock.elapsedRealtime() < end) {
                audio.routedDevice?.let { routes.add(it.type) }
                Thread.sleep(40)
            }
            val verified = routes.isNotEmpty() && routes.all { (it in bluetoothTypes) == bluetooth }
            return out.put("observedRouteTypes", org.json.JSONArray(routes.toList()))
                .put("routeVerified", verified).put("status", if (keepGoing()) "played" else "cancelled")
        } catch (e: Exception) {
            return out.put("status", "error").put("error", e.javaClass.simpleName)
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }
}
