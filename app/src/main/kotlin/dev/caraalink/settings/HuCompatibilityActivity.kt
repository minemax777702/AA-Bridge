package dev.caraalink.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.caraalink.BuildConfig
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.diagnostics.HuCompatibility
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HuCompatibilityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AaBridgeTheme { CompatibilityScreen { finish() } } }
    }
}

internal val routeLabels = mapOf(
    "phone_direct" to R.string.hu_audio_phone_direct,
    "bt_preferred" to R.string.hu_audio_bluetooth,
    "device_only" to R.string.hu_audio_device,
    "carbit_experimental" to R.string.hu_audio_pcm,
)

@Composable
private fun CompatibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { AppSettings.prefs(context) }
    var auto by remember { mutableStateOf(AppSettings.huAuto(context)) }
    var route by remember { mutableStateOf(prefs.getString("aa_audio_route", "phone_direct") ?: "phone_direct") }
    var mic by remember { mutableStateOf(prefs.getBoolean("aa_carbit_mic", false)) }
    var gestures by remember { mutableStateOf(prefs.getString(AppSettings.HU_GESTURES_KEY, "AUTO") ?: "AUTO") }
    var capture by remember { mutableStateOf(AppSettings.huCapture(context)) }
    var expandedRoute by remember { mutableStateOf<String?>(if (auto) null else route) }
    val evidence by HuCompatibility.state.collectAsState()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    val resultStore = remember(context) { context.getSharedPreferences("hu_test_results", android.content.Context.MODE_PRIVATE) }
    // Name is informational only; not a verified unique identifier.
    val signature = "$auto|$route|$mic|$gestures"
    val canRate = evidence.connected && evidence.appliedProfile == signature && evidence.screen.isNotBlank()
    val resultKey = "${evidence.protocol}|${evidence.name}|$signature"
    var resultRevision by remember { mutableIntStateOf(0) }

    fun applyProfile() {
        prefs.edit().putBoolean(AppSettings.HU_AUTO_KEY, auto).putString("aa_audio_route", route)
            .putBoolean("aa_carbit_mic", mic).putString(AppSettings.HU_GESTURES_KEY, gestures).apply()
        DiagnosticLogger(context, "hu-compatibility").log("Profile selected: auto=$auto audio=$route remoteMic=$mic gestures=$gestures; reconnect HU")
    }
    fun select(profile: HuTestProfiles.Profile) {
        auto = profile.auto
        route = profile.route
        mic = profile.remoteMic
        gestures = profile.gestures
        expandedRoute = if (profile.auto) null else profile.route
        applyProfile()
    }
    SubScreen(stringResource(R.string.hu_compat_title), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                context.startActivity(Intent(context, HuWizardActivity::class.java))
            }) { Text(stringResource(R.string.hu_wizard_title)) }
            CompatibilityTile(R.string.hu_profiles) {
                Text(stringResource(R.string.hu_reconnect), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = auto, onClick = { select(HuTestProfiles.automatic) })
                    TextButton(onClick = { select(HuTestProfiles.automatic) }) { Text(stringResource(R.string.hu_profile_auto)) }
                }
                Text(stringResource(R.string.hu_profile_count, HuTestProfiles.manual.size), color = Dash.Muted,
                    style = MaterialTheme.typography.bodySmall)
                HuTestProfiles.routes.forEach { group ->
                    HorizontalDivider(color = Dash.TileBorder)
                    val expanded = expandedRoute == group
                    val activeGroup = !auto && route == group
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { expandedRoute = if (expanded) null else group }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(routeLabels.getValue(group)))
                                if (activeGroup) Text(stringResource(R.string.hu_profile_combination,
                                    stringResource(if (mic) R.string.hu_profile_mic_hu else R.string.hu_profile_mic_phone),
                                    stringResource(if (gestures == "SINGLE") R.string.hu_gesture_single else R.string.hu_gesture_multi)),
                                    style = MaterialTheme.typography.bodySmall, color = Dash.Muted)
                            }
                            Text(if (expanded) "−" else "+")
                        }
                    }
                    if (expanded) HuTestProfiles.manual.filter { it.route == group }.forEach { profile ->
                        val selected = profile.matches(auto, route, mic, gestures)
                        val label = stringResource(R.string.hu_profile_combination,
                            stringResource(if (profile.remoteMic) R.string.hu_profile_mic_hu else R.string.hu_profile_mic_phone),
                            stringResource(if (profile.gestures == "SINGLE") R.string.hu_gesture_single else R.string.hu_gesture_multi))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = { select(profile) })
                            TextButton(onClick = { select(profile) }) { Text(label) }
                        }
                    }
                }
            }
            CompatibilityTile(R.string.hu_manual) {
                if (auto) Text(stringResource(R.string.hu_auto_summary), color = Dash.Muted)
                val routes = listOf("phone_direct" to R.string.hu_audio_phone_direct,
                    "bt_preferred" to R.string.hu_audio_bluetooth, "device_only" to R.string.hu_audio_device,
                    "carbit_experimental" to R.string.hu_audio_pcm)
                Text(stringResource(R.string.pref_aa_audio_route_title), style = MaterialTheme.typography.titleSmall)
                routes.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !auto && route == value, onClick = { auto = false; route = value; applyProfile() })
                        TextButton(onClick = { auto = false; route = value; applyProfile() }) { Text(stringResource(label)) }
                    }
                }
                HorizontalDivider(color = Dash.TileBorder)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.hu_mic_remote), modifier = Modifier.weight(1f))
                    Switch(checked = if (auto) evidence.micDeclared == true else mic, onCheckedChange = {
                        auto = false; mic = it; applyProfile()
                    })
                }
                Text(stringResource(R.string.hu_mic_fallback), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.hu_gestures), style = MaterialTheme.typography.titleSmall)
                listOf("AUTO" to R.string.hu_gesture_auto, "MULTI" to R.string.hu_gesture_multi, "SINGLE" to R.string.hu_gesture_single).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (if (auto) "AUTO" else gestures) == value, onClick = { auto = false; gestures = value; applyProfile() })
                        TextButton(onClick = { auto = false; gestures = value; applyProfile() }) { Text(stringResource(label)) }
                    }
                }
            }
            CompatibilityTile(R.string.hu_detected) {
                Text(stringResource(if (evidence.connected) R.string.hu_connected else R.string.hu_last_session), color = Dash.Muted)
                Text(evidence.protocol.ifBlank { "—" })
                if (evidence.name.isNotBlank()) Text(evidence.name)
                if (evidence.screen.isNotBlank()) Text(evidence.screen)
                Text(evidence.capabilities.ifBlank { stringResource(R.string.hu_no_capabilities) })
                EvidenceRow(R.string.hu_declared_mic, evidence.micDeclared)
                EvidenceRow(R.string.hu_audio_ack, evidence.playbackAck.takeIf { it })
                EvidenceRow(R.string.hu_mic_ack, evidence.micAck.takeIf { it })
                EvidenceRow(R.string.hu_mic_pcm, evidence.micPcm.takeIf { it })
                EvidenceRow(R.string.hu_multitouch_observed, evidence.multiTouch.takeIf { it })
                Text(stringResource(R.string.hu_message_count, evidence.messages), color = Dash.Muted)
                Text(stringResource(R.string.hu_test_hint), style = MaterialTheme.typography.bodySmall, color = Dash.Muted)
                if (!canRate) Text(stringResource(R.string.hu_reconnect), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                listOf("music" to R.string.hu_check_music, "guidance" to R.string.hu_check_guidance,
                    "voice" to R.string.hu_check_voice, "gestures" to R.string.hu_gestures).forEach { (key, label) ->
                    val saved = remember(resultKey, key, resultRevision) { resultStore.getString("$resultKey|$key", null) }
                    Text(stringResource(label) + if (saved == null) "" else " · " + stringResource(if (saved == "pass") R.string.hu_test_pass else R.string.hu_test_fail),
                        style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = canRate, onClick = {
                            resultStore.edit().putString("$resultKey|$key", "pass").apply(); resultRevision++
                            DiagnosticLogger(context, "hu-compatibility").log("User verdict: $key pass; $resultKey")
                        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.hu_test_pass)) }
                        OutlinedButton(enabled = canRate, onClick = {
                            resultStore.edit().putString("$resultKey|$key", "fail").apply(); resultRevision++
                            DiagnosticLogger(context, "hu-compatibility").log("User verdict: $key fail; $resultKey")
                        }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.hu_test_fail)) }
                    }
                }
            }
            CompatibilityTile(R.string.hu_vehicle) {
                if (evidence.vehicleFields.isEmpty()) Text(stringResource(R.string.hu_no_vehicle_fields), color = Dash.Muted)
                else evidence.vehicleFields.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.hu_unverified), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
            }
            CompatibilityTile(R.string.hu_capture) {
                if (BuildConfig.DEBUG) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.hu_capture), modifier = Modifier.weight(1f))
                        Switch(checked = capture, onCheckedChange = {
                            capture = it
                            prefs.edit().putBoolean(AppSettings.HU_CAPTURE_KEY, it).apply()
                            DiagnosticLogger(context, "hu-compatibility").log("Extended control capture enabled=$it")
                        })
                    }
                    Text(stringResource(R.string.hu_capture_summary), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                } else Text(stringResource(R.string.hu_capture_debug_only), color = Dash.Muted)
                OutlinedButton(enabled = !exporting, modifier = Modifier.fillMaxWidth(), onClick = {
                    exporting = true
                    exportFailed = false
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { DiagnosticLogger(context, "hu-compatibility").shareAllLogsIntent() }
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (_: Exception) { exportFailed = true }
                        finally { exporting = false }
                    }
                }) { Text(stringResource(R.string.home_send_logs)) }
                if (exportFailed) Text(stringResource(R.string.hu_export_failed), color = Dash.Amber)
            }
        }
    }
}

@Composable
private fun CompatibilityTile(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Dash.Tile, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun EvidenceRow(label: Int, value: Boolean?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Text(stringResource(when (value) { true -> R.string.hu_yes; false -> R.string.hu_no; null -> R.string.hu_unknown }), color = Dash.Muted)
    }
}
