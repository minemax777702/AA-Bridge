package dev.caraalink.diagnostics

import android.content.Context
import android.os.SystemClock
import dev.caraalink.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Observed protocol evidence, never authoritative vehicle parking state. */
internal object HuCompatibility {
    data class Evidence(
        val protocol: String = "",
        val connected: Boolean = false,
        val sessionId: String = "",
        val musicDeclared: Boolean? = null,
        val outputDeviceType: Int? = null,
        val micPath: String = "",
        val micPeak: Int? = null,
        val micSilenced: Boolean? = null,
        val appliedProfile: String = "",
        val name: String = "",
        val screen: String = "",
        val capabilities: String = "",
        val micDeclared: Boolean? = null,
        val playbackAck: Boolean = false,
        val micAck: Boolean = false,
        val micPcm: Boolean = false,
        val multiTouch: Boolean = false,
        val messages: Int = 0,
        val vehicleFields: Map<String, String> = emptyMap(),
    )
    private val mutable = MutableStateFlow(Evidence())
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    private var windowStart = 0L
    private var written = 0
    private val recent = linkedMapOf<String, Pair<Int, Long>>()

    @Synchronized fun start(token: Any, protocol: String, appliedProfile: String) {
        owner = token
        mutable.value = Evidence(protocol = protocol, connected = true, sessionId = java.util.UUID.randomUUID().toString(), appliedProfile = appliedProfile)
        recent.clear()
        written = 0
        windowStart = SystemClock.elapsedRealtime()
    }
    @Synchronized fun end(token: Any) {
        if (owner !== token) return
        owner = null
        mutable.value = mutable.value.copy(connected = false)
    }
    @Synchronized fun screen(token: Any, size: String) {
        if (owner === token) mutable.value = mutable.value.copy(screen = size)
    }
    @Synchronized fun multiTouch(token: Any) {
        if (owner === token && !mutable.value.multiTouch) mutable.value = mutable.value.copy(multiTouch = true)
    }
    @Synchronized fun output(sessionId: String, type: Int?) {
        val e = mutable.value
        if (e.connected && e.sessionId == sessionId && type != null && e.outputDeviceType != type)
            mutable.value = e.copy(outputDeviceType = type)
    }
    @Synchronized fun microphone(sessionId: String, path: String, peak: Int? = null, silenced: Boolean? = null) {
        val e = mutable.value
        if (e.connected && e.sessionId == sessionId)
            mutable.value = e.copy(micPath = path, micPeak = peak, micSilenced = silenced)
    }

    private val vehicleName = Regex("park|brake|gear|selector|speed|velocity|ignition|rpm|engine|odometer|fuel|battery|seatbelt|door|vehicle|canbus|reverse|steer|turnsignal|temperature|handbrake", RegexOption.IGNORE_CASE)
    private val privateName = Regex("token|password|secret|serial|uuid|huid|vin|mac|address|latitude|longitude|location|phone|account|ssid|imei|imsi|auth", RegexOption.IGNORE_CASE)

    private fun fields(json: JSONObject, prefix: String = "", depth: Int = 0, out: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
        if (depth > 5) return out
        val keys = json.keys()
        while (keys.hasNext() && out.size < 64) {
            val key = keys.next()
            if (privateName.containsMatchIn(key)) continue
            val path = (prefix + key).take(100)
            val value = json.opt(key)
            if (value is JSONObject) fields(value, "$path.", depth + 1, out)
            else if (value is org.json.JSONArray) {
                for (i in 0 until minOf(value.length(), 16)) {
                    if (out.size >= 64) break
                    val item = value.opt(i)
                    if (item is JSONObject) fields(item, "$path[$i].", depth + 1, out)
                    else if (vehicleName.containsMatchIn(path) && (item is Number || item is Boolean || item is String)) {
                        out["$path[$i]"] = item.toString().replace(Regex("[\\r\\n\\t]"), " ").take(100)
                    }
                }
            }
            else if (vehicleName.containsMatchIn(path) && (value is Number || value is Boolean || value is String)) {
                out[path] = value.toString().replace(Regex("[\\r\\n\\t]"), " ").take(100)
            }
        }
        return out
    }
    private fun safeJson(json: JSONObject, depth: Int = 0): JSONObject {
        val safe = JSONObject()
        if (depth > 5) return safe
        val keys = json.keys()
        var count = 0
        while (keys.hasNext() && count++ < 64) {
            val key = keys.next()
            if (privateName.containsMatchIn(key)) continue
            when (val value = json.opt(key)) {
                is JSONObject -> safe.put(key.take(100), safeJson(value, depth + 1))
                is Number, is Boolean -> safe.put(key.take(100), value)
                is String -> safe.put(key.take(100), value.take(160))
                is org.json.JSONArray -> safe.put(key.take(100), "[array length=${value.length()}]")
            }
        }
        return safe
    }

    @Synchronized fun receive(token: Any, context: Context, logger: DiagnosticLogger, channel: String, cmd: Int, body: ByteArray) {
        if (owner !== token) return
        var e = mutable.value
        // PCM, video, high-rate touch and heartbeat never get payload dumps.
        if (channel == "PXC" && cmd == 0x10060) {
            if (e.micAck && !e.micPcm && body.isNotEmpty()) mutable.value = e.copy(micPcm = true)
            return
        }
        if (channel == "PXC" && ((cmd and 0xFF000000.toInt()) == 0x60000000 || cmd == 0x70000000 || cmd == 0x70000001)) return
        if (channel == "MIRROR" && (cmd == 0x20 || cmd == 0x40 || cmd == 0x72)) return
        if (channel == "QD-HU" && body.size <= 8192 && dev.caraalink.link.QdBin.logicId(body)?.equals("heartbeat", ignoreCase = true) == true) return
        val json = if (body.size in 2..8192 && body.firstOrNull { it != 32.toByte() && it != 10.toByte() && it != 13.toByte() && it != 9.toByte() } == '{'.code.toByte())
            runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() else null
        if (channel == "QD-JSON" && json?.optString("CMD") == "CAR_INFO") {
            val para = json.optJSONObject("PARA")
            if (para != null) e = e.copy(name = para.optString("CarName").take(100),
                capabilities = runCatching { safeJson(para.optJSONObject("CarFeature") ?: JSONObject()).toString().take(1000) }.getOrDefault(""))
        }
        if (channel == "PXC") when (cmd) {
            0x10010 -> if (json != null) e = e.copy(name = json.optString("HUName").take(100),
                micDeclared = if (json.has("supportMic")) json.opt("supportMic") == true else null)
            0x10020 -> if (json != null) e = e.copy(musicDeclared = json.opt("music") as? Boolean, capabilities = listOf("music","tts","vr","talkie","autoChangeToBT")
                .joinToString(" · ") { "$it=${json.opt(it) ?: "?"}" })
            0x20091 -> e = e.copy(playbackAck = true)
            0x200F1 -> e = e.copy(micAck = true)
        }
        val capture = AppSettings.huCapture(context)
        val observed = if (capture && json != null) runCatching { fields(json) }.getOrDefault(emptyMap()) else emptyMap()
        val merged = (e.vehicleFields + observed).entries.toList().takeLast(64).associate { it.toPair() }
        mutable.value = e.copy(messages = (e.messages + 1).coerceAtMost(1_000_000), vehicleFields = merged)
        if (!capture) return
        val now = SystemClock.elapsedRealtime()
        if (now - windowStart >= 60_000) { windowStart = now; written = 0 }
        if (written >= 240) return
        val logic = if (channel == "QD-HU" && body.size <= 8192) dev.caraalink.link.QdBin.logicId(body)?.take(64) else null
        val id = "$channel:0x${cmd.toString(16)}" + if (logic != null && !privateName.containsMatchIn(logic)) " logic=$logic" else ""
        val fingerprint = body.size * 31 + body.take(256).hashCode()
        val previous = recent[id]
        if (previous != null && previous.first == fingerprint && now - previous.second < 5000) return
        if (previous != null && now - previous.second < 500) return
        if (recent.size >= 128 && id !in recent) recent.remove(recent.keys.first())
        recent[id] = fingerprint to now
        written++
        val detail = if (json != null) " json=${runCatching { safeJson(json).toString().take(3000) }.getOrDefault("[unavailable]")}" else
            if (channel == "MIRROR" || channel == "QD-BIN") " hex=${body.take(256).joinToString("") { "%02x".format(it.toInt() and 255) }}" else " payload=unclassified"
        logger.log("HU RX $id len=${body.size}$detail")
        if (observed.isNotEmpty()) logger.log("HU vehicle candidates (unverified): $observed")
    }
}
