package dev.caraalink.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.settings.AppSettings
import org.json.JSONObject

/**
 * Answers the head unit's own feature requests on the PXC main channel.
 * Reply formats CONFIRMED from CarbitLink's handlers (claude/roadmap.md):
 * - 0x10430 QUERY_GPS -> {"status", "longitude", "latitude", "speed",
 *   "altitude", "course", "time", address fields} (pp/m0.java)
 * - 0x10750 CHECK_APP_NETWORKING_CAPABILITY -> {"status": 0|1} (pp/i.java)
 */
class CarFeatures(private val context: Context) {

    @Volatile private var last: Location? = null
    @Volatile private var gpsFix: Pair<Location, PhoneGpsSpeed.Sample>? = null
    private val listener = LocationListener { location ->
        if (!started) return@LocationListener
        last = location
        if (location.provider == LocationManager.GPS_PROVIDER) {
            val sample = if (AppSettings.phoneGpsSpeed(context)) PhoneGpsSpeed.fromLocation(location, SystemClock.elapsedRealtimeNanos()) else null
            gpsFix = sample?.let { Location(location) to it }
            ConnectionStateHolder.update { it.copy(phoneGpsSpeedMps = sample?.metersPerSecond,
                phoneGpsSpeedElapsedNanos = sample?.elapsedNanos ?: 0L) }
        }
    }
    @Volatile private var started = false

    fun start() {
        if (started || !hasLocation()) return
        val lm = context.getSystemService(LocationManager::class.java)
        started = true
        try {
            lm.getProviders(true).forEach { p ->
                lm.getLastKnownLocation(p)?.let { if (it.time > (last?.time ?: Long.MIN_VALUE)) last = it }
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            stop()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.getSystemService(LocationManager::class.java).removeUpdates(listener) }
        gpsFix = null
        last = null
        ConnectionStateHolder.update { it.copy(phoneGpsSpeedMps = null, phoneGpsSpeedElapsedNanos = 0L) }
    }

    fun replyBody(cmd: Int): ByteArray? = when (cmd) {
        QUERY_GPS -> gps().toString().toByteArray(Charsets.UTF_8)
        CHECK_NETWORK -> JSONObject().put("status", if (online()) 1 else 0).toString().toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun gps(): JSONObject {
        val gpsEnabled = AppSettings.phoneGpsSpeed(context)
        val fix = if (gpsEnabled) gpsFix?.takeIf { it.second.freshAt(SystemClock.elapsedRealtimeNanos()) } else null
        val loc = (if (gpsEnabled) fix?.first else last) ?: return JSONObject().put("status", false)
        val speed = if (gpsEnabled) fix?.second?.metersPerSecond else if (loc.hasSpeed()) loc.speed else null
        return JSONObject()
            .put("status", true)
            .put("longitude", loc.longitude)
            .put("latitude", loc.latitude)
            .put("speed", speed?.toDouble())
            .put("altitude", loc.altitude)
            .put("course", loc.bearing.toDouble())
            .put("time", loc.time)
            .put("province", "").put("city", "").put("district", "")
            .put("road", "").put("number", "").put("poiname", "")
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasLocation() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val QUERY_GPS = 0x10430
        const val CHECK_NETWORK = 0x10750
    }
}
