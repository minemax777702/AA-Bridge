package dev.caraalink.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import dev.caraalink.BuildConfig
import dev.caraalink.R
import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.link.EmulatorWifi
import dev.caraalink.protocol.EmulatorLanAuth
import dev.caraalink.service.UsbLinkForegroundService

/** Shared explicit entry point from setup, Home and advanced settings. */
internal object HuEmulatorDialog {
    fun show(context: Context) {
        if (!BuildConfig.DEBUG) return
        val snapshot = ConnectionStateHolder.snapshot
        if (snapshot.emulatorActive) {
            AlertDialog.Builder(context)
                .setTitle(R.string.pref_hu_emulator_title)
                .setMessage(R.string.pref_hu_emulator_already_running)
                .setPositiveButton(R.string.home_disconnect) { _, _ -> stop(context) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (snapshot.link != CarLink.NONE) {
            Toast.makeText(context, R.string.pref_hu_emulator_disconnect_car, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.pref_hu_emulator_title)
            .setItems(arrayOf(context.getString(R.string.pref_hu_emulator_lan), context.getString(R.string.pref_hu_emulator_adb))) { _, index ->
                if (index == 0) showLan(context) else AlertDialog.Builder(context)
                    .setMessage(R.string.pref_hu_emulator_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> start(context) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showLan(context: Context) {
        val endpoint = EmulatorWifi.current(context)
        if (endpoint == null) {
            Toast.makeText(context, R.string.pref_hu_emulator_no_wifi, Toast.LENGTH_LONG).show()
            return
        }
        val ip = endpoint.address.hostAddress ?: return
        val code = EmulatorLanAuth.newCode()
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.pref_hu_emulator_lan)
            .setMessage(context.getString(R.string.pref_hu_emulator_lan_details, ip, code))
            .setPositiveButton(R.string.pref_hu_emulator_start, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                start(context, ip, code)
                it.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = context.getString(R.string.action_close)
            }
        }
        dialog.show()
    }

    private fun start(context: Context, ip: String? = null, code: String? = null) {
        val intent = Intent(context, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_EMULATOR)
        if (ip != null && code != null) {
            intent.putExtra(UsbLinkForegroundService.EXTRA_EMULATOR_IP, ip)
            intent.putExtra(UsbLinkForegroundService.EXTRA_EMULATOR_CODE, code)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_STOP))
    }
}
