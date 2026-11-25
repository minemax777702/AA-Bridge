package dev.caraalink.emulator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private var client: HuClient? = null
    private lateinit var screen: SurfaceView
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var resolution: Spinner
    private lateinit var music: CheckBox
    private lateinit var tts: CheckBox
    private lateinit var vr: CheckBox
    private lateinit var talkie: CheckBox
    private lateinit var mic: CheckBox
    private lateinit var drop: CheckBox
    private lateinit var delay: CheckBox
    private var width = 1280
    private var height = 720
    private var generation = 0
    private val contacts = linkedMapOf<Int, Pair<Int, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host = EditText(this).apply { hint = "IP телефона (Wi-Fi) / ADB: 127.0.0.1"; isSingleLine = true }
        controls.addView(host)
        code = EditText(this).apply {
            hint = "Код сеанса (для Wi-Fi)"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        controls.addView(code)
        resolution = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("1280 × 720", "1920 × 720", "1920 × 1080"))
        }
        controls.addView(resolution)
        val flags = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun flag(label: String, value: Boolean) = CheckBox(this).apply { text = label; isChecked = value; flags.addView(this) }
        music = flag("Music", true); tts = flag("TTS", true); vr = flag("VR", true)
        talkie = flag("Talkie", false); mic = flag("Mic", false)
        controls.addView(HorizontalScrollView(this).apply { addView(flags) })
        val faults = LinearLayout(this)
        drop = CheckBox(this).apply { text = "Потеря ACK" }
        delay = CheckBox(this).apply { text = "ACK +1500 мс" }
        faults.addView(drop); faults.addView(delay); controls.addView(faults)
        val actions = LinearLayout(this)
        connect = Button(this).apply { text = "Подключить"; setOnClickListener {
            if (client != null) disconnect() else if (mic.isChecked &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            } else startConnection()
        } }
        actions.addView(connect)
        actions.addView(Button(this).apply { text = "Home"; setOnClickListener { client?.touch(4,0,0,0,1042) } })
        actions.addView(Button(this).apply { text = "Back"; setOnClickListener { client?.touch(4,0,0,0,1044) } })
        controls.addView(actions)
        layout.addView(controls)
        screen = object : SurfaceView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = View.MeasureSpec.getSize(widthMeasureSpec)
                val h = View.MeasureSpec.getSize(heightMeasureSpec)
                val scale = minOf(w.toFloat() / this@MainActivity.width, h.toFloat() / this@MainActivity.height)
                setMeasuredDimension((this@MainActivity.width * scale).toInt(), (this@MainActivity.height * scale).toInt())
            }
        }
        // Keep the content rectangle and touch coordinate mapping identical.
        val frame = FrameLayout(this)
        frame.addView(screen, FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER))
        layout.addView(frame, LinearLayout.LayoutParams(-1,0,1f))
        status = TextView(this).apply { text = "Нет подключения"; maxLines = 5; textSize = 12f }
        layout.addView(status)
        screen.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { connect.isEnabled = true }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { disconnect(); connect.isEnabled = false }
        })
        connect.isEnabled = false
        screen.setOnTouchListener { _, event -> sendTouch(event); true }
        setContentView(layout)
    }

    private fun startConnection() {
        val address = host.text.toString().trim()
        if (address.isEmpty() || !screen.holder.surface.isValid) return
        val adb = address == "127.0.0.1"
        val key = if (adb) null else dev.caraalink.protocol.EmulatorLanAuth.parseCode(code.text.toString())
        if (!adb && (key == null || !Wire.isIpv4(address))) {
            status.text = "Введите IPv4-адрес телефона и 32-значный код сеанса."
            return
        }
        val (w,h) = when(resolution.selectedItemPosition) { 1 -> 1920 to 720; 2 -> 1920 to 1080; else -> 1280 to 720 }
        width = w; height = h; screen.requestLayout()
        val id = ++generation
        connect.text = "Отключить"
        setConfigEnabled(false)
        val options = HuClient.Config(w,h,music.isChecked,tts.isChecked,vr.isChecked,talkie.isChecked,mic.isChecked,
            if (delay.isChecked) 1500L else 0L,drop.isChecked)
        client = HuClient(this,address,key,options,screen.holder.surface,
            { line -> runOnUiThread { if (id == generation) status.text = (status.text.toString() + "\n" + line).lines().takeLast(5).joinToString("\n") } },
            { runOnUiThread { if (id == generation) resetUi() } }).also { it.start() }
        status.text = "Подключение к $address"
    }

    private fun setConfigEnabled(enabled: Boolean) {
        listOf<View>(host,code,resolution,music,tts,vr,talkie,mic,drop,delay).forEach { it.isEnabled = enabled }
    }

    private fun sendTouch(event: MotionEvent) {
        val session = client ?: return
        fun point(i: Int): Pair<Int,Int> =
            (event.getX(i) * width / screen.width.coerceAtLeast(1)).toInt().coerceIn(0,width-1) to
                (event.getY(i) * height / screen.height.coerceAtLeast(1)).toInt().coerceIn(0,height-1)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex; val id = event.getPointerId(i); val p = point(i)
                contacts[id] = p; session.touch(2,p.first,p.second,id)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i); val p = point(i)
                if (id in contacts) { contacts[id] = p; session.touch(3,p.first,p.second,id) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex; val id = event.getPointerId(i); val p = point(i)
                contacts.remove(id); session.touch(1,p.first,p.second,id)
            }
            MotionEvent.ACTION_CANCEL -> {
                contacts.forEach { (id,p) -> session.touch(1,p.first,p.second,id) }
                contacts.clear()
            }
        }
    }

    private fun resetUi() {
        client = null; contacts.clear(); connect.text = "Подключить"; setConfigEnabled(true)
        status.append("\nОтключено")
    }

    private fun disconnect() {
        generation++
        val old = client
        resetUi()
        Thread { old?.close() }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if (requestCode == 10 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED && !isFinishing) startConnection()
    }
    override fun onStop() { disconnect(); super.onStop() }
}
