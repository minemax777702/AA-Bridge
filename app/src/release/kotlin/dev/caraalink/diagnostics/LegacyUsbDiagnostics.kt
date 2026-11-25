package dev.caraalink.diagnostics

import android.content.Context
import android.hardware.usb.UsbAccessory

@Suppress("UNUSED_PARAMETER")
internal class LegacyUsbDiagnostics(context: Context) {
    fun startSniffer(accessory: UsbAccessory) = Unit
    fun startProbe(accessory: UsbAccessory) = Unit
    fun stop() = Unit
}
