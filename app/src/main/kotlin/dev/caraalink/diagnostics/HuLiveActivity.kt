package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only live table, independent of RAW capture and the test wizard. */
class HuLiveActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var table: LinearLayout
    private var tab = 0
    private var last = ""
    private var exportInProgress = false
    private val colors = intArrayOf(Color.parseColor("#151B24"), Color.parseColor("#10151D"))
    private val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val refresh = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#0E1116"))
        }
        content.addView(label("Параметры ГУ", 23f, Color.WHITE))
        status = label("", 13f, Color.LTGRAY)
        content.addView(status)
        content.addView(label("Нет данных ≠ Нет. Объявленные возможности не означают фактическое состояние автомобиля. Данные последнего сеанса остаются после отключения.", 13f, Color.LTGRAY))
        val tabs = listOf("Параметры", "Поля пакетов", "Типы пакетов", "Изменения")
        for (pair in tabs.indices.chunked(2)) {
            val line = LinearLayout(this)
            pair.forEach { i ->
                line.addView(Button(this).apply {
                    text = tabs[i]; isAllCaps = false
                    setOnClickListener { tab = i; last = ""; update() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(line)
        }
        content.addView(Button(this).apply {
            text = "Отметить действие"; isAllCaps = false
            setOnClickListener {
                val input = android.widget.EditText(this@HuLiveActivity).apply { hint = "Например: открыта дверь водителя"; setSingleLine() }
                android.app.AlertDialog.Builder(this@HuLiveActivity).setTitle("Метка наблюдения").setView(input)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Записать") { _, _ -> HuLiveData.mark(input.text.toString()); update() }.show()
            }
        })
        content.addView(Button(this).apply {
            text = "Экспорт таблицы и истории JSON"; isAllCaps = false
            setOnClickListener {
                if (!exportInProgress) {
                    android.app.AlertDialog.Builder(this@HuLiveActivity).setTitle("Экспорт")
                        .setMessage("Неизвестные поля могут содержать идентификаторы ГУ. Проверьте файл перед отправкой.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Сохранить") { _, _ ->
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/json"
                                putExtra(Intent.EXTRA_TITLE, "AA-HU-live-${System.currentTimeMillis()}.json")
                            }, 752)
                        }.show()
                }
            }
        })
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(table) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(content)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }
    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(6), dp(7), dp(6), dp(7))
        setTextIsSelectable(true)
    }
    private fun update() {
        status.text = (if (HuLiveData.isConnected()) "Сеанс активен" else "Нет активного сеанса • последние данные") + "\n" +
            HuLiveData.status()
        val rows = when (tab) {
            1 -> HuLiveData.rawFields()
            2 -> HuLiveData.packetRows()
            3 -> HuLiveData.history().takeLast(150).asReversed().map {
                HuLiveData.Row(it.optString("field"), it.optString("old") + " → " + it.optString("new"),
                    it.optString("source"), it.optString("kind"), it.optLong("timeMs"))
            }
            else -> HuLiveData.rows()
        }
        // Keep the table scroll stable when no displayed value changes.
        val signature = rows.joinToString("\n") { "${it.label}|${it.value}|${it.kind}|${it.at / 10000}" }
        if (signature == last) return
        last = signature
        table.removeAllViews()
        val header = LinearLayout(this)
        header.addView(label("Параметр / источник", 13f, Color.LTGRAY), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("Значение / время", 13f, Color.LTGRAY), LinearLayout.LayoutParams(0, -2, 1f))
        table.addView(header)
        if (rows.isEmpty()) table.addView(label("Ожидаются входящие данные ГУ", 16f, Color.LTGRAY))
        rows.forEachIndexed { i, row ->
            val line = LinearLayout(this).apply { setBackgroundColor(colors[i % 2]) }
            val color = when (row.kind) {
                "state", "observed" -> Color.parseColor("#86EFAC")
                "stale" -> Color.parseColor("#FBBF24")
                "advertised" -> Color.parseColor("#93C5FD")
                "unknown" -> Color.parseColor("#A1A1AA")
                else -> Color.WHITE
            }
            line.addView(label(row.label + "\n" + row.source, 13f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            val kind = when (row.kind) {
                "state" -> "Полученное состояние"
                "stale" -> "Последнее значение (не актуальное)"
                "advertised" -> "Объявлено ГУ"
                "unknown" -> "Не установлено"
                "info" -> "Параметр ГУ"
                "observed" -> "Получено"
                else -> row.kind
            }
            line.addView(label(row.value + "\n" + kind + if (row.at > 0) "\n" + date.format(Date(row.at)) else "",
                13f, color), LinearLayout.LayoutParams(0, -2, 1f))
            table.addView(line)
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 752 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        exportInProgress = true
        Thread {
            val result = runCatching {
                val json = HuLiveData.report().toString(2)
                contentResolver.openOutputStream(uri, "w")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("Destination unavailable")
            }
            runOnUiThread {
                exportInProgress = false
                if (!isDestroyed) Toast.makeText(this, if (result.isSuccess) "JSON сохранён" else
                    "Ошибка сохранения: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
