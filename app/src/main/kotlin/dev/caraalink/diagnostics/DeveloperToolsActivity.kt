package dev.caraalink.diagnostics

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import dev.caraalink.R
import dev.caraalink.aa.AaPreviewActivity
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeveloperToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AaBridgeTheme { DeveloperTools(onBack = { finish() }) } }
    }
}

@Composable
private fun DeveloperTools(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    SubScreen(stringResource(R.string.dev_tools), onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LanDebugCard()
            Surface(color = Dash.Tile, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dev_diagnostics), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { context.startActivity(Intent(context, ConnectionDiagnosticsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_diagnostics)) }
                    OutlinedButton(enabled = !clearing, onClick = { confirmClear = true },
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_clear_logs)) }
                }
            }
            Surface(color = Dash.Tile, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dev_preview), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.dev_preview_desc), color = Dash.Muted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { context.startActivity(Intent(context, AaPreviewActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_preview)) }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.dev_clear_logs)) },
        text = { Text(stringResource(R.string.dev_clear_logs_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                clearing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { DiagnosticLogger.clearAll(context) }
                        Toast.makeText(context, R.string.dev_logs_cleared, Toast.LENGTH_SHORT).show()
                    } finally { clearing = false }
                }
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
