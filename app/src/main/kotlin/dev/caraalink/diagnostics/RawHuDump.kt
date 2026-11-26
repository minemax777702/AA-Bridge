package dev.caraalink.diagnostics

import android.content.Context
import android.os.SystemClock
import dev.caraalink.BuildConfig
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Byte-for-byte application-layer I/O capture. Local, explicitly enabled.
 * A single writer preserves enqueue order without blocking the car transport
 * on disk I/O. If capacity is exhausted, stop and mark the capture incomplete.
 */
object RawHuDump {
    private const val RESERVE = 512L * 1024 * 1024
    private const val MAX_QUEUE = 16L * 1024 * 1024
    private const val MAX_RECORDS_IN_QUEUE = 16384
    private const val MAGIC = "AHUDUMP1"
    const val RX = 1
    const val TX = 2
    const val MARK = 3
    const val META = 4

    private data class Record(val seq: Long, val ns: Long, val direction: Int,
        val name: ByteArray, val data: ByteArray) {
        val size: Long get() = 25L + name.size + data.size
    }

    private class Session(val dir: File, val startedNs: Long, val wallMs: Long, val limit: Long) {
        val queue = LinkedBlockingQueue<Record>(MAX_RECORDS_IN_QUEUE)
        @Volatile var accepting = true
        @Volatile var finished = false
        @Volatile var reason = "recording"
        var queued = 0L
        var accepted = 16L
        var sequence = 0L
        @Volatile var written = 16L
        @Volatile var recordsWritten = 0L
        var rejected = 0L
    }

    @Volatile private var current: Session? = null
    val active: Boolean get() = current?.accepting == true
    val busy: Boolean get() = current?.finished == false
    private fun directory(context: Context) = File(context.filesDir, "hu-dumps")
    fun streamId(prefix: String): String = "$prefix/${UUID.randomUUID()}"

    @Synchronized fun start(context: Context, maxGiB: Int): Boolean {
        if (!BuildConfig.HU_DIAGNOSTICS || busy || maxGiB !in listOf(1, 4, 8)) return false
        return runCatching {
            val base = directory(context).apply { mkdirs() }
            val limit = minOf(maxGiB * 1024L * 1024 * 1024, base.usableSpace - RESERVE)
            if (limit < 1024 * 1024) return false
            val wall = System.currentTimeMillis()
            val dir = File(base, "$wall-${UUID.randomUUID()}")
            check(dir.mkdir()) { "Cannot create dump directory" }
            val s = Session(dir, SystemClock.elapsedRealtimeNanos(), wall, limit)
            // A process death before finalization must never look like a complete dump.
            File(dir, "manifest.json").writeText(manifest(s, false).toString(2))
            current = s
            Thread({ writer(s) }, "RawHuDump").apply { isDaemon = true }.start()
            meta("session", "build=${BuildConfig.GIT_SHA}; limit=$limit")
            true
        }.getOrDefault(false)
    }

    @Synchronized fun stop() {
        val s = current ?: return
        if (s.accepting) {
            s.reason = "user-stop"
            s.accepting = false
        }
    }

    fun status(): String {
        val s = current ?: return "RAW: не запущен"
        val state = when {
            s.accepting -> "запись"
            !s.finished -> "сохранение"
            s.reason == "user-stop" -> "остановлен"
            else -> "НЕПОЛНЫЙ: ${s.reason}"
        }
        return "RAW: $state • ${s.recordsWritten} записей • ${s.written / (1024 * 1024)} МиБ / ${s.limit / (1024 * 1024)} МиБ"
    }

    fun mark(label: String) = record("manual", MARK, label.toByteArray(Charsets.UTF_8))
    fun meta(stream: String, label: String) = record(stream, META, label.toByteArray(Charsets.UTF_8))

    fun record(stream: String, direction: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (!BuildConfig.HU_DIAGNOSTICS || !active || length <= 0) return
        synchronized(this) {
            val s = current ?: return
            if (!s.accepting) return
            val name = stream.toByteArray(Charsets.UTF_8)
            val size = 25L + name.size + length
            val reason = when {
                s.accepted + size > s.limit -> "size-limit"
                s.queued + size > MAX_QUEUE || s.queue.remainingCapacity() == 0 -> "writer-overflow"
                else -> null
            }
            if (reason != null) {
                s.rejected++
                s.reason = reason
                s.accepting = false
                return
            }
            val r = Record(++s.sequence, SystemClock.elapsedRealtimeNanos() - s.startedNs,
                direction, name, bytes.copyOfRange(offset, offset + length))
            check(s.queue.offer(r))
            s.queued += size
            s.accepted += size
        }
    }

    private fun manifest(s: Session, finalized: Boolean): JSONObject = JSONObject()
        .put("format", MAGIC).put("session", s.dir.name).put("wallStartMs", s.wallMs)
        .put("build", BuildConfig.GIT_SHA).put("finalized", finalized)
        .put("completeUntilStop", finalized && s.reason == "user-stop" && s.recordsWritten == s.sequence)
        .put("stopReason", s.reason).put("recordCount", s.recordsWritten)
        .put("acceptedRecords", s.sequence).put("rejectedAtStop", s.rejected)
        .put("bytesWritten", s.written).put("limitBytes", s.limit)

    private fun writer(s: Session) {
        try {
            DataOutputStream(BufferedOutputStream(File(s.dir, "traffic.bin").outputStream(), 256 * 1024)).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeLong(s.wallMs)
                var checkAt = 0L
                while (s.accepting || s.queue.isNotEmpty()) {
                    val r = s.queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (s.written >= checkAt) {
                        check(s.dir.usableSpace > RESERVE) { "low-storage" }
                        checkAt = s.written + 1024 * 1024
                    }
                    out.writeLong(r.seq)
                    out.writeLong(r.ns)
                    out.writeByte(r.direction)
                    out.writeInt(r.name.size)
                    out.writeInt(r.data.size)
                    out.write(r.name)
                    out.write(r.data)
                    s.written += r.size
                    s.recordsWritten++
                    synchronized(this) { s.queued -= r.size }
                }
                out.flush()
            }
        } catch (e: Exception) {
            synchronized(this) {
                s.reason = "io-error: ${e.javaClass.simpleName}: ${e.message}"
                s.accepting = false
                s.queue.clear()
                s.queued = 0
            }
        } finally {
            runCatching {
                File(s.dir, "capabilities.json").writeText(HuSurvey.report().toString(2))
            }
            runCatching {
                File(s.dir, "manifest.json").writeText(manifest(s, true).toString(2))
            }
            s.finished = true
        }
    }

    fun saved(context: Context): List<File> = directory(context).listFiles()
        .orEmpty().filter { it.isDirectory && File(it, "traffic.bin").isFile &&
            (it != current?.dir || !busy) }.sortedByDescending { it.name }

    /** Writes directly to a user-selected SAF destination, without a second large local copy. */
    fun export(dir: File, destination: java.io.OutputStream) {
        require(dir != current?.dir || !busy)
        ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
            zip.setLevel(1)
            for (name in listOf("manifest.json", "capabilities.json", "traffic.bin")) {
                val file = File(dir, name)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
            }
        }
    }
}
