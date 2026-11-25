package dev.caraalink.diagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                HorizontalDivider(color = Dash.TileBorder)
                Text(stringResource(R.string.diag_error, snapshot.lastError ?: "-"), color = Dash.Muted)
            }
        }
    }
}
