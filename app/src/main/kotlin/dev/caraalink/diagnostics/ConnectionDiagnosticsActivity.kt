package dev.caraalink.diagnostics

import dev.caraalink.R

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder

/**
 * Live view of [ConnectionStateHolder] — phase, accessory identity, byte
 * counters, last error, reconnect attempts. Meant to be glanced at while
 * actually plugged into the car, instead of pulling adb logcat.
 */
class ConnectionDiagnosticsActivity : Activity() {

    private lateinit var statusView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener: (ConnectionSnapshot) -> Unit = { snapshot ->
        mainHandler.post { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#F2F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        val pad = dp(24)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1116"))
            addView(TextView(this@ConnectionDiagnosticsActivity).apply {
                text = getString(R.string.dev_diagnostics)
                setTextColor(Color.parseColor("#F2F5F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, 0, dp(20))
            })
            addView(statusView)
        })
        render(ConnectionStateHolder.snapshot)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onResume() {
        super.onResume()
        ConnectionStateHolder.addListener(listener)
        render(ConnectionStateHolder.snapshot)
    }

    override fun onPause() {
        ConnectionStateHolder.removeListener(listener)
        super.onPause()
    }

    private fun render(snapshot: ConnectionSnapshot) {
        statusView.text = buildString {
            appendLine(getString(R.string.diag_phase, getString(when (snapshot.phase) {
                ConnectionPhase.DISCONNECTED -> R.string.diag_phase_disconnected
                ConnectionPhase.ATTACHED -> R.string.diag_phase_attached
                ConnectionPhase.SNIFFING -> R.string.diag_phase_sniffing
                ConnectionPhase.STREAMING -> R.string.diag_phase_streaming
                ConnectionPhase.RECONNECTING -> R.string.diag_phase_reconnecting
                ConnectionPhase.ERROR -> R.string.diag_phase_error
            })))
            appendLine(getString(R.string.diag_accessory, snapshot.accessoryManufacturer ?: "-", snapshot.accessoryModel ?: "-"))
            appendLine(getString(R.string.diag_read, snapshot.bytesRead))
            appendLine(getString(R.string.diag_written, snapshot.bytesWritten))
            appendLine(getString(R.string.diag_reconnect, snapshot.reconnectAttempts))
            appendLine(getString(R.string.diag_error, snapshot.lastError ?: "-"))
        }
    }
}
