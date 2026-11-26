package dev.caraalink.diagnostics

import android.content.Context
import android.os.SystemClock
import dev.caraalink.BuildConfig
import org.json.JSONObject
import java.util.UUID

/**
 * Opt-in structured protocol trace, local only. No new transport or command.
 * TX_BUILT means a frame was constructed, not that it reached the HU.
 * Media samples are deliberately excluded by callers; unknown control payloads
 * can still contain identifiers, so review the ZIP before sharing.
 */
object HuCapture {
    private const val MAX_EVENTS = 50_000
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val MAX_PAYLOAD = 4096
    private val hex = "0123456789abcdef"
    private data class Session(
        val id: String,
        val start: Long,
        val logger: DiagnosticLogger,
        var count: Int = 0,
        var bytes: Int = 0,
        var dropped: Long = 0,
        var adbDataRx: Long = 0,
        var adbDataTx: Long = 0,
    )
    @Volatile private var session: Session? = null
    val active: Boolean get() = session != null

    @Synchronized fun start(context: Context): Boolean {
        if (!BuildConfig.HU_DIAGNOSTICS || session != null) return false
        val s = Session(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(),
            DiagnosticLogger(context.applicationContext, "hu-trace"))
        session = s
        HuSurvey.start()
        record("session", "START", "capture", details = "build=${BuildConfig.GIT_SHA}")
        return true
    }

    @Synchronized fun stop() {
        val s = session ?: return
        HuSurvey.stop()
        session = null
        s.logger.log(JSONObject().put("session", s.id).put("event", "STOP")
            .put("events", s.count).put("dropped", s.dropped).put("estimatedBytes", s.bytes)
            .put("adbDataRx", s.adbDataRx).put("adbDataTx", s.adbDataTx).toString())
    }

    fun status(): String = synchronized(this) {
        session?.let { "Запись: ${it.count} событий, пропущено ${it.dropped}" } ?: "Запись остановлена"
    }

    fun record(
        protocol: String, direction: String, event: String,
        payload: ByteArray? = null, length: Int = payload?.size ?: 0,
        details: String = "",
    ) {
        if (!BuildConfig.HU_DIAGNOSTICS || session == null) return
        synchronized(this) {
            val s = session ?: return
            HuSurvey.observe(protocol, direction, event, length, payload)
            if (s.count >= MAX_EVENTS || s.bytes >= MAX_BYTES) {
                s.dropped++
                return
            }
            val n = minOf(payload?.size ?: 0, MAX_PAYLOAD)
            val json = JSONObject().put("session", s.id)
                .put("sequence", s.count + 1)
                .put("elapsedMs", SystemClock.elapsedRealtime() - s.start)
                .put("protocol", protocol).put("direction", direction)
                .put("event", event).put("length", length)
                .put("details", details.take(4096))
            if (payload != null) {
                val value = buildString(n * 2) {
                    for (i in 0 until n) {
                        val b = payload[i].toInt() and 255
                        append(hex[b ushr 4]); append(hex[b and 15])
                    }
                }
                json.put("hex", value).put("truncated", n < payload.size)
            }
            val line = json.toString()
            // Conservative budget for UTF-8, plus the existing logger prefix.
            val cost = line.toByteArray(Charsets.UTF_8).size + 100
            if (s.bytes + cost > MAX_BYTES) {
                s.dropped++
                return
            }
            s.count++
            s.bytes += cost
            s.logger.log(line)
        }
    }

    // WRTE/OKAY include the high-volume projection stream. Preserve counters
    // and sample their headers, so video cannot exhaust the control trace.
    fun adb(direction: String, cmd: Int, arg0: Int, arg1: Int, length: Int) {
        if (!active) return
        synchronized(this) {
            val s = session ?: return
            if (cmd == 0x45545257 || cmd == 0x59414b4f) {
                val count = if (direction == "RX") ++s.adbDataRx else ++s.adbDataTx
                if (count > 8 && count % 128 != 0L) return
            }
            record("ADB", direction, "0x" + cmd.toUInt().toString(16),
                length = length, details = "arg0=$arg0 arg1=$arg1")
        }
    }

    fun pxc(direction: String, cmd: Int, extra: Int, body: ByteArray, stream: Int? = null) {
        if (!active) return
        val media = cmd == 0x00010060 || cmd == 0x00010061 ||
            (cmd and 0xFF000000.toInt()) == 0x60000000
        record("PXC", direction, "0x" + cmd.toUInt().toString(16).padStart(8, '0'),
            if (media) null else body, body.size,
            "extra=$extra stream=$stream mediaPayloadOmitted=$media")
    }
}
