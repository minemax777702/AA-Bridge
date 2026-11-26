package dev.caraalink.diagnostics

import android.app.Activity
import android.app.AlertDialog
import java.io.File
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.caraalink.connection.ConnectionStateHolder

/** The same process as the diagnostic bridge, not a second USB reader. */
class HuDiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var pendingDump: File? = null
    private val tick = object : Runnable {
        override fun run() {
            status.text = HuCapture.status() + "\n" + RawHuDump.status() + "\n" + ConnectionStateHolder.snapshot.phase
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(title: String, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = title
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
        text("AA-Bridge Diagnostics", 24f)
        text("Начните запись до подключения к ГУ. Вернитесь на главный экран и подключитесь обычным способом. Проверяйте функции только на стоящем автомобиле. Не отправляйте отчёт без просмотра: неизвестные пакеты могут содержать личные данные.")
        status = text(HuCapture.status())
        button("Начать полный RAW-дамп") {
            if (RawHuDump.busy || HuCapture.active) {
                notice("Сначала остановите текущую запись")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Полный дамп: лимит размера")
                    .setMessage("RAW включает видео, аудио, микрофон и потенциально личные данные. Записывайте только собственное подключение. Отправляйте файл только после проверки. Запись остановится при достижении лимита, нехватке места или переполнении очереди.")
                    .setNegativeButton("1 ГиБ") { _, _ -> startRaw(1) }
                    .setNeutralButton("4 ГиБ") { _, _ -> startRaw(4) }
                    .setPositiveButton("8 ГиБ") { _, _ -> startRaw(8) }
                    .show()
            }
        }
        button("Начать запись (только журнал)") {
            if (HuCapture.start(this)) snapshot()
            status.text = HuCapture.status()
        }
        button("Снимок устройств и соединения") {
            if (HuCapture.active || RawHuDump.active) snapshot() else notice("Сначала начните запись")
        }
        text("Метки действий: отметьте начало проверки, выполните действие и отметьте результат.", 18f)
        for (label in listOf("USB / Wi-Fi Direct", "Экран / сенсор", "Кнопки руля", "Музыка",
            "Навигационные подсказки", "Микрофон / голос", "Зажигание", "Стояночный тормоз",
            "Селектор / задний ход", "Парктроник / камера", "Двери / свет / климат",
            "Успешно", "Не работает", "Нет наблюдаемого события")) {
            button(label) {
                if (HuCapture.active || RawHuDump.active) {
                    RawHuDump.mark(label)
                    HuCapture.record("manual", "MARK", label)
                    notice("Метка записана: $label")
                } else notice("Сначала начните запись")
            }
        }
        button("Остановить запись") {
            RawHuDump.stop()
            HuCapture.stop()
            status.text = RawHuDump.status()
        }
        button("Экспорт полного дампа ZIP") { chooseDump(false) }
        button("Удалить сохранённый дамп") { chooseDump(true) }
        button("Экспорт всех журналов ZIP") {
            if (HuCapture.active) {
                notice("Сначала остановите запись")
            } else {
                Thread {
                    val result = runCatching {
                        DiagnosticLogger(applicationContext, "hu-export").shareAllLogsIntent()
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            result.onSuccess { startActivity(Intent.createChooser(it, "Экспорт диагностики")) }
                                .onFailure { notice("Ошибка экспорта: ${it.message}") }
                        }
                    }
                }.start()
            }
        }
        button("Назад к подключению") { finish() }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun notice(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun startRaw(limit: Int) {
        if (RawHuDump.start(this, limit)) {
            HuCapture.start(this)
            snapshot()
        } else notice("Не удалось начать RAW: проверьте свободное место")
    }

    private fun chooseDump(delete: Boolean) {
        if (RawHuDump.busy) { notice("Остановите RAW и дождитесь сохранения"); return }
        val dumps = RawHuDump.saved(this)
        if (dumps.isEmpty()) { notice("Нет сохранённых дампов"); return }
        val labels = dumps.map { dir ->
            val date = java.util.Date(dir.name.substringBefore('-').toLongOrNull() ?: dir.lastModified())
            "${date} — ${File(dir, "traffic.bin").length() / (1024 * 1024)} МиБ"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if (delete) "Удалить дамп" else "Выберите дамп")
            .setItems(labels) { _, index ->
                val dir = dumps[index]
                if (delete) {
                    AlertDialog.Builder(this).setMessage("Удалить выбранный дамп безвозвратно?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            notice(if (dir.deleteRecursively()) "Удалено" else "Ошибка удаления")
                        }.show()
                } else {
                    pendingDump = dir
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, "AA-HU-${dir.name}.zip")
                    }, 731)
                }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDump?.let { outState.putString("pendingDump", it.name) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pendingDump = RawHuDump.saved(this).firstOrNull { it.name == savedInstanceState.getString("pendingDump") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 731) return
        val dir = pendingDump.also { pendingDump = null } ?: return
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { RawHuDump.export(dir, it) }
                    ?: error("Cannot open destination")
            }
            runOnUiThread { if (!isDestroyed) notice(if (result.isSuccess) "ZIP сохранён" else "Ошибка экспорта: ${result.exceptionOrNull()?.message}") }
        }.start()
    }

    private fun snapshot() {
        RawHuDump.meta("device", "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
        RawHuDump.meta("connection", ConnectionStateHolder.snapshot.toString())
        HuCapture.record("device", "INFO", "phone",
            details = "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
        HuCapture.record("connection", "INFO", "snapshot", details = ConnectionStateHolder.snapshot.toString())
        runCatching {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            usb.accessoryList.orEmpty().forEach {
                HuCapture.record("USB", "INFO", "accessory",
                    details = "manufacturer=${it.manufacturer} model=${it.model} version=${it.version} description=${it.description}")
            }
            usb.deviceList.values.forEach { device ->
                HuCapture.record("USB", "INFO", "device",
                    details = "vid=${device.vendorId} pid=${device.productId} class=${device.deviceClass} interfaces=${device.interfaceCount}")
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    HuCapture.record("USB", "INFO", "interface",
                        details = "id=${iface.id} class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} endpoints=${iface.endpointCount}")
                }
            }
        }.onFailure { HuCapture.record("USB", "ERROR", "snapshot", details = it.toString()) }
        runCatching {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.getDevices(AudioManager.GET_DEVICES_ALL).forEach {
                HuCapture.record("audio", "INFO", "device",
                    details = "type=${it.type} name=${it.productName} source=${it.isSource} sink=${it.isSink}")
            }
        }.onFailure { HuCapture.record("audio", "ERROR", "snapshot", details = it.toString()) }
    }
}
