package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dev.caraalink.R
import dev.caraalink.aa.AaSessionProbe
import dev.caraalink.aa.AaTlsReceiver
import dev.caraalink.aa.AaVersionProbe

/** ADB-only early protocol probes. Not compiled into the release variant. */
internal object LegacyAaDiagnostics {
    private const val TAG = "LegacyAaDiagnostics"
    fun handle(activity: Activity, intent: Intent?) { with(activity) {
        val probe = intent?.getStringExtra("aa_probe")
        if (probe != null) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                when (probe) {
                    "stop" -> { AaVersionProbe.active?.stop(); AaSessionProbe.active?.stop() }
                    "session" -> if (AaSessionProbe.active == null) {
                        val cfg = AaSessionProbe.Config(
                            versionMajor = intent.getIntExtra("aa_vmajor", -1).takeIf { it >= 0 },
                            versionMinor = intent.getIntExtra("aa_vminor", -1).takeIf { it >= 0 },
                        )
                        AaSessionProbe(this, cfg).start()
                    }
                    else -> if (AaVersionProbe.active == null) AaVersionProbe(this).start()
                }
            }
            return@with
        }

        val op = intent?.getStringExtra("aa_tls") ?: return@with
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "aa_tls='$op' ignored: not a debuggable build")
            return@with
        }

        if (op == "stop") {
            Log.i(TAG, "aa_tls: stopping receiver")
            AaTlsReceiver.active?.stop()
            Toast.makeText(this, getString(R.string.tls_stopped), Toast.LENGTH_SHORT).show()
            return@with
        }

        if (AaTlsReceiver.active != null) {
            Log.w(TAG, "aa_tls: receiver already running, ignoring '$op'")
            Toast.makeText(this, getString(R.string.tls_running), Toast.LENGTH_SHORT).show()
            return@with
        }

        val mode = when (intent.getStringExtra("aa_mode")?.lowercase()) {
            "listen" -> AaTlsReceiver.Mode.LISTEN
            else -> AaTlsReceiver.Mode.DIAL
        }
        val role = when (intent.getStringExtra("aa_role")?.lowercase()) {
            "server" -> AaTlsReceiver.TlsRole.SERVER
            "client" -> AaTlsReceiver.TlsRole.CLIENT
            else -> AaTlsReceiver.TlsRole.AUTO
        }
        val config = AaTlsReceiver.Config(
            mode = mode,
            host = intent.getStringExtra("aa_host") ?: "127.0.0.1",
            port = intent.getIntExtra("aa_port", 5277),
            tlsRole = role,
        )
        Log.i(TAG, "aa_tls: starting receiver $config")
        AaTlsReceiver(this, config).start()
        Toast.makeText(this, getString(R.string.tls_status, mode.name, role.name), Toast.LENGTH_SHORT).show()
    }
    }

}
