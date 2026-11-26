package dev.caraalink.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.RawHuDump
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Thin wrapper over [UsbManager.openAccessory] — opens the fd and exposes it
 * as raw streams. CONFIRMED by RE pass 1 (claude/roadmap.md): the phone is
 * the accessory here, matching how the real CarbitLink app's
 * EPSDK.openAccessory() uses this exact API.
 */
class UsbAccessoryConnection private constructor(
    private val pfd: ParcelFileDescriptor,
    val accessory: UsbAccessory,
    val input: FileInputStream,
    val output: FileOutputStream,
    private val traceId: String?,
) : AutoCloseable {

    // Streams built from a FileDescriptor don't own it, so closing them alone
    // leaves the accessory open until GC -- which kept Android from showing
    // the app chooser on the next plug-in.
    override fun close() {
        traceId?.let { RawHuDump.meta(it, "close") }
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd.close() }
    }

    private class TraceInput(fd: FileDescriptor, private val id: String) : FileInputStream(fd) {
        override fun read(): Int {
            val n = super.read()
            if (n >= 0 && RawHuDump.active) RawHuDump.record(id, RawHuDump.RX, byteArrayOf(n.toByte()))
            return n
        }
        override fun read(b: ByteArray): Int = read(b, 0, b.size)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) RawHuDump.record(id, RawHuDump.RX, b, off, n)
            return n
        }
    }

    private class TraceOutput(fd: FileDescriptor, private val id: String) : FileOutputStream(fd) {
        override fun write(b: Int) {
            super.write(b)
            if (RawHuDump.active) RawHuDump.record(id, RawHuDump.TX, byteArrayOf(b.toByte()))
        }
        override fun write(b: ByteArray) = write(b, 0, b.size)
        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            RawHuDump.record(id, RawHuDump.TX, b, off, len)
        }
    }

    companion object {
        private const val TAG = "UsbAccessoryConnection"

        /**
         * Crash seen on real hardware (claude/roadmap.md): UsbManager.openAccessory()
         * THROWS IllegalArgumentException("no accessory attached") -- it does not
         * just return null -- when the accessory has been physically detached
         * since the caller last saw it (e.g. a stale reference reused by a
         * reconnect retry). Catch broadly here so a vanished accessory is a
         * null return, not an uncaught crash.
         */
        fun open(context: Context, accessory: UsbAccessory): UsbAccessoryConnection? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val pfd = try {
                usbManager.openAccessory(accessory)
            } catch (e: Exception) {
                Log.w(TAG, "openAccessory threw -- treating as not attached", e)
                null
            } ?: return null
            val descriptor = pfd.fileDescriptor
            if (!BuildConfig.HU_DIAGNOSTICS) {
                return UsbAccessoryConnection(pfd, accessory, FileInputStream(descriptor), FileOutputStream(descriptor), null)
            }
            val id = RawHuDump.streamId("usb-aoa")
            RawHuDump.meta(id, "open manufacturer=${accessory.manufacturer} model=${accessory.model} version=${accessory.version}")
            return UsbAccessoryConnection(pfd, accessory, TraceInput(descriptor, id), TraceOutput(descriptor, id), id)
        }
    }
}
