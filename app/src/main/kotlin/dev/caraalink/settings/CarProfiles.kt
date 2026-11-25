package dev.caraalink.settings

import android.content.Context
import org.json.JSONObject

/**
 * Named snapshots of the video/AA settings that actually vary per car
 * (screen shape, resolution, DPI, fill mode, bitrate, audio routing) --
 * multi-car management from claude/roadmap.md's feasibility check, scoped
 * down to "save/load a settings snapshot by name" rather than a full
 * per-car device/connection registry, which this app has no concept of
 * (CarbitLink doesn't identify *which* car is connected, just that one is).
 */
object CarProfiles {
    private const val PREFS_FILE = "car_profiles"

    /** Keys worth remembering per car; everything else in Settings is phone-wide, not car-specific. */
    private val PROFILE_DEFAULTS = mapOf(
        "car_orientation" to "auto",
        "video_resolution_mode" to "auto",
        "video_resolution_custom_width" to JSONObject.NULL,
        "video_resolution_custom_height" to JSONObject.NULL,
        "video_frame_rate" to "30",
        "video_bitrate_kbps" to "6000",
        "video_dpi_auto" to true,
        "video_dpi_manual" to "160",
        "video_downscale_720p" to true,
        "aa_fill_mode" to "fit",
        "aa_auto_crop" to true,
        "aa_audio_route" to "phone_direct",
        "aa_carbit_mic" to false,
    )

    private fun store(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private fun logger(context: Context) = dev.caraalink.diagnostics.DiagnosticLogger(context, "car-profiles")

    fun listProfiles(context: Context): List<String> =
        store(context).all.keys.sorted()

    fun saveProfile(context: Context, name: String) {
        if (name.isBlank()) return
        val values = AppSettings.prefs(context).all
        val snapshot = JSONObject()
        for ((key, default) in PROFILE_DEFAULTS) {
            snapshot.put(key, values[key] ?: default)
        }
        store(context).edit().putString(name.trim(), snapshot.toString()).apply()
        logger(context).log("saved profile '${name.trim()}': $snapshot")
    }

    /** Returns false if [name] isn't a saved profile. */
    fun loadProfile(context: Context, name: String): Boolean {
        val json = store(context).getString(name, null) ?: return false
        val snapshot = JSONObject(json)
        val defaults = AppSettings.prefs(context)
        val editor = defaults.edit()
        for ((key, default) in PROFILE_DEFAULTS) {
            // Old profiles did not include every key; retain their existing behavior.
            if (!snapshot.has(key)) continue
            if (snapshot.isNull(key)) {
                editor.remove(key)
                continue
            }
            val value = snapshot.get(key)
            when {
                default is Boolean && value is Boolean -> editor.putBoolean(key, value)
                default !is Boolean && value is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        logger(context).log("loaded profile '$name': $snapshot")
        return true
    }

    fun deleteProfile(context: Context, name: String) {
        store(context).edit().remove(name).apply()
        logger(context).log("deleted profile '$name'")
    }
}

