package dev.caraalink.diagnostics

import android.content.Context
import android.hardware.usb.UsbAccessory

internal class LegacyUsbDiagnostics(private val context: Context) {
    private var sniffer: UsbSnifferDiagnostic? = null
    private var probe: AdbHandshakeProbe? = null

    fun startSniffer(accessory: UsbAccessory) {
        stop()
        sniffer = UsbSnifferDiagnostic(context).also { it.start(accessory) }
    }

    fun startProbe(accessory: UsbAccessory) {
        stop()
        probe = AdbHandshakeProbe(context).also { it.start(accessory) }
    }

    fun stop() {
        sniffer?.stop()
        probe?.stop()
        sniffer = null
        probe = null
    }
}
