package dev.caraalink.link

/**
 * Experimental music channel, isolated from Android Auto and the video transport.
 * Wire facts: CarbitLink 6.5.1 arm64 libEPSDK.so, P2CMainService::sendAudioStart,
 * sendAudioData and sendAudioStop. See docs/CarbitLink-Protocol.md.
 *
 * The reader thread handles acknowledgments; the audio worker never waits for
 * one. Until the start ACK arrives, the caller continues local playback.
 */
internal class CarbitAudioTransport(
    private val send: (Int, ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private enum class State { IDLE, STARTING, PLAYING, STOPPING, FAILED }

    private var state = State.IDLE
    private var musicAllowed = false
    private var deadline = 0L

    @Synchronized
    fun updateMusicAllowed(allowed: Boolean) {
        if (musicAllowed == allowed) return
        musicAllowed = allowed
        if (!allowed) stopPlayback()
        log("Carbit audio: music allowed=$allowed")
    }

    /** True only if the complete PCM block was accepted for remote transmission. */
    @Synchronized
    fun playMusic(pcm: ByteArray): Boolean {
        if (!musicAllowed || pcm.isEmpty() || pcm.size % 4 != 0 || pcm.size > MAX_PCM_BYTES) return false
        expireRequest()
        when (state) {
            State.IDLE -> {
                state = State.STARTING
                deadline = nowMs() + ACK_TIMEOUT_MS
                if (!send(START, MUSIC_START)) {
                    // The P2C stream may not be connected yet. Do not enqueue sound.
                    state = State.IDLE
                }
                return false
            }
            State.PLAYING -> {
                if (send(MUSIC, pcm)) return true
                // Backpressure or disconnect: stop using the remote channel and
                // fall back instead of growing an unbounded audio backlog.
                stopPlayback()
                log("Carbit audio: remote send unavailable; using local output")
                return false
            }
            else -> return false
        }
    }

    @Synchronized
    fun onReply(command: Int) {
        expireRequest()
        when {
            command == START_ACK && state == State.STARTING -> {
                state = State.PLAYING
                log("Carbit audio: music start acknowledged")
            }
            command == STOP_ACK && state == State.STOPPING -> state = State.IDLE
        }
    }

    @Synchronized
    fun stopPlayback() {
        if (state != State.PLAYING && state != State.STARTING) return
        val wasStarting = state == State.STARTING
        state = State.STOPPING
        deadline = nowMs() + ACK_TIMEOUT_MS
        val sent = send(STOP, MUSIC_STOP)
        // Start ACK has no stream token. Never reuse a timed-out/cancelled
        // start on the same connection, where a late ACK could be ambiguous.
        if (!sent || wasStarting) state = State.FAILED
    }

    @Synchronized
    fun reset() {
        state = State.IDLE
        musicAllowed = false
        deadline = 0L
    }

    private fun expireRequest() {
        if ((state == State.STARTING || state == State.STOPPING) && nowMs() >= deadline) {
            if (state == State.STARTING) send(STOP, MUSIC_STOP)
            state = State.FAILED
            log("Carbit audio: acknowledgment timed out; using local output until reconnect")
        }
    }

    companion object {
        const val CAPABILITIES = 0x00010020
        const val START = 0x00020090
        const val START_ACK = 0x00020091
        const val STOP = 0x000200D0
        const val STOP_ACK = 0x000200D1
        const val MUSIC = 0x60000030
        private const val ACK_TIMEOUT_MS = 1000L
        private const val MAX_PCM_BYTES = 16 * 1024
        private val MUSIC_START = """{"type":4,"sampleRate":48000,"channel":2,"format":3}""".toByteArray(Charsets.UTF_8)
        private val MUSIC_STOP = """{"type":4}""".toByteArray(Charsets.UTF_8)
    }
}
