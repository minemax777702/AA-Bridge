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
    private var lastNonempty: JSONObject? = null
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
        if (fields.keys.any { !it.startsWith("Instrument/link/") }) lastNonempty = JSONObject().put("sessionStartedMs", started)
            .put("rawFields", JSONArray(rawFields().map { JSONObject().put("source", it.source).put("value", it.value).put("kind", it.kind).put("lastReceivedMs", it.at) }))
        owner = session
        connected = true
        started = System.currentTimeMillis()
        fields.clear(); packets.clear(); omitted = 0
        change("connection", "session", null, started.toString(), "session-start")
    }
    @Synchronized fun end(session: Any) {
        if (owner !== session || !connected) return
        connected = false
        owner = null
        change("connection", "link", "active", "closed", "session")
    }
    private fun accepts(session: Any?): Boolean = BuildConfig.HU_DIAGNOSTICS && session != null && owner === session && connected

    private fun change(source: String, field: String, old: String?, value: String?, kind: String) {
        val event = JSONObject().put("timeMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("sessionStartedMs", started).put("source", source).put("field", field)
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

    @Synchronized fun instrumentLink(session: Any, status: String) {
        if (accepts(session)) value("Instrument/link", "status", status)
    }

    @Synchronized fun instrumentPacket(session: Any, cmd: Int, body: ByteArray) {
        if (!accepts(session)) return
        val source = "Instrument/0x" + cmd.toUInt().toString(16).padStart(8, '0')
        val p = packet(source, body.size) ?: return
        if (cmd != dev.caraalink.link.InstrumentWire.MXC) binary(source, p, body)
    }

    @Synchronized fun instrumentFields(session: Any, source: String, json: JSONObject) {
        if (accepts(session)) flatten("Instrument/$source", json)
    }

    @Synchronized fun instrumentMcu(session: Any, frame: dev.caraalink.link.InstrumentWire.McuFrame) {
        if (!accepts(session)) return
        val source = "Instrument/MCU/0x" + frame.type.toString(16).padStart(4, '0')
        val p = packet(source, frame.bytes.size) ?: return
        if (frame.signals.isEmpty()) binary(source, p, frame.bytes)
        else {
            value("Instrument/link", "status", "telemetry")
            frame.signals.forEach {
                value("Instrument/values", it.name + ".valid", it.valid)
                value("Instrument/values", it.name, it.raw)
            }
        }
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
        val reversing = fields["PXC/0x00010460/reversing"]
        out.add(Row("Задний ход (событие PXC)", when (reversing?.text) {
            "4112" -> "Да"; "4113" -> "Нет"; else -> "Нет данных"
        }, "PXC/0x00010460/reversing", if (reversing?.text in listOf("4112", "4113")) (if (connected) "state" else "stale") else "unknown", reversing?.at ?: 0))
        field("Событие Bluetooth (код)", "PXC/0x00010460", "bluetooth", "observed")
        field("Режим движения PXC (код приложения)", "PXC/0x00010460", "drivingMode", "observed")
        val linkState = fields["Instrument/link/status"]
        val linkLabel = when (linkState?.text) {
            "waiting" -> "Ожидается канал"
            "listening" -> "Ожидается подключение"
            "listen-failed" -> "Не удалось открыть порт"
            "accepted" -> "Соединение принято"
            "channel-open", "receiving" -> "Канал открыт, ожидаются данные"
            "p2c-connected" -> "Обратный канал открыт"
            "telemetry" -> "Данные получены"
            "closed" -> "Канал закрыт"
            "invalid-header" -> "Некорректный заголовок"
            "unsupported-or-duplicate-channel" -> "Неизвестный или повторный канал"
            else -> "Нет данных"
        }
        out.add(Row("Instrument 10926", if (connected) linkLabel else "Нет активного соединения",
            "Instrument/link/status", if (connected && linkState != null) "observed" else "unknown", linkState?.at ?: 0))
        fun signal(label: String, name: String, bool: Boolean = false) {
            val source = "Instrument/values/$name"
            val v = fields[source]
            val valid = fields["$source.valid"]
            val available = v != null && valid?.text == "true"
            val link = fields["Instrument/link/status"]?.text
            val fresh = connected && link == "telemetry" && available && System.currentTimeMillis() - (v?.at ?: 0) < 10000
            val display = if (!available || v == null) "Нет данных" else if (bool)
                (if (v.text == "1") "Да" else if (v.text == "0") "Нет" else v.text)
            else v.text + " (raw)"
            out.add(Row(label, display, source, if (!available) "unknown" else if (fresh) "state" else "stale", v?.at ?: 0))
        }
        signal("Зажигание", "IGN", true)
        signal("Скорость автомобиля", "SPEED")
        signal("Обороты двигателя", "RPM")
        signal("Селектор КПП (код)", "GEAR")
        signal("Температура ОЖ", "TEMP_WATER")
        signal("Температура воздуха", "TEMP_AIR")
        signal("Топливо (%) — raw", "FUEL_PERCENT")
        signal("Остаток топлива", "FUEL_REMAIN")
        signal("Пробег", "ODO")
        signal("Пробег поездки 1", "TRIP1")
        signal("Пробег поездки 2", "TRIP2")
        signal("Запас хода", "RANGE_LEFT")
        signal("Средняя скорость поездки 1", "TRIP1_AVG_SPEED")
        signal("Средняя скорость поездки 2", "TRIP2_AVG_SPEED")
        signal("Средний расход", "TIMER_AVG_CONSUME")
        signal("Общее время движения", "TOTAL_DRIVE_TIME")
        signal("Ближний свет", "LIGHT_NEAR", true)
        signal("Дальний свет", "LIGHT_FAR", true)
        signal("Левый поворотник", "LIGHT_TURN_LEFT", true)
        signal("Правый поворотник", "LIGHT_TURN_RIGHT", true)
        signal("Индикатор габаритов", "LIGHT_EDGE", true)
        signal("Индикатор стояночного тормоза", "LIGHT_PARK", true)
        signal("Индикатор заднего хода", "LIGHT_GEAR_R", true)
        signal("Подсветка приборов", "BACKLIGHT_LEVEL")
        signal("Режим двигателя (код Instrument)", "DRIVE_MODE")
        // No verified protocol mapping for these remaining vehicle signals yet.
        listOf("Двигатель запущен", "Дверь водителя", "Передняя правая дверь", "Задняя левая дверь",
            "Задняя правая дверь", "Багажник", "Капот", "Парктроники", "Камера заднего вида",
            "Давление шин", "Температура салона", "Настройка климата",
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
    @Synchronized fun mark(label: String) {
        if (BuildConfig.HU_DIAGNOSTICS && label.isNotBlank()) change("user", "marker", null, label.take(200), "manual-marker")
    }
    @Synchronized fun history(): List<JSONObject> = changes.toList()
    @Synchronized fun status(): String = "${fields.size} полей · ${packets.size} типов · ${changes.size} изменений · вытеснено $dropped · лимит $omitted"
    @Synchronized fun report(): JSONObject = JSONObject().put("schema", 1)
        .put("sessionStartedMs", started).put("connectionActive", connected)
        .put("historyDropped", dropped).put("omitted", omitted)
        .put("snapshotTimeMs", System.currentTimeMillis()).put("historyScope", "last 2000 changes across connections in current app process; current fields are last session only")
        .put("parameters", JSONArray(rows().map { JSONObject().put("label", it.label).put("value", it.value)
            .put("source", it.source).put("kind", it.kind).put("lastReceivedMs", it.at) }))
        .put("rawFields", JSONArray(rawFields().map { JSONObject().put("source", it.source).put("value", it.value)
            .put("kind", it.kind).put("lastReceivedMs", it.at) }))
        .put("packetTypes", JSONArray(packetRows().map { JSONObject().put("source", it.source).put("value", it.value)
            .put("lastReceivedMs", it.at) }))
        .put("changes", JSONArray(changes.toList()))
        .put("lastNonemptyPreviousSession", lastNonempty ?: JSONObject.NULL)
        .put("interpretation", "Incoming HU observations only; unknown does not mean false. Advertised support is not actual state. Binary changes are not decoded vehicle signals.")
}
