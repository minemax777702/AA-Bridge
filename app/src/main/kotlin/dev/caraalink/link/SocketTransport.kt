package dev.caraalink.link

import android.os.Process
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

internal class EmulatorLanSession(val endpoint: EmulatorWifi.Endpoint, val key: ByteArray)

/** What [CarSession] needs from a link to the car, USB or WiFi. */
interface CarTransport {
    fun stop(reason: String = "stopped")
}

/**
 * Over Wi-Fi Direct the car connects to plain TCP ports on the phone, with the same framing as the ADB streams.
 * Callbacks run under one lock so the channels see the single-threaded order AdbTransport gives them.
 */
class SocketTransport internal constructor(
    private val logger: DiagnosticLogger,
    private val handlerFor: (service: String) -> AdbTransport.StreamHandler?,
    private val onClosed: (reason: String) -> Unit,
    private val emulatorMode: Boolean = false,
    private val emulatorLan: EmulatorLanSession? = null,
) : CarTransport {

    private val running = AtomicBoolean(false)
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val streams = CopyOnWriteArrayList<SocketStream>()
    private val nextId = AtomicInteger(1)
    private val dispatch = Any()
    private val authenticating = CopyOnWriteArrayList<Socket>()
    private val peerLock = Any()
    private var emulatorPeer: java.net.InetAddress? = null
    private val emulatorPorts = mutableMapOf<Int, Int>()

    fun start() {
        check(!emulatorMode || dev.caraalink.BuildConfig.DEBUG)
        check(emulatorLan == null || emulatorMode)
        if (!running.compareAndSet(false, true)) return
        for (port in PORTS) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(when {
                        emulatorLan != null -> InetSocketAddress(emulatorLan.endpoint.address, port)
                        emulatorMode -> InetSocketAddress("127.0.0.1", port)
                        else -> InetSocketAddress(port)
                    })
                }
            } catch (e: IOException) {
                logger.log("listen on $port failed: ${e.message}")
                continue
            }
            servers += server
            thread(name = "SocketTransport-accept-$port", isDaemon = true) { acceptLoop(server, port) }
        }
        if (emulatorMode && servers.size != PORTS.size) {
            stop("HU emulator ports unavailable")
            return
        }
        listening = servers.isNotEmpty()
        logger.log("listening on ${servers.joinToString { it.localPort.toString() }}")
    }

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        listening = false
        logger.log("stopping: $reason")
        servers.forEach { runCatching { it.close() } }
        authenticating.forEach { runCatching { it.close() } }
        streams.forEach { it.close() }
        onClosed(reason)
    }

    private fun acceptLoop(server: ServerSocket, port: Int) {
        while (running.get()) {
            val socket = try { server.accept() } catch (e: IOException) { break }
            val iface = runCatching { java.net.NetworkInterface.getByInetAddress(socket.localAddress)?.name }.getOrNull()
            val allowed = when {
                emulatorLan != null -> socket.localAddress == emulatorLan.endpoint.address &&
                    emulatorLan.endpoint.contains(socket.inetAddress)
                emulatorMode -> socket.inetAddress.isLoopbackAddress && socket.localAddress.isLoopbackAddress
                else -> acceptsInterface(iface)
            }
            if (!allowed) {
                logger.log("refused tcp:$port from ${socket.remoteSocketAddress} on $iface")
                runCatching { socket.close() }
                continue
            }
            socket.tcpNoDelay = true
            if (emulatorLan != null) {
                authenticating.add(socket)
                val authenticated = try {
                    socket.soTimeout = 3000
                    dev.caraalink.protocol.EmulatorLanAuth.accept(
                        socket.getInputStream(), socket.getOutputStream(), emulatorLan.key, port
                    ) { reserveEmulatorPort(socket.inetAddress, port) }
                } catch (_: Exception) {
                    false
                } finally {
                    authenticating.remove(socket)
                }
                if (!authenticated || !running.get()) {
                    runCatching { socket.close() }
                    continue
                }
                socket.soTimeout = 0
            }
            val handler = handlerFor("tcp:$port")
            logger.log("car connected to tcp:$port from ${socket.remoteSocketAddress}")
            accepted++
            if (handler == null) {
                runCatching { socket.close() }
                continue
            }
            val stream = SocketStream(nextId.getAndIncrement(), socket)
            streams += stream
            if (!running.get()) {
                stream.close()
                streams -= stream
                continue
            }
            thread(name = "SocketTransport-read-$port", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                readLoop(stream, handler, port)
            }
        }
    }

    // Only the first authenticated peer can open this session's two PXC
    // sockets and one control/video socket each. Reconnect requires a new code.
    private fun reserveEmulatorPort(peer: java.net.InetAddress, port: Int): Boolean = synchronized(peerLock) {
        if (!running.get() || (emulatorPeer != null && emulatorPeer != peer)) return@synchronized false
        val count = emulatorPorts[port] ?: 0
        if (count >= if (port == PXC_PORT) 2 else 1) return@synchronized false
        emulatorPeer = peer
        emulatorPorts[port] = count + 1
        true
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
                if (!emulatorMode && reads++ < 2) logger.logBytes("RX tcp:$port", buf, n)
                synchronized(dispatch) { handler.onData(stream, buf.copyOf(n)) }
            }
        } catch (e: IOException) {
            reason = "read failed on tcp:$port: ${e.message}"
        }
        logger.log(reason)
        stream.close()
        streams -= stream
        synchronized(dispatch) { handler.onClose(stream) }
        if (port == PXC_PORT || emulatorMode) stop(reason)
    }

    inner class SocketStream(override val localId: Int, val socket: Socket) : MirrorStream {
        private val queue = LinkedBlockingQueue<Queued>()
        @Volatile override var closed = false
            private set

        private val writer = thread(name = "SocketTransport-write-$localId", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val out = socket.getOutputStream()
                while (!closed) {
                    out.write(queue.take().bytes)
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
