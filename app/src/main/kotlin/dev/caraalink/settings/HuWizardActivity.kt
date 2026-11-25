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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.caraalink.BuildConfig
import dev.caraalink.R
import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.connection.WirelessPhase
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.diagnostics.HuCompatibility
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.rememberConnection
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HuWizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AaBridgeTheme { HuWizardScreen { finish() } } }
    }
}

private fun idle(): Boolean {
    val c = ConnectionStateHolder.snapshot
    return c.link == CarLink.NONE && !c.emulatorActive &&
        c.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR) &&
        c.wireless !in setOf(WirelessPhase.SEARCHING, WirelessPhase.CONNECTING, WirelessPhase.RECONNECTING) &&
        !HuCompatibility.state.value.connected
}

@Composable
private fun HuWizardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { AppSettings.prefs(context) }
    val store = remember(context) { HuWizardStore(context) }
    val logger = remember(context) { DiagnosticLogger(context, "hu-wizard") }
    var run by remember { mutableStateOf(store.load()) }
    val evidence by HuCompatibility.state.collectAsState()
    val connection = rememberConnection()
    val ready = evidence.connected && evidence.screen.isNotBlank() && connection.phase == ConnectionPhase.STREAMING
    val disconnected = connection.link == CarLink.NONE && !connection.emulatorActive &&
        connection.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR) &&
        connection.wireless !in setOf(WirelessPhase.SEARCHING, WirelessPhase.CONNECTING, WirelessPhase.RECONNECTING) && !evidence.connected
    val fingerprint = HuWizardStore.fingerprint(evidence)
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var parked by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var extraTests by remember { mutableStateOf(false) }

    fun save(value: HuWizardStore.Run) {
        store.save(value)
        run = value
    }
    fun applySignature(signature: String): Boolean {
        if (!idle()) return false
        val parts = signature.split('|')
        if (parts.size != 4 || parts[0] !in listOf("true", "false") ||
            parts[1] !in HuTestProfiles.routes || parts[2] !in listOf("true", "false") ||
            parts[3] !in listOf("AUTO", "MULTI", "SINGLE")) return false
        prefs.edit().putBoolean(AppSettings.HU_AUTO_KEY, parts[0] == "true")
            .putString("aa_audio_route", parts[1]).putBoolean("aa_carbit_mic", parts[2] == "true")
            .putString(AppSettings.HU_GESTURES_KEY, parts[3]).apply()
        logger.log("Wizard applied profile $signature; reconnect required")
        return true
    }
    fun restore(value: HuWizardStore.Run, signature: String): Boolean {
        if (!applySignature(signature)) return false
        prefs.edit().putBoolean(AppSettings.HU_CAPTURE_KEY, value.originalCapture).apply()
        return true
    }
    SubScreen(stringResource(R.string.hu_wizard_title), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hu_wizard_parked_note), color = Dash.Muted)
            Row {
                Checkbox(checked = parked, onCheckedChange = { parked = it })
                Text(stringResource(R.string.hu_wizard_parked_confirm), modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { extraTests = !extraTests }) {
                Text(stringResource(if (extraTests) R.string.hu_extra_hide else R.string.hu_extra_show))
            }
            if (extraTests) HuAdvancedTests(evidence.sessionId, parked && ready && (run == null || run?.target == fingerprint))
            val current = run
            if (current == null) {
                WizardTile(R.string.hu_wizard_identify) {
                    Text(evidence.protocol.ifBlank { stringResource(R.string.hu_wizard_connect) })
                    if (evidence.name.isNotBlank()) Text(evidence.name)
                    if (evidence.screen.isNotBlank()) Text(evidence.screen)
                    Text(evidence.capabilities.ifBlank { stringResource(R.string.hu_no_capabilities) }, color = Dash.Muted)
                    OutlinedTextField(value = label, onValueChange = { label = it.take(80) },
                        label = { Text(stringResource(R.string.hu_wizard_name)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Button(enabled = ready && parked && label.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val live = HuCompatibility.state.value
                        if (live.sessionId != evidence.sessionId || !live.connected ||
                            ConnectionStateHolder.snapshot.phase != ConnectionPhase.STREAMING) return@Button
                        val originalCapture = AppSettings.huCapture(context)
                        val original = AppSettings.huProfileSignature(context)
                        val value = HuWizardStore.Run(HuWizardStore.fingerprint(live), label.trim(), original, originalCapture)
                        save(value)
                        if (BuildConfig.DEBUG) prefs.edit().putBoolean(AppSettings.HU_CAPTURE_KEY, true).apply()
                        logger.log("Wizard started: target=${value.target}; original=$original")
                    }) { Text(stringResource(R.string.hu_wizard_start)) }
                    if (!ready) Text(stringResource(R.string.hu_wizard_connect), color = Dash.Muted)
                }
            } else {
                val candidates = HuWizardPlan.candidates(evidence.musicDeclared, evidence.micDeclared)
                val tested = current.results.mapNotNull { (signature, answers) ->
                    HuTestProfiles.all.firstOrNull { it.signature == signature }?.let { HuWizardPlan.Result(it, answers, current.voicePaths[signature].orEmpty()) }
                }
                val finished = tested.count { it.complete }
                val progress = (finished + current.skipped.size).coerceAtMost(candidates.size)
                val targetMatches = fingerprint == current.target
                WizardTile(R.string.hu_wizard_progress) {
                    Text(current.label, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.hu_wizard_progress_count, progress, candidates.size))
                    LinearProgressIndicator(progress = { progress.toFloat() / candidates.size }, modifier = Modifier.fillMaxWidth())
                    Text(if (ready && !targetMatches) stringResource(R.string.hu_wizard_wrong_hu) else
                        evidence.protocol.ifBlank { stringResource(R.string.hu_wizard_connect) },
                        color = if (ready && !targetMatches) Dash.Amber else Dash.Muted)
                    if (ready && targetMatches) {
                        Text(evidence.capabilities.ifBlank { stringResource(R.string.hu_no_capabilities) }, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.hu_message_count, evidence.messages), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (current.summary) {
                    val ranked = HuWizardPlan.rank(tested)
                    val best = HuWizardPlan.recommendation(tested)
                    WizardTile(R.string.hu_wizard_summary) {
                        Text(stringResource(if (best == null) R.string.hu_wizard_no_recommendation else R.string.hu_wizard_recommended))
                        if (best != null) {
                            Text(profileName(best.profile), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.hu_wizard_audio_hu_count, best.huAudio))
                            if (best.profile.gestures == "SINGLE") Text(stringResource(R.string.hu_wizard_single_limit), color = Dash.Amber)
                            if (best.voicePath.isNotBlank()) Text(stringResource(R.string.hu_wizard_mic_path, best.voicePath))
                            if (best.huAudio < 2) Text(stringResource(R.string.hu_wizard_phone_limit), color = Dash.Amber)
                            Button(enabled = disconnected && parked, modifier = Modifier.fillMaxWidth(), onClick = {
                                if (restore(current, best.profile.signature)) {
                                    logger.log("Wizard recommendation applied: ${best.profile.signature}; HU audio=${best.huAudio}/2")
                                    notice = R.string.hu_wizard_applied
                                }
                            }) { Text(stringResource(R.string.hu_wizard_apply)) }
                        }
                        OutlinedButton(enabled = disconnected, modifier = Modifier.fillMaxWidth(), onClick = {
                            if (restore(current, current.originalSignature)) notice = R.string.hu_wizard_restored
                        }) { Text(stringResource(R.string.hu_wizard_restore)) }
                        if (!disconnected) Text(stringResource(R.string.hu_wizard_disconnect), color = Dash.Muted)
                        ranked.forEach { result ->
                            HorizontalDivider(color = Dash.TileBorder)
                            Text(profileName(result.profile))
                            Text(stringResource(R.string.hu_wizard_result, result.passed, result.checks.size, result.failed, result.huAudio),
                                color = if (result.confirmed) Dash.Teal else Dash.Muted, style = MaterialTheme.typography.bodySmall)
                            if (result.voicePath.isNotBlank()) Text(stringResource(R.string.hu_wizard_mic_path, result.voicePath), style = MaterialTheme.typography.bodySmall)
                            result.checks.forEach { check ->
                                val verdict = result.answers[check]
                                Text(stringResource(checkLabel(check)) + ": " + stringResource(verdict?.let(::verdictLabel) ?: R.string.hu_unknown),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { notice = null; save(current.copy(summary = false, selected = "")) }) {
                            Text(stringResource(R.string.hu_wizard_continue))
                        }
                    }
                } else {
                    val selected = HuTestProfiles.all.firstOrNull { it.signature == current.selected }
                    if (selected == null) {
                        WizardTile(R.string.hu_wizard_select) {
                            Text(stringResource(R.string.hu_wizard_select_hint), color = Dash.Muted)
                            candidates.forEach { profile ->
                                val result = tested.firstOrNull { it.profile == profile }
                                val skipped = profile.signature in current.skipped
                                OutlinedButton(enabled = parked && (!ready || targetMatches), modifier = Modifier.fillMaxWidth(), onClick = {
                                    notice = null
                                    save(current.copy(selected = profile.signature, skipped = current.skipped - profile.signature))
                                }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(profileName(profile))
                                        if (result != null) Text(stringResource(R.string.hu_wizard_result, result.passed, result.checks.size, result.failed, result.huAudio),
                                            style = MaterialTheme.typography.bodySmall)
                                        else if (skipped) Text(stringResource(R.string.hu_wizard_skip), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    } else {
                        val answers = current.results[selected.signature].orEmpty()
                        val checks = HuWizardPlan.checksFor(selected)
                        val next = checks.firstOrNull { it !in answers }
                        val active = ready && targetMatches && evidence.appliedProfile == selected.signature &&
                            AppSettings.huProfileSignature(context) == selected.signature
                        WizardTile(R.string.hu_wizard_test) {
                            Text(profileName(selected), style = MaterialTheme.typography.titleMedium)
                            if (!active) {
                                Text(stringResource(if (disconnected) R.string.hu_wizard_apply_connect else R.string.hu_wizard_disconnect), color = Dash.Muted)
                                Button(enabled = disconnected && parked, modifier = Modifier.fillMaxWidth(), onClick = {
                                    if (applySignature(selected.signature)) notice = R.string.hu_wizard_connect
                                }) { Text(stringResource(R.string.hu_wizard_prepare)) }
                            } else {
                                Text(stringResource(R.string.hu_wizard_active), color = Dash.Teal)
                                if (evidence.playbackAck) Text(stringResource(R.string.hu_audio_ack), style = MaterialTheme.typography.bodySmall)
                                if (evidence.micPcm) Text(stringResource(R.string.hu_mic_pcm), style = MaterialTheme.typography.bodySmall)
                                if (evidence.multiTouch) Text(stringResource(R.string.hu_multitouch_observed), style = MaterialTheme.typography.bodySmall)
                                evidence.outputDeviceType?.let { Text(stringResource(R.string.hu_wizard_output_type, it), style = MaterialTheme.typography.bodySmall) }
                                if (evidence.micPath.isNotBlank()) Text(stringResource(R.string.hu_wizard_mic_path, evidence.micPath), style = MaterialTheme.typography.bodySmall)
                                evidence.micPeak?.let { Text(stringResource(R.string.hu_wizard_mic_peak, it), style = MaterialTheme.typography.bodySmall) }
                                if (evidence.micSilenced == true) Text(stringResource(R.string.hu_wizard_mic_silenced), color = Dash.Amber)
                            }
                            Text(stringResource(R.string.hu_wizard_test_count, answers.size, checks.size))
                            if (next != null) {
                                Text(stringResource(checkLabel(next)), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(instructionLabel(next)))
                                HuWizardPlan.options(next).forEach { verdict ->
                                    OutlinedButton(enabled = active && parked, modifier = Modifier.fillMaxWidth(), onClick = {
                                        val live = HuCompatibility.state.value
                                        if (live.sessionId != evidence.sessionId || !live.connected ||
                                            live.appliedProfile != selected.signature ||
                                            HuWizardStore.fingerprint(live) != current.target ||
                                            ConnectionStateHolder.snapshot.phase != ConnectionPhase.STREAMING ||
                                            AppSettings.huProfileSignature(context) != selected.signature) return@OutlinedButton
                                        val updated = answers + (next to verdict)
                                        val paths = if (next == HuWizardPlan.Check.VOICE)
                                            current.voicePaths + (selected.signature to live.micPath) else current.voicePaths
                                        save(current.copy(results = current.results + (selected.signature to updated), voicePaths = paths))
                                        logger.log("Wizard test ${selected.signature} ${next.name}=${verdict.name}; playbackAck=${live.playbackAck} micPcm=${live.micPcm} multiTouch=${live.multiTouch} micPath=${live.micPath} outputDevice=${live.outputDeviceType}")
                                    }) { Text(stringResource(verdictLabel(verdict))) }
                                }
                            } else {
                                Text(stringResource(R.string.hu_wizard_profile_done), color = Dash.Teal)
                            }
                            if (answers.isNotEmpty()) TextButton(onClick = {
                                save(current.copy(results = current.results - selected.signature, voicePaths = current.voicePaths - selected.signature))
                            }) { Text(stringResource(R.string.hu_wizard_retest)) }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                val skipped = if (answers.isEmpty()) current.skipped + selected.signature else current.skipped
                                notice = null
                                save(current.copy(selected = "", skipped = skipped))
                            }) { Text(stringResource(R.string.hu_wizard_choose_other)) }
                        }
                    }
                    WizardTile(R.string.hu_vehicle) {
                        if (evidence.vehicleFields.isEmpty() || !targetMatches) Text(stringResource(R.string.hu_no_vehicle_fields), color = Dash.Muted)
                        else evidence.vehicleFields.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.hu_unverified), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        notice = null
                        save(current.copy(summary = true))
                        logger.log("Wizard summary: profiles=$finished of ${candidates.size}; recommendation=${HuWizardPlan.recommendation(tested)?.profile?.signature ?: "none"}")
                    }) { Text(stringResource(R.string.hu_wizard_summary)) }
                }
                notice?.let { Text(stringResource(it), color = Dash.Teal) }
                OutlinedButton(enabled = !exporting, modifier = Modifier.fillMaxWidth(), onClick = {
                    exporting = true; exportError = false
                    scope.launch {
                        try {
                            logger.log("Wizard report: target=${current.target}; tested=${current.results}; micPaths=${current.voicePaths}; skipped=${current.skipped}")
                            val intent = withContext(Dispatchers.IO) { logger.shareAllLogsIntent() }
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (_: Exception) { exportError = true }
                        finally { exporting = false }
                    }
                }) { Text(stringResource(R.string.home_send_logs)) }
                if (exportError) Text(stringResource(R.string.hu_export_failed), color = Dash.Amber)
                TextButton(enabled = disconnected, onClick = { confirmCancel = true }) { Text(stringResource(R.string.hu_wizard_reset)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.hu_wizard_pause)) }
            }
        }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.hu_wizard_reset)) },
        text = { Text(stringResource(R.string.hu_wizard_reset_confirm)) },
        confirmButton = { TextButton(onClick = {
            run?.let { if (restore(it, it.originalSignature)) { store.clear(); run = null; notice = null } }
            confirmCancel = false
        }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun WizardTile(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Dash.Tile) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun profileName(profile: HuTestProfiles.Profile): String {
    if (profile.auto) return stringResource(R.string.hu_profile_auto)
    return stringResource(routeLabels.getValue(profile.route)) + " · " +
        stringResource(if (profile.remoteMic) R.string.hu_profile_mic_hu else R.string.hu_profile_mic_phone) + " · " +
        stringResource(if (profile.gestures == "SINGLE") R.string.hu_gesture_single else R.string.hu_gesture_multi)
}

private fun checkLabel(check: HuWizardPlan.Check): Int = when (check) {
    HuWizardPlan.Check.MUSIC -> R.string.hu_check_music
    HuWizardPlan.Check.GUIDANCE -> R.string.hu_check_guidance
    HuWizardPlan.Check.VOICE -> R.string.hu_check_voice
    HuWizardPlan.Check.TOUCH -> R.string.hu_wizard_touch
    HuWizardPlan.Check.PINCH -> R.string.hu_wizard_pinch
}
private fun instructionLabel(check: HuWizardPlan.Check): Int = when (check) {
    HuWizardPlan.Check.MUSIC -> R.string.hu_wizard_music_hint
    HuWizardPlan.Check.GUIDANCE -> R.string.hu_wizard_guidance_hint
    HuWizardPlan.Check.VOICE -> R.string.hu_wizard_voice_hint
    HuWizardPlan.Check.TOUCH -> R.string.hu_wizard_touch_hint
    HuWizardPlan.Check.PINCH -> R.string.hu_wizard_pinch_hint
}
private fun verdictLabel(verdict: HuWizardPlan.Verdict): Int = when (verdict) {
    HuWizardPlan.Verdict.HU -> R.string.hu_wizard_heard_hu
    HuWizardPlan.Verdict.PHONE -> R.string.hu_wizard_heard_phone
    HuWizardPlan.Verdict.PASS -> R.string.hu_test_pass
    HuWizardPlan.Verdict.FAIL -> R.string.hu_test_fail
    HuWizardPlan.Verdict.SKIP -> R.string.hu_wizard_skip
}
