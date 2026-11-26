package dev.caraalink.diagnostics

import android.app.Activity
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
    private val tick = object : Runnable {
        override fun run() {
            status.text = HuCapture.status() + "\n" + ConnectionStateHolder.snapshot.phase
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
        button("Начать запись") {
            if (HuCapture.start(this)) snapshot()
            status.text = HuCapture.status()
        }
        button("Снимок устройств и соединения") {
            if (HuCapture.active) snapshot() else notice("Сначала начните запись")
        }
        text("Метки действий: отметьте начало проверки, выполните действие и отметьте результат.", 18f)
        for (label in listOf("USB / Wi-Fi Direct", "Экран / сенсор", "Кнопки руля", "Музыка",
            "Навигационные подсказки", "Микрофон / голос", "Зажигание", "Стояночный тормоз",
            "Селектор / задний ход", "Парктроник / камера", "Двери / свет / климат",
            "Успешно", "Не работает", "Нет наблюдаемого события")) {
            button(label) {
                if (HuCapture.active) {
                    HuCapture.record("manual", "MARK", label)
                    notice("Метка записана: $label")
                } else notice("Сначала начните запись")
            }
        }
        button("Остановить запись") {
            HuCapture.stop()
            status.text = HuCapture.status()
        }
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

    private fun snapshot() {
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
