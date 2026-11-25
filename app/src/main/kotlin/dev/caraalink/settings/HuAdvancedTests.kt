package dev.caraalink.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.diagnostics.*
import dev.caraalink.ui.theme.Dash
import kotlinx.coroutines.*

@Composable
internal fun HuAdvancedTests(session: String, allowed: Boolean) {
    val context = LocalContext.current
    val logger = remember(context) { DiagnosticLogger(context, "hu-extra-tests") }
    val tools by HuDiagnosticTools.state.collectAsState()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signalResults by remember(session) { mutableStateOf<Map<HuTestSignal.Route, HuTestSignal.Result>>(emptyMap()) }
    var heard by remember(session) { mutableStateOf<Map<HuTestSignal.Route, String>>(emptyMap()) }
    var currentRoute by remember { mutableStateOf<HuTestSignal.Route?>(null) }
    var reference by remember { mutableStateOf<HuTraceCompare.Trace?>(null) }
    var bridge by remember { mutableStateOf<HuTraceCompare.Trace?>(null) }
    var traceError by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    val ready = allowed && !busy && !tools.calibrating && HuDiagnosticTools.ready(session)
    // Readiness also depends on AA closing; refresh even if the connection snapshot did not change.
    var readyTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) { while (true) { delay(500); readyTick++ } }
    val canStart = readyTick.let { allowed && !busy && !tools.calibrating && HuDiagnosticTools.ready(session) }
    DisposableEffect(session) {
        onDispose { job?.cancel(); HuDiagnosticTools.stop(session) }
    }
    LaunchedEffect(allowed) {
        if (!allowed) { job?.cancel(); HuDiagnosticTools.stop(session) }
    }

    fun start(routes: List<HuTestSignal.Route>) {
        if (!canStart || job?.isActive == true) return
        busy = true
        job = scope.launch {
            try {
                for (route in routes) {
                    if (!HuDiagnosticTools.ready(session)) break
                    currentRoute = route
                    val result = try {
                        withContext(Dispatchers.IO) { HuTestSignal.play(context.applicationContext, session, route) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        logger.log("Signal failure: $route ${e.javaClass.simpleName}")
                        HuTestSignal.Result(false)
                    }
                    signalResults = signalResults + (route to result)
                    heard = heard - route
                    logger.log("Signal result $route sent=${result.sent} device=${result.device} frames=${result.frames}")
                    delay(500)
                }
            } finally { busy = false; currentRoute = null }
        }
    }
    fun importTrace(uri: Uri?, original: Boolean) {
        if (uri == null || importing) return
        importing = true; traceError = false
        scope.launch {
            try {
                val trace = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { HuTraceCompare.read(it) }
                }
                if (trace == null || trace.recognized == 0) { traceError = true; return@launch }
                if (original) reference = trace else bridge = trace
                showDiff = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { traceError = true }
            finally { importing = false }
        }
    }
    val pickReference = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importTrace(it,true) }
    val pickBridge = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importTrace(it,false) }

    ExtraTile(R.string.hu_extra_audio) {
        Text(stringResource(R.string.hu_extra_closed), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.hu_extra_audio_hint), style = MaterialTheme.typography.bodySmall)
        Button(enabled = canStart, modifier = Modifier.fillMaxWidth(), onClick = { start(HuTestSignal.Route.entries.toList()) }) {
            Text(stringResource(R.string.hu_extra_auto_signals))
        }
        if (busy) {
            Text(currentRoute?.let { stringResource(signalLabel(it)) } ?: "")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hu_extra_stop)) }
        }
        HuTestSignal.Route.entries.forEach { route ->
            HorizontalDivider(color = Dash.TileBorder)
            Text(stringResource(signalLabel(route)), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = canStart, onClick = { start(listOf(route)) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.hu_extra_manual_signal))
            }
            signalResults[route]?.let { result ->
                Text(stringResource(if (result.sent) R.string.hu_extra_signal_sent else R.string.hu_extra_signal_failed),
                    color = if (result.sent) Dash.Teal else Dash.Amber)
                result.device?.let { Text(stringResource(R.string.hu_wizard_output_type,it), style = MaterialTheme.typography.bodySmall) }
                val options = listOf("HU" to R.string.hu_wizard_heard_hu, "PHONE" to R.string.hu_wizard_heard_phone,
                    "SILENT" to R.string.hu_extra_silent, "DISTORTED" to R.string.hu_extra_distorted)
                options.forEach { (value,res) ->
                    TextButton(enabled = allowed && !busy, onClick = {
                        heard = heard + (route to value)
                        logger.log("Manual signal verdict $route=$value; transportSent=${result.sent}")
                    }) { Text((if (heard[route] == value) "✓ " else "") + stringResource(res)) }
                }
            }
        }
    }
    ExtraTile(R.string.hu_extra_calibration) {
        Text(stringResource(R.string.hu_extra_cal_hint), color = Dash.Muted)
        Button(enabled = canStart, modifier = Modifier.fillMaxWidth(), onClick = {
            if (HuDiagnosticTools.begin(session)) logger.log("Calibration started")
        }) { Text(stringResource(R.string.hu_extra_cal_start)) }
        if (tools.sessionId == session && tools.samples.isNotEmpty()) {
            Text(stringResource(R.string.hu_cal_screen,tools.samples.size,HuCalibration.targets.size))
            tools.result?.let { result ->
                Text(stringResource(R.string.hu_extra_cal_errors,(result.originalError*100).toFloat(),(result.maxError*100).toFloat()))
                Text(stringResource(if (result.valid) R.string.hu_extra_cal_valid else R.string.hu_extra_cal_invalid),
                    color = if (result.valid) Dash.Teal else Dash.Amber)
                Button(enabled = allowed && result.valid && tools.calibrating, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (HuDiagnosticTools.apply(session)) logger.log("Calibration applied: error=${result.maxError} mapping=${result.mapping}")
                }) { Text(stringResource(R.string.hu_extra_cal_apply)) }
            }
        }
        if (tools.sessionId == session && tools.calibrating) OutlinedButton(onClick = {
            HuDiagnosticTools.stop(session); logger.log("Calibration stopped")
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.hu_extra_stop)) }
        if (tools.sessionId == session && (tools.applied || tools.samples.isNotEmpty())) {
            TextButton(onClick = { HuDiagnosticTools.reset(session); logger.log("Calibration reset") }) { Text(stringResource(R.string.hu_extra_cal_reset)) }
        }
    }
    ExtraTile(R.string.hu_extra_compare) {
        Text(stringResource(R.string.hu_extra_compare_hint), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !importing, modifier = Modifier.fillMaxWidth(), onClick = {
            pickReference.launch(arrayOf("text/*","application/octet-stream"))
        }) { Text(stringResource(R.string.hu_extra_reference)) }
        reference?.let { Text(stringResource(R.string.hu_extra_trace_count,it.commands.size,it.recognized)) }
        OutlinedButton(enabled = !importing, modifier = Modifier.fillMaxWidth(), onClick = {
            pickBridge.launch(arrayOf("text/*","application/octet-stream"))
        }) { Text(stringResource(R.string.hu_extra_bridge)) }
        OutlinedButton(enabled = !importing, modifier = Modifier.fillMaxWidth(), onClick = {
            importing = true; traceError = false
            scope.launch {
                try {
                    val trace = withContext(Dispatchers.IO) { logger.latestLogFile().inputStream().use { HuTraceCompare.read(it) } }
                    if (trace.recognized == 0) traceError = true else bridge = trace
                    showDiff = false
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { traceError = true }
                finally { importing = false }
            }
        }) { Text(stringResource(R.string.hu_extra_current_log)) }
        bridge?.let { Text(stringResource(R.string.hu_extra_trace_count,it.commands.size,it.recognized)) }
        if (reference?.truncated == true || bridge?.truncated == true) Text(stringResource(R.string.hu_extra_trace_truncated),color = Dash.Amber)
        if (traceError) Text(stringResource(R.string.hu_extra_trace_error), color = Dash.Amber)
        Button(enabled = reference != null && bridge != null && !importing, modifier = Modifier.fillMaxWidth(), onClick = {
            showDiff = true
            val diff = HuTraceCompare.compare(checkNotNull(reference),checkNotNull(bridge))
            logger.log("Protocol comparison: differences=${diff.size}")
            diff.take(128).forEach { logger.log("Trace difference ${it.key} reference=${it.reference} bridge=${it.bridge}") }
        }) { Text(stringResource(R.string.hu_extra_compare_run)) }
        if (showDiff) {
            val a = reference; val b = bridge
            if (a != null && b != null) {
                val differences = remember(a,b) { HuTraceCompare.compare(a,b) }
                Text(stringResource(R.string.hu_extra_diff_count,differences.size))
                differences.take(128).forEach { difference ->
                    Text(difference.key.toString(),style = MaterialTheme.typography.titleSmall)
                    Text("CarbitLink: ${difference.reference?.sorted()?.joinToString() ?: "—"} · AA-Bridge: ${difference.bridge?.sorted()?.joinToString() ?: "—"}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (differences.size > 128) Text(stringResource(R.string.hu_extra_diff_limit))
            }
        }
    }
}

@Composable private fun ExtraTile(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Dash.Tile) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun signalLabel(route: HuTestSignal.Route): Int = when(route) {
    HuTestSignal.Route.SYSTEM -> R.string.hu_extra_system
    HuTestSignal.Route.SPEAKER -> R.string.hu_audio_device
    HuTestSignal.Route.BLUETOOTH -> R.string.hu_audio_bluetooth
    HuTestSignal.Route.PCM_MUSIC -> R.string.hu_extra_pcm_music
    HuTestSignal.Route.PCM_TTS -> R.string.hu_extra_pcm_tts
}
