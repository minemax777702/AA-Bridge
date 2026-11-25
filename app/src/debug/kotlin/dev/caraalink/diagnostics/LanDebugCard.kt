package dev.caraalink.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caraalink.R
import dev.caraalink.connection.CarLink
import dev.caraalink.link.EmulatorLanAuth
import dev.caraalink.link.EmulatorWifi
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.ui.rememberConnection
import dev.caraalink.ui.theme.Dash

@Composable
internal fun LanDebugCard() {
    val context = LocalContext.current
    val snapshot = rememberConnection()
    var code by remember { mutableStateOf<String?>(null) }
    var ip by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        if (starting) {
            kotlinx.coroutines.delay(3000)
            starting = false
        }
    }
    LaunchedEffect(snapshot.emulatorActive) {
        if (snapshot.emulatorActive) starting = false
        else if (!starting) { code = null; ip = null }
    }
    Surface(color = Dash.Tile, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lan_debug_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.lan_debug_summary), color = Dash.Muted, style = MaterialTheme.typography.bodyMedium)
            if (snapshot.emulatorActive || starting) {
                if (ip != null && code != null) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.lan_debug_ip, ip.orEmpty()))
                            Text(code.orEmpty(), fontSize = 28.sp, letterSpacing = 2.sp, color = Dash.Teal)
                        }
                    }
                    Text(stringResource(R.string.lan_debug_wait), color = Dash.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.lan_debug_running))
                }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, UsbLinkForegroundService::class.java).setAction(UsbLinkForegroundService.ACTION_STOP))
                    code = null; ip = null; starting = false
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_disconnect)) }
            } else {
                if (snapshot.link != CarLink.NONE) Text(stringResource(R.string.lan_debug_disconnect), color = Dash.Amber)
                if (error) Text(stringResource(R.string.lan_debug_no_wifi), color = Dash.Amber)
                Button(enabled = snapshot.link == CarLink.NONE, onClick = {
                    val endpoint = EmulatorWifi.current(context)
                    val address = endpoint?.address?.hostAddress
                    error = address == null
                    if (address != null) {
                        val sessionCode = EmulatorLanAuth.newCode()
                        code = sessionCode
                        ip = address
                        starting = true
                        context.startForegroundService(Intent(context, UsbLinkForegroundService::class.java)
                            .setAction(UsbLinkForegroundService.ACTION_START_EMULATOR)
                            .putExtra(UsbLinkForegroundService.EXTRA_EMULATOR_IP, address)
                            .putExtra(UsbLinkForegroundService.EXTRA_EMULATOR_CODE, sessionCode))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lan_debug_start)) }
            }
        }
    }
}
