package dev.caraalink.diagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.os.SystemClock
import dev.caraalink.settings.AppSettings
import dev.caraalink.link.PhoneGpsSpeed
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.rememberConnection
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash

class ConnectionDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AaBridgeTheme { ConnectionDiagnostics(onBack = { finish() }) } }
    }
}

@Composable
private fun ConnectionDiagnostics(onBack: () -> Unit) {
    val snapshot = rememberConnection()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtimeNanos()
            kotlinx.coroutines.delay(500)
        }
    }
    val speed = snapshot.phoneGpsSpeedMps?.let { PhoneGpsSpeed.Sample(it, snapshot.phoneGpsSpeedElapsedNanos) }
        ?.takeIf { it.freshAt(now) && AppSettings.phoneGpsSpeed(context) }
    val phase = stringResource(when (snapshot.phase) {
        ConnectionPhase.DISCONNECTED -> R.string.diag_phase_disconnected
        ConnectionPhase.ATTACHED -> R.string.diag_phase_attached
        ConnectionPhase.SNIFFING -> R.string.diag_phase_sniffing
        ConnectionPhase.STREAMING -> R.string.diag_phase_streaming
        ConnectionPhase.RECONNECTING -> R.string.diag_phase_reconnecting
        ConnectionPhase.ERROR -> R.string.diag_phase_error
    })
    SubScreen(stringResource(R.string.dev_diagnostics), onBack) {
        Surface(Modifier.padding(16.dp).fillMaxWidth(), color = Dash.Tile, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.diag_phase, phase), style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(color = Dash.TileBorder)
                Text(stringResource(R.string.diag_accessory, snapshot.accessoryManufacturer ?: "-", snapshot.accessoryModel ?: "-"))
                Text(stringResource(R.string.diag_read, snapshot.bytesRead))
                Text(stringResource(R.string.diag_written, snapshot.bytesWritten))
                Text(stringResource(R.string.diag_reconnect, snapshot.reconnectAttempts))
                if (AppSettings.phoneGpsSpeed(context)) Text(if (speed == null) stringResource(R.string.diag_gps_speed_unavailable)
                    else stringResource(R.string.diag_gps_speed, speed.metersPerSecond * 3.6f))
                HorizontalDivider(color = Dash.TileBorder)
                Text(stringResource(R.string.diag_error, snapshot.lastError ?: "-"), color = Dash.Muted)
            }
        }
    }
}
