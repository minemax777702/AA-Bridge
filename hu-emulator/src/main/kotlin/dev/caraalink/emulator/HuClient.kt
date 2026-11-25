package dev.caraalink.emulator

import android.content.Context
import android.view.Surface
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class HuClient(
    private val context: Context,
    private val host: String,
    private val config: Config,
    private val surface: Surface,
    private val log: (String) -> Unit,
    private val ended: () -> Unit,
) : AutoCloseable {
    data class Config(val width: Int, val height: Int, val music: Boolean,
        val tts: Boolean, val vr: Boolean, val talkie: Boolean, val mic: Boolean,
        val ackDelayMs: Long = 0, val dropAudioAck: Boolean = false)

    private class Link(val socket: Socket) : AutoCloseable {
        val input = DataInputStream(socket.getInputStream())
        @Synchronized fun write(bytes: ByteArray) { socket.getOutputStream().write(bytes) }
        override fun close() { runCatching { socket.close() } }
    }
    private val links = CopyOnWriteArrayList<Link>()
    private val running = AtomicBoolean(true)
    private val touchQueue = ArrayBlockingQueue<ByteArray>(256)
    private val outputs = mutableMapOf<Int, PcmOutput>()
    private var mic: MicInput? = null
    @Volatile private var ctrl: Link? = null

    fun start() = worker("connect") {
        val c2p = connect(Wire.PXC)
        val p2c = connect(Wire.PXC)
        request(c2p, 0x10000)
        request(p2c, 0x20000)
        val info = JSONObject().put("HUName", "HU Emulator").put("carBrand", "HU Emulator")
            .put("HUID", "aa-bridge-hu-emulator").put("supportScreenMirroring", true)
            .put("supportScreenTouch", true).put("supportMic", config.mic)
        request(c2p, 0x10010, info.toString().toByteArray())
        val caps = JSONObject().put("music", config.music).put("tts", config.tts)
            .put("vr", config.vr).put("talkie", config.talkie).put("autoChangeToBT", false)
        request(c2p, 0x10020, caps.toString().toByteArray())
        worker("c2p-read") { while (running.get()) Wire.readPxc(c2p.input) }
        worker("p2c-read") { while (running.get()) handleP2c(p2c, c2p, Wire.readPxc(p2c.input)) }
        val control = connect(Wire.CONTROL)
        control.write(Wire.mirror(0x30))
        check(Wire.readMirror(control.input).command == 0x31)
        control.write(Wire.mirror(0x10, Wire.capture(config.width, config.height)))
        val reply = Wire.readMirror(control.input)
        check(reply.command == 0x11 && reply.body.size >= 8) { "Capture configuration rejected" }
        val format = Wire.little(reply.body)
        check(format.int == 2) { "Expected H.264" }
        val width = format.short.toInt() and 65535
        val height = format.short.toInt() and 65535
        check(width in 16..2560 && height in 16..1600)
        control.write(Wire.mirror(0x70))
        check(Wire.readMirror(control.input).command == 0x71) { "Video start rejected" }
        val video = connect(Wire.VIDEO)
        ctrl = control
        log("Connected: H.264 ${width}×${height}; $caps")
        worker("control-read") { while (running.get()) Wire.readMirror(control.input) }
        worker("control-write") {
            while (running.get()) {
                val message = touchQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                control.write(message ?: Wire.mirror(0x40))
            }
        }
        VideoDecoder(width, height, surface).use { decoder ->
            while (running.get()) decoder.feed(Wire.readFrame(video.input))
        }
    }

    fun touch(event: Int, x: Int, y: Int, slot: Int, key: Int = 0) {
        if (!running.get() || ctrl == null) return
        if (!touchQueue.offer(Wire.touch(event, x, y, slot, key))) {
            log("Input queue full; disconnecting rather than losing contact transitions")
            thread(isDaemon = true) { close() }
        }
    }

    private fun connect(port: Int): Link {
        check(running.get())
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            socket.soTimeout = 15000
            val link = Link(socket)
            links.add(link)
            if (!running.get()) { link.close(); error("Session closed") }
            return link
        } catch (e: Exception) { socket.close(); throw e }
    }

    private fun request(link: Link, command: Int, body: ByteArray = ByteArray(0)) {
        link.write(Wire.pxc(command, body))
        check(Wire.readPxc(link.input).command == command + 1) { "PXC handshake rejected: ${command.toString(16)}" }
    }

    private fun ack(link: Link, packet: Wire.Packet) {
        if (config.dropAudioAck && packet.command in setOf(0x20090, 0x200D0, 0x200F0, 0x20100)) {
            log("Simulated lost ACK: ${packet.command.toString(16)}")
            return
        }
        if (config.ackDelayMs > 0 && packet.command in setOf(0x20090, 0x200D0, 0x200F0, 0x20100)) {
            Thread.sleep(config.ackDelayMs)
        }
        if (running.get()) link.write(Wire.pxc(packet.command + 1, extra = packet.extra))
    }

    private fun handleP2c(p2c: Link, c2p: Link, packet: Wire.Packet) {
        if (packet.command == 0x70000000) { ack(p2c, packet); return }
        if (packet.command in 0x60000000..0x60000030 && packet.command % 16 == 0) {
            val type = (packet.command - 0x60000000) / 16 + 1
            synchronized(outputs) { outputs[type]?.feed(packet.body) }
            return
        }
        log("PXC 0x${packet.command.toString(16)} · ${packet.body.size} B")
        when (packet.command) {
            0x20090 -> {
                val body = JSONObject(String(packet.body))
                val type = body.getInt("type")
                val allowed = when (type) { 1 -> config.tts; 2 -> config.vr; 3 -> config.talkie; 4 -> config.music; else -> false }
                if (!allowed || config.dropAudioAck) return
                val rate = body.getInt("sampleRate")
                val channels = body.getInt("channel")
                require(body.getInt("format") == 3 && rate in setOf(16000, 48000) && channels in 1..2)
                synchronized(outputs) {
                    if (!running.get()) return
                    outputs.remove(type)?.close()
                    outputs[type] = PcmOutput(rate, channels, type != 4)
                }
                ack(p2c, packet)
            }
            0x200D0 -> {
                synchronized(outputs) { outputs.remove(JSONObject(String(packet.body)).getInt("type"))?.close() }
                ack(p2c, packet)
            }
            0x200F0 -> {
                if (!config.mic || config.dropAudioAck) return
                val body = JSONObject(String(packet.body))
                require(body.getInt("sampleRate") == 16000 && body.getInt("channel") == 1 && body.getInt("format") == 3)
                synchronized(this) {
                    if (!running.get()) return
                    mic?.close()
                    // Send ACK before PCM; first packet may be discarded by the bridge if it races the ACK.
                    ack(p2c, packet)
                    if (running.get()) mic = MicInput(context) { pcm ->
                        if (running.get()) c2p.write(Wire.pxc(0x10060, pcm))
                    }
                }
            }
            0x20100 -> { synchronized(this) { mic?.close(); mic = null }; ack(p2c, packet) }
            else -> ack(p2c, packet)
        }
    }

    private fun worker(name: String, work: () -> Unit) = thread(name = "HU-$name", isDaemon = true) {
        try { work() } catch (e: Exception) {
            if (running.get()) log("$name: ${e.message}")
        } finally { close() }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        links.forEach { it.close() }
        synchronized(this) { mic?.close(); mic = null }
        synchronized(outputs) { outputs.values.forEach { it.close() }; outputs.clear() }
        touchQueue.clear()
        ended()
    }
}
