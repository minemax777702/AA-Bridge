package dev.caraalink.settings

/**
 * Every distinct existing manual behavior: four audio routes, two microphone
 * preferences, two touch filters. AUTO and MULTI gestures use the same native
 * slot path, so do not duplicate them as separate presets.
 */
internal object HuTestProfiles {
    data class Profile(
        val route: String,
        val remoteMic: Boolean,
        val gestures: String,
        val auto: Boolean = false,
    ) {
        val signature: String get() = "$auto|$route|$remoteMic|$gestures"

        fun matches(auto: Boolean, route: String, remoteMic: Boolean, gestures: String): Boolean =
            if (this.auto) auto
            else !auto && this.route == route && this.remoteMic == remoteMic &&
                this.gestures == (if (gestures == "AUTO") "MULTI" else gestures)
    }

    val routes = listOf("phone_direct", "bt_preferred", "device_only", "carbit_experimental")
    val automatic = Profile("bt_preferred", false, "AUTO", true)
    val manual = routes.flatMap { route ->
        listOf(false, true).flatMap { remoteMic ->
            listOf("MULTI", "SINGLE").map { gestures -> Profile(route, remoteMic, gestures) }
        }
    }
    val all = listOf(automatic) + manual
}
