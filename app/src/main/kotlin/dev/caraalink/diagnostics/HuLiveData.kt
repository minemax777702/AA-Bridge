package dev.caraalink.diagnostics

import android.os.SystemClock
import dev.caraalink.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, session-scoped observations of incoming HU messages.
 * Unmapped fields/bytes are evidence, never automatically assigned vehicle semantics.
 * No media payloads, outbound messages, phone GPS or wizard answers become vehicle state.
 */
internal object HuLiveData {
    data class Row(val label: String, val value: String, val source: String, val kind: String, val at: Long = 0)
    private data class Value(val text: String, val type: String, val at: Long)
    private data class Packet(var count: Long = 0, var size: Int = 0, var at: Long = 0, var previous: ByteArray? = null)
    private var owner: Any? = null
    private var connected = false
    private var started = 0L
    private val fields = linkedMapOf<String, Value>()
    private val packets = linkedMapOf<String, Packet>()
    private val changes = ArrayDeque<JSONObject>()
    private var dropped = 0L
    private var omitted = 0L
    private const val MAX_FIELDS = 1024
    private const val MAX_PACKETS = 256
    private const val MAX_CHANGES = 2000
    private const val MAX_BINARY = 4096

    @Synchronized fun begin(session: Any) {
        if (!BuildConfig.HU_DIAGNOSTICS) return
        owner = session
        connected = true
        started = System.currentTimeMillis()
        fields.clear(); packets.clear(); changes.clear(); dropped = 0; omitted = 0
    }
    @Synchronized fun end(session: Any) {
        if (owner !== session || !connected) return
        connected = false
        change("connection", "link", "active", "closed", "session")
    }
    private fun accepts(session: Any?): Boolean = BuildConfig.HU_DIAGNOSTICS && session != null && owner === session && connected

    private fun change(source: String, field: String, old: String?, value: String?, kind: String) {
        val event = JSONObject().put("timeMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("source", source).put("field", field)
            .put("old", old ?: JSONObject.NULL).put("new", value ?: JSONObject.NULL).put("kind", kind)
        if (changes.size == MAX_CHANGES) { changes.removeFirst(); dropped++ }
        changes.addLast(event)
        if (RawHuDump.active) RawHuDump.meta("live/change", event.toString())
    }

    private fun value(source: String, name: String, data: Any?) {
        val key = "$source/$name".take(320)
        val type = when (data) {
            is Boolean -> "boolean"
            is Number -> "number"
            null, JSONObject.NULL -> "null"
            else -> "string"
        }
        val text = data.toString().take(512)
        val old = fields[key]
        if (old == null && fields.size >= MAX_FIELDS) { omitted++; return }
        fields[key] = Value(text, type, System.currentTimeMillis())
        if (old == null || old.text != text || old.type != type) change(source, name, old?.text, text, "json-$type")
    }

    private fun flatten(source: String, json: JSONObject) {
        var visited = 0
        fun walk(path: String, item: Any?, depth: Int) {
            if (++visited > 256 || depth > 6) return
            when (item) {
                is JSONObject -> {
                    val keys = item.keys()
                    while (keys.hasNext() && visited < 256) {
                        val key = keys.next()
                        walk(if (path.isEmpty()) key else "$path.$key", item.opt(key), depth + 1)
                    }
                }
                is JSONArray -> {
                    value(source, "$path.length", item.length())
                    for (i in 0 until minOf(item.length(), 24)) walk("$path[$i]", item.opt(i), depth + 1)
                }
                else -> value(source, path, item)
            }
        }
        walk("", json, 0)
    }

    private fun packet(key: String, size: Int): Packet? {
        val p = packets[key] ?: run {
            if (packets.size >= MAX_PACKETS) { omitted++; return null }
            Packet().also { packets[key] = it }
        }
        p.count++; p.size = size; p.at = System.currentTimeMillis()
        return p
    }

    @Synchronized fun pxc(session: Any?, cmd: Int, body: ByteArray, reply: Boolean) {
        if (!accepts(session)) return
        val source = "PXC/" + (if (reply) "reply/" else "") + "0x" + cmd.toUInt().toString(16).padStart(8, '0')
        val p = packet(source, body.size) ?: return
        // Media and periodic empty packets: count only. Never interpret PCM as vehicle signals.
        if (body.isEmpty() || cmd == 0x10060 || cmd == 0x10061 || (cmd and 0xff000000.toInt()) == 0x60000000) return
        if (body.size <= 64 * 1024 && body.first() == '{'.code.toByte()) {
            val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
            if (json != null) {
                // Catalog is large; preserve a bounded field sample, not thousands of change rows.
                if (cmd == 0x10470 && !reply) {
                    value(source, "received", true)
                    value(source, "bytes", body.size)
                    if (p.count == 1L) flatten(source, json)
                } else flatten(source, json)
                return
            }
        }
        binary(source, p, body)
    }

    @Synchronized fun mirror(session: Any?, type: Int, body: ByteArray) {
        if (!accepts(session)) return
        val source = "mirror/0x" + type.toString(16).padStart(4, '0')
        val p = packet(source, body.size) ?: return
        if (type !in setOf(0x10, 0x20, 0x30, 0x40, 0x70)) binary(source, p, body)
    }

    @Synchronized fun qd(session: Any?, id: String, json: JSONObject) {
        if (!accepts(session)) return
        val source = "QDLink/" + id.take(80)
        packet(source, json.toString().length) ?: return
        if (id != "HEARTBEAT") flatten(source, json)
    }

    @Synchronized fun config(session: Any, json: JSONObject) {
        if (accepts(session)) flatten("mirror/config", json)
    }

    @Synchronized fun key(session: Any, key: Int) {
        if (!accepts(session)) return
        packet("mirror/key", 0)
        value("mirror/key", "lastCode", key)
    }

    private fun binary(source: String, p: Packet, body: ByteArray) {
        if (body.isEmpty() || body.size > MAX_BINARY) return
        val old = p.previous
        if (old == null) {
            change(source, "binary", null, HuPacketDiff.hex(body, 64), "unknown-binary-first")
        } else if (!old.contentEquals(body)) {
            val diff = HuPacketDiff.describe(old, body)
            change(source, "binary", HuPacketDiff.hex(old, 64), diff, "unknown-binary-change")
        }
        p.previous = body.copyOf()
    }

    @Synchronized fun rows(): List<Row> {
        val out = mutableListOf<Row>()
        fun field(label: String, source: String, name: String, kind: String, bool: Boolean = false) {
            val v = fields["$source/$name"]
            val display = if (v == null) "Нет данных" else if (bool && v.type == "boolean")
                if (v.text == "true") "Да" else "Нет" else v.text
            out.add(Row(label, display, "$source/$name", if (v == null) "unknown" else kind, v?.at ?: 0))
        }
        val info = "PXC/0x00010010"
        val audio = "PXC/0x00010020"
        val night = fields["PXC/0x00010200/night"]
        out.add(Row("Ночной режим", if (night == null || night.type != "boolean") "Нет данных" else if (night.text == "true") "Да" else "Нет",
            "PXC/0x00010200/night", if (night?.type == "boolean") "state" else "unknown", night?.at ?: 0))
        // No verified protocol mapping for these vehicle signals yet.
        listOf("Зажигание", "Двигатель запущен", "Дверь водителя", "Передняя правая дверь", "Задняя левая дверь",
            "Задняя правая дверь", "Багажник", "Капот", "Габариты", "Ближний свет", "Дальний свет",
            "Подсветка приборов", "Режим Eco / Sport / Comfort", "Селектор КПП", "Стояночный тормоз",
            "Парктроники", "Камера заднего вида", "Скорость автомобиля", "Обороты двигателя",
            "Температура двигателя", "Топливо", "Давление шин", "Температура салона", "Настройка климата",
            "Кондиционер", "Вентилятор", "GPS автомобиля").forEach {
            out.add(Row(it, "Нет данных", "Семантика не подтверждена", "unknown"))
        }
        listOf("HUName" to "Модель ГУ", "carBrand" to "Марка", "carModel" to "Платформа",
            "version_name" to "Версия ПО", "sdkVersion" to "SDK", "pxcVersion" to "PXC",
            "package_name" to "Платформа CarbitLink", "supportFunction" to "Маска функций",
            "supportConnect" to "Маска подключений", "currentHUTime" to "Время ГУ (raw)")
            .forEach { (key, label) -> field(label, info, key, "info") }
        listOf("supportMic" to "Микрофон ГУ", "supportBTCall" to "Bluetooth-звонки",
            "supportBTSetting" to "Настройки Bluetooth", "supportHID" to "HID",
            "supportScreenTouch" to "Сенсор", "supportScreenMirroring" to "Зеркалирование",
            "supportSyncCorrectTime" to "Синхронизация времени", "supportThirdPartyApp" to "Сторонние приложения",
            "supportOTAUpdate" to "OTA").forEach { (key, label) -> field(label, info, key, "advertised", true) }
        listOf("music", "tts", "vr", "talkie", "autoChangeToBT").forEach { field("Аудио: $it", audio, it, "advertised", true) }
        listOf("width", "height", "fps", "encoder", "bitrate", "orientation").forEach {
            field("Экран: $it", "mirror/config", it, "info")
        }
        field("Каталог голосовых команд получен", "PXC/0x00010470", "received", "observed", true)
        field("Навигация: maxNaviIcon", "PXC/0x00010040", "maxNaviIcon", "info")
        field("Последний код клавиши", "mirror/key", "lastCode", "observed")
        return out
    }
    @Synchronized fun isConnected() = connected
    @Synchronized fun rawFields(): List<Row> = fields.map { (key, v) ->
        Row(key.substringAfterLast('/'), v.text, key, "raw-${v.type}", v.at)
    }
    @Synchronized fun packetRows(): List<Row> = packets.map { (key, p) ->
        Row(key, "${p.count} пак. · ${p.size} Б", key, "observed", p.at)
    }
    @Synchronized fun history(): List<JSONObject> = changes.toList()
    @Synchronized fun status(): String = "${fields.size} полей · ${packets.size} типов · ${changes.size} изменений · вытеснено $dropped · лимит $omitted"
    @Synchronized fun report(): JSONObject = JSONObject().put("schema", 1)
        .put("sessionStartedMs", started).put("connectionActive", connected)
        .put("historyDropped", dropped).put("omitted", omitted)
        .put("parameters", JSONArray(rows().map { JSONObject().put("label", it.label).put("value", it.value)
            .put("source", it.source).put("kind", it.kind).put("lastReceivedMs", it.at) }))
        .put("rawFields", JSONArray(rawFields().map { JSONObject().put("source", it.source).put("value", it.value)
            .put("kind", it.kind).put("lastReceivedMs", it.at) }))
        .put("packetTypes", JSONArray(packetRows().map { JSONObject().put("source", it.source).put("value", it.value)
            .put("lastReceivedMs", it.at) }))
        .put("changes", JSONArray(changes.toList()))
        .put("interpretation", "Incoming HU observations only; unknown does not mean false. Advertised support is not actual state. Binary changes are not decoded vehicle signals.")
}
