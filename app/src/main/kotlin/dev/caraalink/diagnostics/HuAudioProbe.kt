package dev.caraalink.diagnostics

import dev.caraalink.BuildConfig
import dev.caraalink.link.CarbitAudioTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/** Short, quiet, user-initiated audio test only; never sends vehicle control commands. */
internal object HuAudioProbe {
    @Volatile private var target: CarbitAudioTransport? = null
    private val running = AtomicBoolean()
    val profiles get() = CarbitAudioTransport.Stream.entries
    fun attach(audio: CarbitAudioTransport) {
        if (BuildConfig.HU_DIAGNOSTICS) target = audio
    }
    fun detach(audio: CarbitAudioTransport) {
        if (target === audio) target = null
    }

    fun run(profile: CarbitAudioTransport.Stream, shouldContinue: () -> Boolean = { true }, done: (String) -> Unit): Boolean {
        val audio = target ?: return false
        if (!BuildConfig.HU_DIAGNOSTICS || !RawHuDump.active || !running.compareAndSet(false, true)) return false
        Thread({
            var result = "not-started"
            var started = false
            var sent = 0
            fun event(value: String) {
                RawHuDump.meta("audio-probe", "${profile.name}: $value")
                HuCapture.record("audio-probe", "INFO", profile.name, details = value)
            }
            try {
                event("begin: explicit test, advertised flags unchanged")
                started = audio.beginDiagnosticProbe(profile)
                if (started) {
                    while (audio.diagnosticStatus() == "starting" && RawHuDump.active && target === audio && shouldContinue())
                        Thread.sleep(25)
                    result = audio.diagnosticStatus()
                    if (result == "active" && RawHuDump.active && target === audio && shouldContinue()) {
                        // 20 ms frames, 440 Hz, about -34 dBFS, fade in/out, 1 second max.
                        val count = profile.rate / 50
                        for (frame in 0 until 50) {
                            if (!RawHuDump.active || target !== audio || !shouldContinue()) break
                            val pcm = ByteArray(count * profile.channels * 2)
                            for (i in 0 until count) {
                                val n = frame * count + i
                                val envelope = minOf(1.0, n / (profile.rate * 0.05),
                                    (profile.rate - 1 - n) / (profile.rate * 0.05))
                                val sample = (sin(2 * PI * 440 * n / profile.rate) * 650 * envelope).toInt()
                                for (ch in 0 until profile.channels) {
                                    val offset = (i * profile.channels + ch) * 2
                                    pcm[offset] = sample.toByte()
                                    pcm[offset + 1] = (sample shr 8).toByte()
                                }
                            }
                            if (!audio.diagnosticFrame(pcm)) break
                            sent++
                            Thread.sleep(20)
                        }
                        result = "start-ack; queued-frames=$sent/50"
                    }
                }
            } catch (e: Exception) {
                result = "error: ${e.javaClass.simpleName}"
            } finally {
                try {
                    if (started) {
                        audio.endDiagnosticProbe()
                        while (audio.diagnosticStatus() == "stopping" && target === audio) Thread.sleep(25)
                        result += "; final=${audio.diagnosticStatus()}"
                    }
                    event(result)
                } finally {
                    running.set(false)
                    done(result)
                }
            }
        }, "HuAudioProbe").apply { isDaemon = true }.start()
        return true
    }
}
