package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import org.json.JSONObject

/** One question at a time. All tests use the existing capture and connection. */
class HuWizardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val ids = listOf(
        "idle", "inventory", "voice_catalog", "nav_info", "audio_phone", "audio_bt", "audio_pxc_music",
        "audio_pxc_tts", "audio_pxc_vr", "audio_pxc_talkie", "mic_phone", "mic_bt", "mic_pxc", "display",
        "display_fit", "display_resume", "touch", "touch_tap", "touch_swipe", "touch_long", "map_zoom",
        "map_rotate", "map_rotate_full", "map_tilt", "music", "navigation", "voice", "call",
        "wheel", "media_keys", "key_next", "key_prev", "key_play", "key_voice", "key_back",
        "volume", "volume_up", "volume_down", "volume_mute", "focus", "bt_controls", "night",
        "night_on", "night_off", "cluster_light", "headlights", "drive_mode", "climate", "climate_temp",
        "climate_fan", "climate_ac", "defog", "doors", "windows", "sunroof", "native_voice",
        "native_radio", "native_apps", "cluster_nav", "native_exit", "gps", "time_sync", "parking",
        "gear", "sensors", "vehicle_speed", "engine_rpm", "engine_temp", "fuel", "tire_pressure",
        "ignition", "heartbeat", "link_usb", "reconnect", "wireless"
    )
    private var index = 0
    private var opened = false
    private var vehicleState = "unknown"
    private var stage = "ready"
    private var evidence = JSONObject()
    private var summary = mutableListOf<String>()
    @Volatile private var generation = 0
    @Volatile private var visible = false
    private lateinit var content: LinearLayout
    private val id get() = ids.getOrNull(index)
    private val step get() = HuSurvey.scenarios.firstOrNull { it.id == id }
    private val refresh = object : Runnable {
        override fun run() {
            if (!RawHuDump.active || !HuCapture.active) {
                generation++
                stage = "stopped"
                render()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt("index")
            opened = savedInstanceState.getBoolean("opened")
            vehicleState = savedInstanceState.getString("vehicleState") ?: "unknown"
            stage = savedInstanceState.getString("stage") ?: "ready"
            evidence = runCatching { JSONObject(savedInstanceState.getString("evidence") ?: "{}") }.getOrDefault(JSONObject())
            summary = savedInstanceState.getStringArrayList("summary") ?: mutableListOf()
            if (stage == "running") { stage = "answer"; evidence.put("status", "interrupted") }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putBoolean("opened", opened)
        outState.putString("vehicleState", vehicleState)
        outState.putString("stage", stage)
        outState.putString("evidence", evidence.toString())
        outState.putStringArrayList("summary", ArrayList(summary))
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.post(refresh)
        render()
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(refresh)
        if (stage == "running") {
            generation++
            stage = "answer"
            evidence.put("status", "interrupted")
        }
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        if (isFinishing && opened) HuSurvey.finish("inconclusive", evidence.put("status", "wizard-exited"))
        super.onDestroy()
    }

    private fun text(value: String, size: Float = 16f) {
        content.addView(TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 12)
        })
    }
    private fun button(value: String, action: () -> Unit) {
        content.addView(Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } })
    }

    private fun render() {
        content.removeAllViews()
        text("Мастер диагностики", 24f)
        if (stage == "stopped" || !RawHuDump.active || !HuCapture.active) {
            text("Запись остановлена. Вернитесь к диагностике и сохраните дамп.")
            button("Назад") { finish() }
            return
        }
        val test = step
        if (test == null) {
            text("Проверка завершена", 22f)
            summary.forEach { text(it) }
            button("Завершить и сохранить запись") { HuCapture.stop(); RawHuDump.stop(); finish() }
            button("Вернуться к записи") { finish() }
            return
        }
        text("Шаг ${index + 1} из ${ids.size}")
        val states = listOf("unknown", "ACC", "ON", "engine-running")
        val labels = arrayOf("Не указано", "ACC", "Зажигание ON", "Двигатель работает")
        button("Состояние авто: " + labels[states.indexOf(vehicleState).coerceAtLeast(0)]) {
            android.app.AlertDialog.Builder(this).setTitle("Фактическое состояние автомобиля")
                .setItems(labels) { _, i ->
                    vehicleState = states[i]
                    RawHuDump.mark("VEHICLE_STATE:$vehicleState")
                    render()
                }.show()
        }
        text(test.title, 22f)
        text(test.instructions)
        val audio = test.id.startsWith("audio_")
        val timed = test.id in listOf("idle", "heartbeat")
        val mic = test.id.startsWith("mic_")
        val inventory = test.id in listOf("inventory", "voice_catalog", "nav_info")
        when (stage) {
            "ready" -> {
                if (audio) {
                    text(if (test.id == "audio_bt") "Подключите Bluetooth автомобиля (один медиавыход)." else
                        "Отключите Bluetooth-аудио. Остановите музыку и голосового помощника, установите низкую громкость.")
                    button("Настройки Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    if (test.id.startsWith("audio_pxc_") && !HuAudioProbe.available)
                        text("PXC не готов. После тайм-аута переподключите ГУ; при отсутствии канала пропустите тест.")
                }
                if (mic) {
                    text("Запись до 3 секунд. Произнесите короткую фразу, не содержащую личных данных.")
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        button("Разрешить микрофон") { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 641) }
                }
                button(if (audio) "Воспроизвести сигнал" else "Начать проверку") { startStep() }
                button("Пропустить") { skip() }
            }
            "running" -> text(if (timed) "Записываем фоновый обмен…" else "Идёт автоматическая проверка…")
            "answer" -> {
                if (audio) {
                    text("Слышали сигнал именно из динамиков автомобиля?", 19f)
                    val verified = if (test.id.startsWith("audio_pxc_")) evidence.optBoolean("signalQueued")
                        else evidence.optBoolean("routeVerified") && evidence.optString("status") == "played"
                    text(if (verified) "Тестовый сигнал отправлен, подтвердите слышимость."
                        else "Маршрут или передача сигнала не подтверждены. Результат будет отмечен как неопределённый.")
                    if (test.id.startsWith("audio_pxc_") && !verified)
                        text("При тайм-ауте переподключите ГУ перед следующей проверкой.")
                    text("Технический результат: " + evidence.optString("status", "—"))
                } else if (mic) {
                    text("Технический результат: " + evidence.optString("status"))
                    text("Получено отсчётов: " + evidence.optLong("samples") + "; пик: " + evidence.optInt("peak"))
                    text("Произнесли фразу во время теста?", 19f)
                } else if (inventory) {
                    text("Результат автоматического обнаружения: " + evidence.optString("status"))
                    text("Типов пакетов: " + evidence.optInt("packetTypeCount"))
                } else text(question(test.id), 19f)
                if (inventory) button("Далее") { answer("auto") }
                else { button("Да") { answer("yes") }; button("Нет") { answer("no") } }
                button("Не уверен / не удалось проверить") { answer("uncertain") }
                button("Повторить") {
                    if (opened) HuSurvey.finish("inconclusive", evidence.put("status", "repeated"))
                    opened = false; stage = "ready"; evidence = JSONObject(); render()
                }
                button("Пропустить") { answer("skip") }
            }
        }
        button("Выйти из мастера") { finish() }
    }

    private fun question(id: String): String = when (id) {
        "map_zoom" -> "Карта увеличивается и уменьшается двумя пальцами?"
        "map_rotate", "map_rotate_full" -> "Карта поворачивается при вращении двух пальцев?"
        "idle", "heartbeat" -> "Соединение оставалось стабильным?"
        "voice" -> "Голосовой помощник распознал речь?"
        "night" -> "Тема Android Auto изменилась вслед за ГУ?"
        "wheel", "media_keys" -> "Android Auto отреагировал на кнопку?"
        "cluster_nav" -> "Подсказка появилась на приборной панели?"
        "gps" -> "Положение на карте соответствует текущему?"
        "display" -> "Изображение Android Auto отображается корректно?"
        "touch" -> "Касания и свайпы отрабатывают корректно?"
        "touch_tap" -> "Все точки экрана реагируют на касание?"
        "touch_swipe" -> "Карта перемещается во всех направлениях?"
        "touch_long" -> "Долгое нажатие распознаётся?"
        "map_tilt" -> "Карта меняет угол наклона?"
        "key_next", "key_prev" -> "Трек переключился?"
        "key_play" -> "Пауза и воспроизведение сработали?"
        "key_voice" -> "Голосовой помощник открылся?"
        "key_back" -> "Штатная кнопка вызвала возврат?"
        "night_on", "night_off" -> "Android Auto переключился на соответствующую тему?"
        "volume_up", "volume_down", "volume_mute" -> "Громкость изменилась как ожидалось?"
        "vehicle_speed", "engine_rpm", "engine_temp", "fuel", "tire_pressure" -> "Штатное значение отображается на приборной панели или ГУ?"
        "display_fit" -> "Все элементы интерфейса видны, ничего не обрезано?"
        "display_resume" -> "Изображение вернулось после открытия CarbitLink?"
        "time_sync" -> "Время на телефоне и ГУ совпадает?"
        "volume" -> "Громкость изменяется штатными кнопками?"
        "drive_mode" -> "Штатный интерфейс показал изменение режима движения?"
        "cluster_light" -> "Яркость приборной панели изменилась?"
        "navigation" -> "Навигационная подсказка слышна в автомобиле?"
        "focus" -> "Звук восстановился при возврате в Android Auto?"
        else -> "Наблюдаемая штатная функция сработала?"
    }

    private fun prerequisites(id: String): Boolean {
        if (!RawHuDump.active || !HuCapture.active || !visible) return false
        if (id.startsWith("audio_")) {
            val outputs = runCatching { HuLocalAudioTest.bluetoothOutputs(this) }.getOrNull() ?: return false
            if (id == "audio_bt") return outputs.count { it.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO } == 1
            if (outputs.isNotEmpty()) return false
        }
        if (id.startsWith("audio_pxc_")) return HuAudioProbe.available
        if (id == "mic_pxc") return HuAudioProbe.available && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (id.startsWith("mic_")) return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (id in listOf("idle", "heartbeat") && ConnectionStateHolder.snapshot.phase !in
            listOf(ConnectionPhase.ATTACHED, ConnectionPhase.STREAMING)) return false
        return true
    }

    private fun startStep() {
        val test = step ?: return
        if (!prerequisites(test.id)) {
            android.widget.Toast.makeText(this, "Проверьте подключение и условия теста; можно пропустить.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!HuSurvey.begin(test.id)) {
            android.widget.Toast.makeText(this, "Завершите предыдущий тест.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        opened = true
        evidence = JSONObject().put("mode", "guided").put("vehicleStateBefore", vehicleState).put("connectionBefore", ConnectionStateHolder.snapshot.phase.name)
        val token = ++generation
        val keepGoing = { visible && generation == token && RawHuDump.active }
        fun complete(result: JSONObject) {
            runOnUiThread {
                if (isDestroyed || generation != token || !opened) return@runOnUiThread
                evidence.keys().forEach { key -> if (!result.has(key)) result.put(key, evidence.opt(key)) }
                evidence = result.put("mode", "guided").put("connectionAfter", ConnectionStateHolder.snapshot.phase.name)
                stage = "answer"; render()
            }
        }
        when {
            test.id in listOf("inventory", "voice_catalog", "nav_info") -> {
                complete(HuSurvey.inventory(test.id))
            }
            test.id == "mic_pxc" -> {
                stage = "running"; render()
                if (!HuAudioProbe.runMic(keepGoing) { result -> complete(result) })
                    complete(JSONObject().put("status", "not-started"))
            }
            test.id in listOf("mic_phone", "mic_bt") -> {
                stage = "running"; render()
                Thread({ complete(HuLocalMicTest.run(applicationContext, test.id == "mic_bt", keepGoing)) }, "HuLocalMicTest").start()
            }
            test.id == "idle" || test.id == "heartbeat" -> {
                stage = "running"; render()
                handler.postDelayed({ if (generation == token) complete(JSONObject().put("status", "observed-30s")) }, 30_000)
            }
            test.id == "audio_phone" || test.id == "audio_bt" -> {
                stage = "running"; render()
                Thread({ complete(HuLocalAudioTest.run(applicationContext, test.id == "audio_bt", keepGoing)) }, "HuLocalAudioTest").start()
            }
            test.id.startsWith("audio_pxc_") -> {
                stage = "running"; render()
                val profile = HuAudioProbe.profiles.first { it.name.equals(test.id.removePrefix("audio_pxc_"), true) }
                if (!HuAudioProbe.run(profile, keepGoing) { result ->
                    complete(JSONObject().put("status", result)
                        .put("signalQueued", result.contains("queued-frames=50/50")))
                }) complete(JSONObject().put("status", "not-started"))
            }
            else -> { stage = "answer"; render() }
        }
    }

    private fun skip() {
        val test = step ?: return
        if (!opened && HuSurvey.begin(test.id)) opened = true
        if (opened) answer("skip")
    }

    private fun answer(value: String) {
        val test = step ?: return
        if (!opened) return
        val result = when {
            value == "skip" -> "not-tested"
            value == "uncertain" -> "inconclusive"
            value == "auto" -> "inconclusive" // advertised evidence never proves operation
            test.id.startsWith("mic_") -> "inconclusive" // signal metrics are not speech intelligibility
            test.id.startsWith("audio_pxc_") && !evidence.optBoolean("signalQueued") -> "inconclusive"
            test.id in listOf("audio_phone", "audio_bt") &&
                (evidence.optString("status") != "played" || !evidence.optBoolean("routeVerified")) -> "inconclusive"
            value == "yes" -> "works"
            else -> "failed"
        }
        evidence.put("userAnswer", value).put("vehicleStateAfter", vehicleState).put("connectionAfter", ConnectionStateHolder.snapshot.phase.name)
        HuSurvey.finish(result, evidence)
        summary.add("${test.title}: " + when (result) {
            "works" -> "Да"; "failed" -> "Нет"; "not-tested" -> "Пропущено"; else -> "Не подтверждено"
        })
        opened = false; generation++; index++; stage = "ready"; evidence = JSONObject()
        render()
        // Open the next observation window automatically; only safe audio/timed steps run code.
        // Bluetooth changes and manual vehicle actions always require a user action.
        val next = id
        if (next != null && !next.startsWith("mic_") && prerequisites(next)) startStep()
    }
}
