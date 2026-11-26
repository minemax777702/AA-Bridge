package dev.caraalink.diagnostics

import android.os.SystemClock
import dev.caraalink.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Evidence inventory. Observations are not assertions that a function works. */
object HuSurvey {
    private const val MAX_KINDS = 2048
    private const val MAX_SAMPLES = 8
    private const val MAX_FIELDS = 256
    data class Scenario(val id: String, val title: String, val instructions: String)
    val scenarios = listOf(
        Scenario("inventory", "Инвентаризация ГУ", "Автоматически проверяются обнаруженные параметры, команды и возможности."),
        Scenario("mic_pxc", "Микрофон автомобиля PXC", "Остановите голосового помощника. После запуска теста произнесите короткую фразу. RAW содержит аудио."),
        Scenario("mic_phone", "Микрофон телефона", "Отключите Bluetooth-аудио, произнесите короткую фразу во время теста. Проверяется запись Android."),
        Scenario("mic_bt", "Микрофон Bluetooth", "Подключите Bluetooth автомобиля, произнесите короткую фразу. Проверяется фактический вход Android, не принудительное переключение."),
        Scenario("touch_tap", "Одиночное касание", "Нажмите центр и четыре угла экрана в безопасном интерфейсе карты."),
        Scenario("touch_swipe", "Свайп", "Переместите карту одним пальцем в четырёх направлениях."),
        Scenario("touch_long", "Долгое нажатие", "Удерживайте палец на карте, не подтверждайте никаких действий."),
        Scenario("map_tilt", "Наклон карты", "Проверьте жест наклона двумя пальцами, если карты его поддерживают."),
        Scenario("key_next", "Следующий трек", "Нажмите штатную кнопку следующего трека один раз."),
        Scenario("key_prev", "Предыдущий трек", "Нажмите штатную кнопку предыдущего трека один раз."),
        Scenario("key_play", "Пауза / воспроизведение", "Проверьте штатную клавишу паузы и возобновления."),
        Scenario("key_voice", "Голосовая кнопка руля", "Нажмите кнопку голосового помощника и отмените запрос."),
        Scenario("key_back", "Назад / Home", "Проверьте штатную кнопку возврата, затем снова откройте Android Auto."),
        Scenario("volume_up", "Громкость +", "На низкой громкости прибавьте один шаг и верните значение."),
        Scenario("volume_down", "Громкость −", "Уменьшите громкость на один шаг."),
        Scenario("volume_mute", "Приглушение", "Проверьте штатное отключение и восстановление звука."),
        Scenario("display_fit", "Границы изображения", "Посмотрите, видны ли все края и элементы интерфейса Android Auto."),
        Scenario("display_resume", "Возобновление изображения", "Сверните проекцию штатным способом и откройте снова."),
        Scenario("night_on", "Ночной режим", "На стоянке используйте штатный способ включения ночной темы; не выключайте необходимое освещение."),
        Scenario("night_off", "Дневной режим", "На стоянке верните исходную тему штатным способом."),
        Scenario("bt_controls", "Управление Bluetooth", "Посмотрите, отображает ли штатное ГУ подключение и метаданные текущего трека."),
        Scenario("voice_catalog", "Каталог голосовых функций", "Автоматическая проверка наличия каталога, без исполнения команд."),
        Scenario("nav_info", "Параметры навигации", "Автоматическая проверка объявленных параметров навигации, без предположения о приборной панели."),
        Scenario("vehicle_speed", "Скорость автомобиля", "На стоянке наблюдайте доступное штатное значение скорости. Не начинайте движение ради теста."),
        Scenario("engine_rpm", "Обороты двигателя", "Если двигатель уже работает на открытом воздухе, наблюдайте штатное значение на приборной панели. Не запускайте двигатель специально."),
        Scenario("engine_temp", "Температура двигателя", "Наблюдайте штатную индикацию на стоянке."),
        Scenario("fuel", "Топливо / запас хода", "Наблюдайте штатные показания, не меняя состояние автомобиля."),
        Scenario("tire_pressure", "Давление шин", "Если штатная индикация доступна, откройте её на стоянке. Не изменяйте давление."),
        Scenario("windows", "Стеклоподъёмники", "Только наблюдайте штатные функции; не отправляйте команды из приложения."),
        Scenario("sunroof", "Люк", "Если есть в комплектации, наблюдайте штатную индикацию, иначе пропустите."),
        Scenario("defog", "Обдув / запотевание", "Проверьте штатную индикацию обдува, не отключая необходимые функции."),
        Scenario("link_usb", "USB-соединение", "Проверьте, установилось ли штатное подключение по кабелю."),
        Scenario("time_sync", "Синхронизация времени", "Сравните отображаемое время телефона и ГУ. Не изменяйте системное время."),
        Scenario("native_apps", "Штатные приложения", "Проверьте доступность штатных приложений через интерфейс ГУ без установки или запуска неизвестных пакетов."),
        Scenario("idle", "Фоновый обмен", "Не трогайте ГУ 30–60 секунд."),
        Scenario("audio_phone", "Звук телефона без Bluetooth", "Тестовый сигнал обычным аудиомаршрутом Android."),
        Scenario("audio_bt", "Звук через Bluetooth", "Тестовый сигнал через выбранный Bluetooth-аудиовыход."),
        Scenario("audio_pxc_music", "Прямой звук MUSIC", "Тест PXC без Bluetooth."),
        Scenario("audio_pxc_tts", "Прямой звук TTS", "Тест PXC без Bluetooth."),
        Scenario("audio_pxc_vr", "Прямой звук VR", "Тест PXC без Bluetooth."),
        Scenario("audio_pxc_talkie", "Прямой звук TALKIE", "Тест PXC без Bluetooth."),
        Scenario("display", "Экран и видео", "Откройте Android Auto, проверьте изображение, возврат и повторное открытие."),
        Scenario("map_zoom", "Масштабирование карты", "Разведите и сведите два пальца на карте Android Auto."),
        Scenario("map_rotate", "Вращение карты: обычный профиль", "Поверните два пальца вокруг общего центра, почти не меняя расстояние между ними. Проверьте, повернулась ли карта."),
        Scenario("map_rotate_full", "Вращение карты: полный поток касаний", "Повторите тот же жест. Только в этом тесте все события движения двух пальцев передаются без ограничения 30 мс."),
        Scenario("touch", "Сенсор", "Нажмите четыре угла и центр, выполните свайп и мультитач, если доступен."),
        Scenario("wheel", "Кнопки руля", "По отдельности проверьте кнопки мультимедиа, голос и возврат."),
        Scenario("music", "Музыка", "Включите и остановите музыку в Android Auto, проверьте источник звука."),
        Scenario("navigation", "Навигация", "Проверьте голосовую подсказку без движения автомобиля."),
        Scenario("voice", "Микрофон", "Вызовите голосовой помощник, проверьте распознавание и завершение."),
        Scenario("volume", "Громкость", "Измените громкость и отключите звук штатными кнопками."),
        Scenario("call", "Телефонный вызов", "При согласии собеседника проверьте входящий или исходящий вызов и аудиомаршрут; не записывайте разговор без разрешения."),
        Scenario("gps", "GPS и источник координат", "Сравните отображаемое местоположение со штатной навигацией; не двигайтесь ради теста."),
        Scenario("focus", "Аудиофокус", "Переключите штатное радио и Android Auto, проверьте паузу и восстановление звука."),
        Scenario("heartbeat", "Стабильность связи", "Оставьте подключение на 60 секунд; проверяются штатные heartbeat и ответы без новых команд."),
        Scenario("night", "День / ночь", "Измените освещение или штатную тему на стоящем автомобиле."),
        Scenario("parking", "Стояночный тормоз", "Только при безопасно зафиксированном автомобиле проверьте доступное состояние; не снимайте тормоз ради теста."),
        Scenario("gear", "Селектор / камера", "Только если это безопасно и разрешено руководством автомобиля; не двигайтесь, пропустите при сомнениях."),
        Scenario("sensors", "Парктроники", "Используйте только штатную индикацию на безопасно стоящем автомобиле; не создавайте препятствия."),
        Scenario("doors", "Двери", "На стоянке проверьте открытие и закрытие двери."),
        Scenario("climate", "Климат", "Измените штатную настройку климата и верните её."),
        Scenario("ignition", "Зажигание", "При безопасной стоянке наблюдайте штатное включение/выключение ГУ."),
        Scenario("reconnect", "Переподключение", "Отключите и повторно подключите телефон обычным способом."),
        Scenario("wireless", "Wi-Fi Direct", "Проверьте штатное беспроводное подключение отдельно от USB."),
        Scenario("drive_mode", "Режим Eco / Sport / Comfort", "Только наблюдение штатного режима на стоянке. Если изменение разрешено руководством и безопасно, используйте штатное меню, не приложение. Не запускайте двигатель ради теста."),
        Scenario("cluster_light", "Подсветка приборной панели", "На стоянке измените яркость штатным регулятором, верните исходное значение."),
        Scenario("headlights", "Габариты / фары", "На стоянке наблюдайте штатное изменение освещения; не отключайте необходимое освещение."),
        Scenario("climate_temp", "Температура климата", "Измените температуру на один шаг и верните исходное значение."),
        Scenario("climate_fan", "Скорость вентилятора", "Измените штатную скорость вентилятора на один шаг и верните."),
        Scenario("climate_ac", "Кондиционер / рециркуляция", "Проверьте штатную индикацию кондиционера и рециркуляции."),
        Scenario("native_voice", "Штатный голосовой помощник", "Если доступен, вызовите штатного помощника и отмените его без команды управления автомобилем."),
        Scenario("native_radio", "Штатный источник радио", "Переключите штатное радио и вернитесь в CarbitLink."),
        Scenario("cluster_nav", "Навигация на приборной панели", "На стоянке проверьте, появляются ли навигационные подсказки на приборной панели."),
        Scenario("media_keys", "Клавиши следующего / предыдущего трека", "На стоянке проверьте каждую клавишу отдельно."),
        Scenario("native_exit", "Выход из CarbitLink и возврат", "Откройте штатные настройки ГУ и вернитесь в CarbitLink, не отключая запись.")
    )
    private data class Stats(var count: Long = 0, var min: Int = Int.MAX_VALUE, var max: Int = 0,
        val samples: MutableSet<String> = linkedSetOf())
    private val packets = linkedMapOf<String, Stats>()
    private val fields = linkedMapOf<String, String>()
    private val controlJson = linkedMapOf<String, String>()
    private var controlBytes = 0
    private val results = JSONArray()
    private var running = false
    private var overflow = 0L
    private var current: Scenario? = null
    private var beginNs = 0L
    private var before = mapOf<String, Long>()
    private var baseline = mapOf<String, Long>()
    private val windowSamples = linkedMapOf<String, MutableSet<String>>()
    private var idleSamples = mapOf<String, Set<String>>()
    private val windowBytes = linkedMapOf<String, MutableList<ByteArray>>()
    private var idleBytes = mapOf<String, List<ByteArray>>()

    @Synchronized fun start() {
        if (!BuildConfig.HU_DIAGNOSTICS) return
        packets.clear(); fields.clear(); controlJson.clear(); controlBytes = 0
        while (results.length() > 0) results.remove(results.length() - 1)
        current = null; before = emptyMap(); baseline = emptyMap()
        windowSamples.clear(); idleSamples = emptyMap()
        windowBytes.clear(); idleBytes = emptyMap()
        overflow = 0; running = true
    }

    @Synchronized fun observe(protocol: String, direction: String, id: String, length: Int, body: ByteArray? = null) {
        if (!running) return
        val key = "$protocol/$direction/$id".take(256)
        val stats = packets[key] ?: run {
            if (packets.size >= MAX_KINDS) { overflow++; return }
            Stats().also { packets[key] = it }
        }
        stats.count++
        stats.min = minOf(stats.min, length)
        stats.max = maxOf(stats.max, length)
        // Hash bounded non-media samples only; RAW retains actual bytes.
        if (body != null && body.size <= 4096) {
            val window = if (current != null && (key in windowSamples || windowSamples.size < 256))
                windowSamples.getOrPut(key) { linkedSetOf() } else null
            if ((stats.samples.size < MAX_SAMPLES || (window != null && window.size < MAX_SAMPLES)) &&
                (stats.count <= 8 || stats.count % 16 == 0L || window?.isEmpty() == true)) {
                val hash = MessageDigest.getInstance("SHA-256").digest(body)
                    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                if (stats.samples.size < MAX_SAMPLES) stats.samples.add(hash)
                if (window != null && window.size < MAX_SAMPLES && window.add(hash) &&
                    (protocol == "PXC" || (protocol == "mirror-control" && id.startsWith("0x"))) && direction == "RX") {
                    windowBytes.getOrPut(key) { mutableListOf() }.add(body.copyOf(minOf(body.size, 128)))
                }
            }
        }
    }

    /** Capture entire bounded JSON messages, including large voice catalogs; not executable. */
    @Synchronized fun controlJson(cmd: Int, body: ByteArray) {
        if (!running || body.size !in 2..(64 * 1024)) return
        val value = String(body, Charsets.UTF_8).trim()
        if (!value.startsWith("{") || !value.endsWith("}")) return
        val json = runCatching { JSONObject(value) }.getOrNull() ?: return
        val id = "0x" + cmd.toUInt().toString(16).padStart(8, '0')
        val previous = controlJson[id]
        if (previous == value) return
        val cost = value.toByteArray(Charsets.UTF_8).size
        val oldCost = previous?.toByteArray(Charsets.UTF_8)?.size ?: 0
        if ((id !in controlJson && controlJson.size >= 128) || controlBytes - oldCost + cost > 512 * 1024) return
        controlBytes += cost - oldCost
        controlJson[id] = value
        RawHuDump.meta("survey/pxc-json/$id", value)
        val source = when (cmd) {
            0x10200 -> "PXC.NIGHT"
            0x10040 -> "PXC.NAVIGATION"
            0x10690 -> "PXC.LINK"
            else -> null
        }
        if (source != null) advertised(source, json)
    }

    @Synchronized fun advertised(source: String, json: JSONObject) {
        if (!running) return
        fun walk(prefix: String, value: Any?, depth: Int) {
            if (depth > 6 || fields.size >= MAX_FIELDS) return
            when (value) {
                is JSONObject -> value.keys().forEach { key -> walk("$prefix.$key", value.opt(key), depth + 1) }
                is JSONArray -> for (i in 0 until minOf(value.length(), 32)) walk("$prefix[$i]", value.opt(i), depth + 1)
                else -> fields[prefix.take(256)] = value.toString().take(512)
            }
        }
        walk(source, json, 0)
        RawHuDump.meta("survey/advertised", JSONObject().put("source", source).put("data", json).toString())
    }

    @Synchronized fun begin(id: String): Boolean {
        if (!running || current != null) return false
        val test = scenarios.firstOrNull { it.id == id } ?: return false
        current = test
        before = packets.mapValues { it.value.count }
        windowSamples.clear(); windowBytes.clear()
        beginNs = SystemClock.elapsedRealtimeNanos()
        RawHuDump.mark("TEST_BEGIN:$id")
        return true
    }

    @Synchronized fun hasActiveTest(): Boolean = current != null

    @Synchronized fun inventory(kind: String): JSONObject {
        val keys = when (kind) {
            "voice_catalog" -> setOf("0x00010470")
            "nav_info" -> setOf("0x00010040")
            else -> controlJson.keys.toSet()
        }
        val found = keys.filter { it in controlJson }
        return JSONObject().put("status", if (found.isEmpty()) "not-observed" else "observed")
            .put("messages", JSONArray(found)).put("declaredFieldCount", fields.size)
            .put("packetTypeCount", packets.size)
            .put("source", "incoming PXC JSON; not execution evidence")
    }

    @Synchronized fun isScenarioActive(id: String): Boolean = running && current?.id == id

    @Synchronized fun finish(result: String, evidence: JSONObject? = null): Boolean {
        val test = current ?: return false
        if (result !in listOf("works", "failed", "not-tested", "inconclusive")) return false
        val delta = packets.mapValues { (key, value) -> value.count - (before[key] ?: 0L) }.filterValues { it > 0 }
        if (test.id == "idle" && result == "works") {
            baseline = delta
            idleSamples = windowSamples.mapValues { it.value.toSet() }
            idleBytes = windowBytes.mapValues { it.value.toList() }
        }
        val candidates = delta.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.key !in baseline }
            .thenByDescending { it.value }).take(100)
        val entry = JSONObject().put("scenario", test.id).put("userResult", result)
            .put("durationMs", (SystemClock.elapsedRealtimeNanos() - beginNs) / 1_000_000)
            .put("candidates", JSONArray().apply {
                candidates.forEach { (key, count) -> put(JSONObject().put("packet", key).put("count", count)
                    .put("absentInIdleWindow", baseline.isNotEmpty() && key !in baseline)
                    .put("sampleDiffersFromIdle", idleSamples[key]?.let { idle -> windowSamples[key]?.any { it !in idle } } == true)
                    .put("candidateByteOffsets", candidateOffsets(key))) }
            })
        if (evidence != null) entry.put("wizardEvidence", evidence)
        if (results.length() < 200) results.put(entry)
        RawHuDump.mark("TEST_END:${test.id}:$result")
        RawHuDump.meta("survey/test", entry.toString())
        current = null
        windowSamples.clear(); windowBytes.clear()
        return true
    }

    // Only compare aligned prefixes of parsed messages with same observed size.
    // A changed offset is a hypothesis, not a decoded vehicle field.
    private fun candidateOffsets(key: String): JSONArray {
        val base = idleBytes[key].orEmpty()
        val test = windowBytes[key].orEmpty()
        val out = JSONArray()
        if (base.size < 1 || test.size < 1 || base.any { it.size != base[0].size } ||
            test.any { it.size != base[0].size }) return out
        for (i in base[0].indices) {
            val old = base[0][i]
            if (base.all { it[i] == old } && test.any { it[i] != old }) out.put(i)
            if (out.length() >= 64) break
        }
        return out
    }

    @Synchronized fun status(): String =
        "Обнаружено типов: ${packets.size}, полей: ${fields.size}, тестов: ${results.length()}\n" +
            (current?.let { "Текущий тест: ${it.title}" } ?: "Нет активного теста")

    @Synchronized fun report(): JSONObject = JSONObject()
        .put("schema", 1).put("distinctPacketTypes", packets.size).put("untrackedEvents", overflow)
        .put("advertisedFields", JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } })
        .put("pxcJson", JSONObject().apply { controlJson.forEach { (key, value) -> put(key, JSONObject(value)) } })
        .put("packets", JSONArray().apply {
            packets.forEach { (key, stats) ->
                put(JSONObject().put("id", key).put("count", stats.count)
                    .put("minLength", stats.min).put("maxLength", stats.max)
                    .put("sampleHashes", JSONArray(stats.samples.toList())))
            }
        }).put("manualTests", JSONArray(results.toString()))
        .put("interpretation", "Advertised fields, observed messages and user results are distinct evidence; absent is not unsupported. Correlation is not causation.")

    @Synchronized fun stop() {
        if (!running) return
        if (current != null) finish("inconclusive")
        running = false
    }
}
