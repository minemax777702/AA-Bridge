package dev.caraalink.aa

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings

/** Explicit playback device selection, refreshed during playback when Bluetooth changes. */
internal class AaOutputRoute(
    private val context: Context,
    private val preview: Boolean,
    private val logger: DiagnosticLogger,
) {
    private var lastCheck = -1L
    private var selectedId: Int? = null

    fun refresh(track: AudioTrack, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck >= 0 && now - lastCheck < 1000) return
        lastCheck = now
        runCatching {
            val manager = context.getSystemService(AudioManager::class.java) ?: return
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val preferBluetooth = !preview && AppSettings.aaAudioRoute(context) != AppSettings.AaAudioRoute.DEVICE_ONLY
            val bluetooth = if (preferBluetooth) devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            } else null
            val device = bluetooth ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (force || device?.id != selectedId) {
                val accepted = track.setPreferredDevice(device)
                selectedId = if (accepted) device?.id else null
                logger.log("AA audio preferred type=${device?.type} accepted=$accepted actual=${track.routedDevice?.type}")
            }
        }.onFailure { logger.log("AA audio routing failed: $it") }
    }
}
