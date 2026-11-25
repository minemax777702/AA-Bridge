package dev.caraalink.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.caraalink.BuildConfig
import dev.caraalink.R
import dev.caraalink.aa.AaReconnectBus
import dev.caraalink.aa.AaServiceDiscovery
import dev.caraalink.diagnostics.DeveloperToolsActivity
import dev.caraalink.connection.CarLink
import dev.caraalink.ui.rememberConnection
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AaBridgeTheme { AdvancedSettings(onBack = { finish() }) } }
    }
}

private val reconnectKeys = setOf(
    "car_orientation", "video_resolution_mode", "video_resolution_custom_width", "video_resolution_custom_height",
    "video_frame_rate", "video_dpi_auto", "video_dpi_manual", "aa_auto_crop", "video_downscale_720p",
    "aa_audio_route", "aa_carbit_mic", "aa_fill_mode", AppSettings.PARKING_MODE_KEY,
)

@Composable
private fun AdvancedSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { AppSettings.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val values = remember(revision) { prefs.all }
    fun string(key: String, fallback: String) = values[key] as? String ?: fallback
    fun bool(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
    fun reconnect() {
        AaReconnectBus.onVideoSettingsChanged?.let {
            Toast.makeText(context, R.string.pref_reconnecting, Toast.LENGTH_SHORT).show()
            it()
        }
    }
    fun setString(key: String, value: String) {
        if (prefs.getString(key, null) == value) return
        prefs.edit().putString(key, value).apply()
        if (key in reconnectKeys) reconnect()
    }
    fun setBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        if (key in reconnectKeys) reconnect()
    }

    val custom = string("video_resolution_mode", "auto") == "custom"
    val autoDpi = bool("video_dpi_auto", true)
    val carbitAudio = string("aa_audio_route", "phone_direct") == "carbit_experimental"
    val connection = rememberConnection()
    val parkingAvailable = BuildConfig.DEBUG && connection.link == CarLink.NONE
    val density = remember(revision) { AaServiceDiscovery.configFromSettings(context).density }

    SubScreen(stringResource(R.string.settings_advanced), onBack) {
        SettingsSection(R.string.pref_category_video_title) {
            ChoiceSetting(R.string.pref_car_orientation_title, R.string.pref_car_orientation_summary,
                string("car_orientation", "auto"), R.array.car_orientation_entries, R.array.car_orientation_values) {
                setString("car_orientation", it)
            }
            ChoiceSetting(R.string.pref_video_resolution_mode_title, R.string.pref_video_resolution_mode_summary,
                string("video_resolution_mode", "auto"), R.array.resolution_mode_entries, R.array.resolution_mode_values) {
                setString("video_resolution_mode", it)
            }
            if (custom) {
                NumberSetting(R.string.pref_video_resolution_custom_width_title,
                    string("video_resolution_custom_width", ""), 1..8192) { setString("video_resolution_custom_width", it) }
                NumberSetting(R.string.pref_video_resolution_custom_height_title,
                    string("video_resolution_custom_height", ""), 1..8192) { setString("video_resolution_custom_height", it) }
            } else {
                ToggleSetting(stringResource(R.string.pref_video_downscale_720p_title),
                    stringResource(R.string.pref_video_downscale_720p_summary), bool("video_downscale_720p", true)) {
                    setBool("video_downscale_720p", it)
                }
            }
            ChoiceSetting(R.string.pref_video_frame_rate_title, null,
                if (string("video_frame_rate", "30").toIntOrNull()?.let { it >= 60 } == true) "60" else "30",
                R.array.frame_rate_entries, R.array.frame_rate_values) { setString("video_frame_rate", it) }
            NumberSetting(R.string.pref_video_bitrate_kbps_title, string("video_bitrate_kbps", "6000"), 1..100000) {
                setString("video_bitrate_kbps", it)
            }
            ToggleSetting(stringResource(R.string.pref_video_dpi_auto_title), stringResource(R.string.pref_dpi_auto_summary, density), autoDpi) {
                setBool("video_dpi_auto", it)
            }
            if (!autoDpi) {
                NumberSetting(R.string.pref_video_dpi_manual_title, string("video_dpi_manual", "160"), 1..1000) {
                    setString("video_dpi_manual", it)
                }
            }
            ChoiceSetting(R.string.pref_aa_fill_mode_title, R.string.pref_aa_fill_mode_summary,
                string("aa_fill_mode", "fit"), R.array.aa_fill_mode_entries, R.array.aa_fill_mode_values) { setString("aa_fill_mode", it) }
            if (string("aa_fill_mode", "fit") == "fit") {
                ToggleSetting(stringResource(R.string.pref_aa_auto_crop_title), stringResource(R.string.pref_aa_auto_crop_summary),
                    bool("aa_auto_crop", true)) { setBool("aa_auto_crop", it) }
            }
        }
        SettingsSection(R.string.pref_aa_audio_route_title) {
            ChoiceSetting(R.string.pref_aa_audio_route_title, R.string.pref_aa_audio_route_summary,
                string("aa_audio_route", "phone_direct"), R.array.aa_audio_route_entries, R.array.aa_audio_route_values) { setString("aa_audio_route", it) }
            if (carbitAudio) {
                ToggleSetting(stringResource(R.string.pref_aa_carbit_mic_title), stringResource(R.string.pref_aa_carbit_mic_summary),
                    bool("aa_carbit_mic", false)) { setBool("aa_carbit_mic", it) }
            }
        }
        SettingsSection(R.string.pref_category_android_auto_title) {
            ToggleSetting(stringResource(R.string.pref_parking_mode_title),
                stringResource(if (parkingAvailable) R.string.pref_parking_mode_test else R.string.pref_parking_mode_unavailable),
                parkingAvailable && bool(AppSettings.PARKING_MODE_KEY, true), enabled = parkingAvailable) {
                setBool(AppSettings.PARKING_MODE_KEY, it)
            }
            ProfileSetting(onLoaded = { reconnect() })
        }
        SettingsSection(R.string.pref_category_debug_title) {
            if (BuildConfig.DEBUG) {
                ToggleSetting(stringResource(R.string.pref_debug_verbose_logging_title),
                    stringResource(R.string.pref_debug_verbose_logging_summary), bool("debug_verbose_logging", false)) {
                    setBool("debug_verbose_logging", it)
                }
                ToggleSetting(stringResource(R.string.pref_debug_overlay_title),
                    stringResource(R.string.pref_debug_overlay_summary), bool("debug_overlay", false)) {
                    setBool("debug_overlay", it)
                }
            }
            ToggleSetting(stringResource(R.string.pref_auto_save_crash_log_title),
                stringResource(R.string.pref_auto_save_crash_log_summary), bool("auto_save_crash_log", true)) {
                setBool("auto_save_crash_log", it)
            }
            SettingRow(stringResource(R.string.pref_developer_tools_title), stringResource(R.string.pref_developer_tools_summary)) {
                context.startActivity(Intent(context, DeveloperToolsActivity::class.java))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(title), color = Dash.Muted, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = Dash.Tile) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingRow(title: String, summary: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it, color = Dash.Muted) } },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ToggleSetting(title: String, summary: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary, color = Dash.Muted) },
        trailingContent = { Switch(checked = checked, onCheckedChange = change, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
private fun ChoiceSetting(title: Int, summary: Int?, value: String, labelsId: Int, valuesId: Int, change: (String) -> Unit) {
    val labels = stringArrayResource(labelsId)
    val values = stringArrayResource(valuesId)
    var open by remember { mutableStateOf(false) }
    val selected = values.indexOf(value).takeIf { it >= 0 } ?: 0
    SettingRow(stringResource(title), labels.getOrNull(selected)) { open = true }
    if (open) {
        AlertDialog(onDismissRequest = { open = false },
            title = { Text(stringResource(title)) },
            text = {
                Column {
                    if (summary != null) Text(stringResource(summary), color = Dash.Muted,
                        modifier = Modifier.padding(bottom = 8.dp))
                    values.forEachIndexed { index, option ->
                        Row(Modifier.fillMaxWidth().clickable { change(option); open = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = index == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(labels.getOrElse(index) { option })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NumberSetting(title: Int, value: String, range: IntRange, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(value) }
    SettingRow(stringResource(title), value.ifBlank { "—" }) { input = value; open = true }
    if (open) {
        val valid = input.toIntOrNull()?.let { it in range } == true
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(title)) },
            text = { OutlinedTextField(value = input, onValueChange = { input = it },
                singleLine = true, isError = input.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("${range.first} – ${range.last}") }) },
            confirmButton = {
                TextButton(enabled = valid, onClick = { change(input.toInt().toString()); open = false }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProfileSetting(onLoaded: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(CarProfiles.listProfiles(context)) }
    var open by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    SettingRow(stringResource(R.string.pref_car_profiles_title), stringResource(R.string.pref_car_profiles_summary)) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.profile_title)) },
        text = {
            Column {
                profiles.forEach { name -> SettingRow(name) { open = false; selected = name } }
                SettingRow(stringResource(R.string.profile_save_new)) { open = false; input = ""; save = true }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } })
    if (save) AlertDialog(onDismissRequest = { save = false }, title = { Text(stringResource(R.string.profile_save_title)) },
        text = { OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.profile_name_hint)) }) },
        confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = {
            CarProfiles.saveProfile(context, input.trim()); profiles = CarProfiles.listProfiles(context); save = false
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { save = false }) { Text(stringResource(R.string.action_cancel)) } })
    selected?.let { name ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(name) },
            confirmButton = { TextButton(onClick = {
                if (CarProfiles.loadProfile(context, name)) onLoaded()
                selected = null
            }) { Text(stringResource(R.string.action_load)) } },
            dismissButton = { TextButton(onClick = {
                CarProfiles.deleteProfile(context, name); profiles = CarProfiles.listProfiles(context); selected = null
            }) { Text(stringResource(R.string.action_delete)) } })
    }
}
