package dev.caraalink.link

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Experimental PXC PCM transport. One outstanding playback control command:
 * the SDK's start/stop replies identify the command, not the audio type.
 * Wire facts and remaining assumptions: docs/CarbitLink-Protocol.md.
 */
internal class CarbitAudioTransport(
    private val send: (Int, ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Stream(val wireType: Int, val command: Int, val rate: Int, val channels: Int) {
        TTS(1, 0x60000000, 16_000, 1),
        VR(2, 0x60000010, 16_000, 1),
        TALKIE(3, 0x60000020, 16_000, 1),
        MUSIC(4, 0x60000030, 48_000, 2);

        fun startBody() = """{"type":$wireType,"sampleRate":$rate,"channel":$channels,"format":3}""".toByteArray(Charsets.UTF_8)
        fun stopBody() = """{"type":$wireType}""".toByteArray(Charsets.UTF_8)
    }

    private data class Pending(val stream: Stream, val starting: Boolean, val deadline: Long)
    private enum class MicState { IDLE, STARTING, ACTIVE, STOPPING, FAILED }

    private var allowed = emptySet<Stream>()
    private val active = mutableSetOf<Stream>()
    private val stopping = linkedSetOf<Stream>()
    private var pending: Pending? = null
    private var playbackFailed = false
    // One opt-in diagnostic request at a time. Never changes advertised capabilities.
    private var probe: Stream? = null
    private var probePhase = "idle"
    private var probeDeadline = 0L

    @Synchronized fun beginDiagnosticProbe(stream: Stream): Boolean {
        expireRequests()
        if (probe != null || playbackFailed || pending != null || active.isNotEmpty() ||
            stopping.isNotEmpty() || micState != MicState.IDLE) return false
        probe = stream
        probePhase = "starting"
        probeDeadline = nowMs() + ACK_TIMEOUT_MS
        if (!send(START, stream.startBody())) {
            probe = null; probePhase = "send-failed"
            return false
        }
        return true
    }

    @Synchronized fun diagnosticStatus(): String {
        if (probe != null && probePhase in listOf("starting", "stopping") && nowMs() >= probeDeadline) {
            val stream = probe!!
            if (probePhase == "starting") send(STOP, stream.stopBody())
            probe = null
            probePhase = "timeout-reconnect"
            playbackFailed = true // Late ACK cannot be correlated safely.
        }
        return probePhase
    }

    @Synchronized fun diagnosticFrame(pcm: ByteArray): Boolean {
        val stream = probe ?: return false
        if (diagnosticStatus() != "active" || pcm.isEmpty() || pcm.size > MAX_PCM_BYTES ||
            pcm.size % (stream.channels * 2) != 0) return false
        return send(stream.command, pcm)
    }

    @Synchronized fun endDiagnosticProbe() {
        val stream = probe ?: return
        if (probePhase == "stopping") return
        val wasStarting = probePhase == "starting"
        probePhase = "stopping"
        probeDeadline = nowMs() + ACK_TIMEOUT_MS
        if (!send(STOP, stream.stopBody()) || wasStarting) {
            probe = null; probePhase = "cancelled-reconnect"; playbackFailed = true
        }
    }

    private var micAllowed = false
    private var micState = MicState.IDLE
    private var micDeadline = 0L
    private val micFrames = ArrayBlockingQueue<ByteArray>(8)

    @Synchronized
    fun updateCapabilities(streams: Set<Stream>) {
        allowed = streams.toSet()
        (active + listOfNotNull(pending?.takeIf { it.starting }?.stream))
            .filter { it !in allowed }.forEach { stopStream(it) }
        log("Carbit audio: enabled=$allowed")
    }

    @Synchronized
    fun updateMusicAllowed(enabled: Boolean) =
        updateCapabilities(if (enabled) allowed + Stream.MUSIC else allowed - Stream.MUSIC)

    @Synchronized
    fun updateMicAllowed(enabled: Boolean) {
        micAllowed = enabled
        if (!enabled) stopMic()
        log("Carbit audio: supportMic=$enabled")
    }

    @Synchronized
    fun startStream(stream: Stream) {
        expireRequests()
        if (probe != null || playbackFailed || stream !in allowed || stream in active || stream in stopping ||
            pending != null || micState == MicState.STARTING || micState == MicState.STOPPING) return
        pending = Pending(stream, true, nowMs() + ACK_TIMEOUT_MS)
        if (!send(START, stream.startBody())) pending = null
    }

    @Synchronized
    fun play(stream: Stream, pcm: ByteArray): Boolean {
        if (probe != null || stream !in allowed || pcm.isEmpty() || pcm.size > MAX_PCM_BYTES ||
            pcm.size % (stream.channels * 2) != 0) return false
        expireRequests()
        if (playbackFailed || stream in stopping) return false
        if (stream !in active) {
            startStream(stream)
            return false
        }
        if (send(stream.command, pcm)) return true
        stopStream(stream)
        log("Carbit audio: $stream congestion/disconnect, local fallback")
        return false
    }

    fun playMusic(pcm: ByteArray) = play(Stream.MUSIC, pcm)

    @Synchronized
    fun onReply(command: Int) {
        expireRequests()
        if (probe != null && command in listOf(START_ACK, STOP_ACK)) {
            if (diagnosticStatus() == "starting" && command == START_ACK) probePhase = "active"
            else if (probePhase == "stopping" && command == STOP_ACK) {
                probe = null; probePhase = "stopped"
            }
            return
        }
        when (command) {
            START_ACK, STOP_ACK -> {
                val request = pending ?: return
                if (command != if (request.starting) START_ACK else STOP_ACK) return
                pending = null
                if (request.starting) {
                    active.add(request.stream)
                    log("Carbit audio: ${request.stream} start acknowledged")
                } else {
                    active.remove(request.stream)
                    stopping.remove(request.stream)
                }
                pumpStops()
            }
            MIC_START_ACK -> if (micState == MicState.STARTING) {
                micState = MicState.ACTIVE
                log("Carbit audio: microphone start acknowledged")
                pumpStops()
            }
            MIC_STOP_ACK -> if (micState == MicState.STOPPING) {
                micState = MicState.IDLE
                pumpStops()
            }
        }
    }

    @Synchronized
    fun stopStream(stream: Stream) {
        expireRequests()
        if (stream !in active && pending?.stream != stream) return
        stopping.add(stream)
        pumpStops()
    }

    @Synchronized
    fun stopPlayback() {
        expireRequests()
        stopping.addAll(active)
        pending?.takeIf { it.starting }?.let { stopping.add(it.stream) }
        pumpStops()
    }

    private fun pumpStops() {
        if (playbackFailed || pending != null ||
            micState == MicState.STARTING || micState == MicState.STOPPING) return
        val stream = stopping.firstOrNull { it in active } ?: return
        pending = Pending(stream, false, nowMs() + ACK_TIMEOUT_MS)
        if (!send(STOP, stream.stopBody())) failPlayback()
    }

    @Synchronized
    fun requestMic(): Boolean {
        expireRequests()
        if (probe != null || !micAllowed || micState != MicState.IDLE || pending != null) return false
        micFrames.clear()
        micState = MicState.STARTING
        micDeadline = nowMs() + ACK_TIMEOUT_MS
        if (!send(MIC_START, MIC_START_BODY)) {
            micState = MicState.IDLE
            return false
        }
        return true
    }

    /** Called only after C2P has sent the required acknowledgment. Never logs raw PCM. */
    @Synchronized
    fun onMicData(pcm: ByteArray) {
        expireRequests()
        if (micState != MicState.ACTIVE || pcm.isEmpty() || pcm.size > MAX_PCM_BYTES || pcm.size % 2 != 0) return
        if (!micFrames.offer(pcm.copyOf())) {
            micFrames.poll()
            micFrames.offer(pcm.copyOf())
        }
    }

    fun pollMic(timeoutMs: Long): ByteArray? {
        val pcm = micFrames.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return synchronized(this) {
            expireRequests()
            pcm?.takeIf { micState == MicState.ACTIVE }
        }
    }

    @Synchronized
    fun stopMic() {
        micFrames.clear()
        if (micState != MicState.STARTING && micState != MicState.ACTIVE) return
        val starting = micState == MicState.STARTING
        micState = MicState.STOPPING
        micDeadline = nowMs() + ACK_TIMEOUT_MS
        if (!send(MIC_STOP, ByteArray(0)) || starting) {
            // Late start replies are ambiguous after a cancelled request.
            micState = MicState.FAILED
            pumpStops()
        }
    }

    @Synchronized
    fun reset() {
        probe = null
        probePhase = "disconnected"
        allowed = emptySet()
        active.clear()
        stopping.clear()
        pending = null
        playbackFailed = false
        micAllowed = false
        micState = MicState.IDLE
        micFrames.clear()
    }

    private fun expireRequests() {
        val request = pending
        if (request != null && nowMs() >= request.deadline) {
            if (request.starting) active.add(request.stream)
            failPlayback()
        }
        if ((micState == MicState.STARTING || micState == MicState.STOPPING) && nowMs() >= micDeadline) {
            if (micState == MicState.STARTING) send(MIC_STOP, ByteArray(0))
            micState = MicState.FAILED
            micFrames.clear()
            log("Carbit audio: mic acknowledgment timeout; use local input")
            pumpStops()
        }
    }

    private fun failPlayback() {
        // Never reuse an uncorrelated acknowledgment after timeout.
        playbackFailed = true
        pending = null
        active.forEach { send(STOP, it.stopBody()) }
        active.clear()
        stopping.clear()
        log("Carbit audio: playback control failed; use local output until reconnect")
    }

    companion object {
        const val CAPABILITIES = 0x00010020
        const val START = 0x00020090
        const val START_ACK = 0x00020091
        const val STOP = 0x000200D0
        const val STOP_ACK = 0x000200D1
        const val MUSIC = 0x60000030
        const val MIC_START = 0x000200F0
        const val MIC_START_ACK = 0x000200F1
        const val MIC_STOP = 0x00020100
        const val MIC_STOP_ACK = 0x00020101
        const val MIC_DATA = 0x00010060
        const val MIC_DATA_ACK = 0x00010061
        private const val ACK_TIMEOUT_MS = 1000L
        private const val MAX_PCM_BYTES = 16 * 1024
        private val MIC_START_BODY = """{"sampleRate":16000,"channel":1,"format":3}""".toByteArray(Charsets.UTF_8)
    }
}
