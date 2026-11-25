package dev.caraalink.link

import android.location.Location
import android.location.LocationManager
import android.os.Build

/** GPS velocity only: never a source of parking-brake or gear state. */
internal object PhoneGpsSpeed {
    private const val MAX_AGE_NS = 3_000_000_000L
    private const val MAX_SPEED_ERROR_MPS = 1.5f

    data class Sample(val metersPerSecond: Float, val elapsedNanos: Long) {
        fun freshAt(nowNanos: Long): Boolean =
            elapsedNanos > 0 && nowNanos >= elapsedNanos && nowNanos - elapsedNanos <= MAX_AGE_NS
    }

    fun validate(speed: Float, accuracy: Float, elapsedNanos: Long, nowNanos: Long): Sample? {
        if (!speed.isFinite() || speed < 0f || !accuracy.isFinite() ||
            accuracy < 0f || accuracy > MAX_SPEED_ERROR_MPS) return null
        return Sample(speed, elapsedNanos).takeIf { it.freshAt(nowNanos) }
    }

    @Suppress("DEPRECATION")
    fun fromLocation(location: Location, nowNanos: Long): Sample? {
        if (location.provider != LocationManager.GPS_PROVIDER ||
            !location.hasSpeed() || !location.hasSpeedAccuracy()) return null
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        if (mock) return null
        return validate(location.speed, location.speedAccuracyMetersPerSecond, location.elapsedRealtimeNanos, nowNanos)
    }
}
