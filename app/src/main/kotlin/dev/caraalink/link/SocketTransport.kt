package dev.caraalink.link

import android.os.Process
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.RawHuDump
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What [CarSession] needs from a link to the car, USB or WiFi. */
interface CarTransport {
    fun stop(reason: String = "stopped")
}

/**
 * Over Wi-Fi Direct the car connects to plain TCP ports on the phone, with the same framing as the ADB streams.
 * Callbacks run under one lock so the channels see the single-threaded order AdbTransport gives them.
 */
class SocketTransport(
    private val logger: DiagnosticLogger,
    private val handlerFor: (service: String) -> AdbTransport.StreamHandler?,
    private val onClosed: (reason: String) -> Unit,
) : CarTransport {

    private val running = AtomicBoolean(false)
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val streams = CopyOnWriteArrayList<SocketStream>()
    private val nextId = AtomicInteger(1)
    private val dispatch = Any()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ports = if (BuildConfig.HU_DIAGNOSTICS) PORTS + InstrumentWire.PORT else PORTS
        for (port in ports) {
            val server = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
            } catch (e: IOException) {
                logger.log("listen on $port failed: ${e.message}")
                continue
            }
            servers += server
            thread(name = "SocketTransport-accept-$port", isDaemon = true) { acceptLoop(server, port) }
        }
        listening = servers.isNotEmpty()
        logger.log("listening on ${servers.joinToString { it.localPort.toString() }}")
    }

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        listening = false
        logger.log("stopping: $reason")
        servers.forEach { runCatching { it.close() } }
        streams.forEach { it.close() }
        onClosed(reason)
    }

    private fun acceptLoop(server: ServerSocket, port: Int) {
        while (running.get()) {
            val socket = try { server.accept() } catch (e: IOException) { break }
            val iface = runCatching { java.net.NetworkInterface.getByInetAddress(socket.localAddress)?.name }.getOrNull()
            if (!acceptsInterface(iface)) {
                logger.log("refused tcp:$port from ${socket.remoteSocketAddress} on $iface")
                runCatching { socket.close() }
                continue
            }
            if (port == InstrumentWire.PORT && streams.count { it.socket.localPort == port } >= 4) {
                runCatching { socket.close() }; continue
            }
            if (port == InstrumentWire.PORT) socket.soTimeout = 15000
            socket.tcpNoDelay = true
            val handler = handlerFor("tcp:$port")
            logger.log("car connected to tcp:$port from ${socket.remoteSocketAddress}")
            if (port != InstrumentWire.PORT) accepted++
            if (handler == null) {
                runCatching { socket.close() }
                continue
            }
            val stream = SocketStream(nextId.getAndIncrement(), socket)
            streams += stream
            thread(name = "SocketTransport-read-$port", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                readLoop(stream, handler, port)
            }
        }
    }

    private fun readLoop(stream: SocketStream, handler: AdbTransport.StreamHandler, port: Int) {
        synchronized(dispatch) { handler.onOpen(stream) }
        val buf = ByteArray(64 * 1024)
        var reads = 0
        var reason = "car closed tcp:$port"
        try {
            val input = stream.socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                stream.traceId?.let { RawHuDump.record(it, RawHuDump.RX, buf, 0, n) }
                if (reads++ < 2) logger.logBytes("RX tcp:$port", buf, n)
                synchronized(dispatch) { handler.onData(stream, buf.copyOf(n)) }
            }
        } catch (e: IOException) {
            reason = "read failed on tcp:$port: ${e.message}"
        }
        logger.log(reason)
        stream.close()
        streams -= stream
        synchronized(dispatch) { handler.onClose(stream) }
        if (port == PXC_PORT) stop(reason)
    }

    inner class SocketStream(override val localId: Int, val socket: Socket) : MirrorStream {
        val traceId = if (BuildConfig.HU_DIAGNOSTICS) RawHuDump.streamId("wifi-tcp/${socket.localPort}") else null
        init { traceId?.let { RawHuDump.meta(it, "open local=${socket.localSocketAddress} remote=${socket.remoteSocketAddress}") } }
        private val queue = LinkedBlockingQueue<Queued>()
        @Volatile override var closed = false
            private set

        private val writer = thread(name = "SocketTransport-write-$localId", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    val bytes = queue.take().bytes
                    out.write(bytes)
                    traceId?.let { RawHuDump.record(it, RawHuDump.TX, bytes) }
                }
            } catch (_: InterruptedException) {
            } catch (e: IOException) {
                logger.log("write failed on stream $localId: ${e.message}")
                close()
            }
        }

        override fun write(data: ByteArray) {
            if (closed) return
            queue.offer(Queued(data))
        }

        override fun backlogAgeMs() = queue.peek()?.ageMs() ?: 0L

        override fun clearBacklog() = queue.clear()

        fun close() {
            if (closed) return
            closed = true
            traceId?.let { RawHuDump.meta(it, "close") }
            writer.interrupt()
            runCatching { socket.close() }
        }
    }

    companion object {
        /** The car only reaches us over Wi-Fi Direct; anyone on the phone's other networks is refused. */
        internal fun acceptsInterface(name: String?): Boolean = name?.startsWith("p2p") == true

        const val PXC_PORT = 10922
        val PORTS = listOf(PXC_PORT, 10921, 10920)

        @Volatile
        var listening = false
            private set

        /** Connections the car opened; the hello fallback waits on it. */
        @Volatile
        var accepted = 0
    }
}
