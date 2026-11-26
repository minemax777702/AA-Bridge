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
    private val ids = listOf("idle", "audio_phone", "audio_bt", "audio_pxc_music", "audio_pxc_tts",
        "audio_pxc_vr", "audio_pxc_talkie", "display", "touch", "map_zoom", "map_rotate",
        "wheel", "media_keys", "navigation", "voice", "volume", "focus", "night",
        "cluster_light", "headlights", "drive_mode", "climate_temp", "climate_fan", "climate_ac",
        "doors", "native_voice", "native_radio", "cluster_nav", "native_exit", "gps",
        "parking", "gear", "sensors", "ignition", "heartbeat", "reconnect", "wireless")
    private var index = 0
    private var opened = false
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
        text(test.title, 22f)
        text(test.instructions)
        val audio = test.id.startsWith("audio_")
        val timed = test.id in listOf("idle", "heartbeat")
        when (stage) {
            "ready" -> {
                if (audio) {
                    text(if (test.id == "audio_bt") "Подключите Bluetooth автомобиля (один медиавыход)." else
                        "Отключите Bluetooth-аудио. Остановите музыку и голосового помощника, установите низкую громкость.")
                    button("Настройки Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
                button(if (audio) "Воспроизвести сигнал" else "Начать проверку") { startStep() }
                button("Пропустить") { skip() }
            }
            "running" -> text(if (timed) "Записываем фоновый обмен…" else "Идёт автоматическая проверка…")
            "answer" -> {
                if (audio) {
                    text("Слышали сигнал именно из динамиков автомобиля?", 19f)
                    text(evidence.toString())
                } else text(question(test.id), 19f)
                button("Да") { answer("yes") }
                button("Нет") { answer("no") }
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
        "map_rotate" -> "Карта поворачивается при вращении двух пальцев?"
        "idle", "heartbeat" -> "Соединение оставалось стабильным?"
        "voice" -> "Голосовой помощник распознал речь?"
        "night" -> "Тема Android Auto изменилась вслед за ГУ?"
        "wheel", "media_keys" -> "Android Auto отреагировал на кнопку?"
        "cluster_nav" -> "Подсказка появилась на приборной панели?"
        "gps" -> "Положение на карте соответствует текущему?"
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
        evidence = JSONObject().put("mode", "guided").put("connectionBefore", ConnectionStateHolder.snapshot.phase.name)
        val token = ++generation
        val keepGoing = { visible && generation == token && RawHuDump.active }
        fun complete(result: JSONObject) {
            runOnUiThread {
                if (isDestroyed || generation != token || !opened) return@runOnUiThread
                evidence = result.put("mode", "guided").put("connectionAfter", ConnectionStateHolder.snapshot.phase.name)
                stage = "answer"; render()
            }
        }
        when {
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
            test.id.startsWith("audio_pxc_") && !evidence.optBoolean("signalQueued") -> "inconclusive"
            test.id in listOf("audio_phone", "audio_bt") &&
                (evidence.optString("status") != "played" || !evidence.optBoolean("routeVerified")) -> "inconclusive"
            value == "yes" -> "works"
            else -> "failed"
        }
        evidence.put("userAnswer", value).put("connectionAfter", ConnectionStateHolder.snapshot.phase.name)
        HuSurvey.finish(result, evidence)
        summary.add("${test.title}: " + when (result) {
            "works" -> "Да"; "failed" -> "Нет"; "not-tested" -> "Пропущено"; else -> "Не подтверждено"
        })
        opened = false; generation++; index++; stage = "ready"; evidence = JSONObject()
        render()
        // Run the next audio profile automatically if its prerequisites are satisfied.
        // Bluetooth changes and manual vehicle actions always require a user action.
        val next = id
        if (next != null && next.startsWith("audio_pxc_") && prerequisites(next)) startStep()
    }
}
