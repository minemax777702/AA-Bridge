package dev.caraalink.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.caraalink.R
import dev.caraalink.aa.AndroidAutoServer
import dev.caraalink.connection.CarLink
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.connection.WirelessPhase
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.theme.Dash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SERVER_POLL_MS = 2_000L

/** True while the screen is resumed: polls the head unit server every 2 s. */
@Composable
fun rememberServerRunning(): Boolean {
    var running by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                running = withContext(Dispatchers.IO) { AndroidAutoServer.isRunning() }
                delay(SERVER_POLL_MS)
            }
        }
    }
    return running
}

@Composable
internal fun rememberConnection(): ConnectionSnapshot {
    var snapshot by remember { mutableStateOf(ConnectionStateHolder.snapshot) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener: (ConnectionSnapshot) -> Unit = { s -> main.post { snapshot = s } }
        ConnectionStateHolder.addListener(listener)
        onDispose { ConnectionStateHolder.removeListener(listener) }
    }
    return snapshot
}

/** The wordmark on screens without the settings gear. */
@Composable
fun BrandBar(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text(
            stringResource(R.string.app_name),
            color = Dash.Text,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Starts the wireless search: the remembered car, or [address] when the driver picked one. */
fun startWireless(context: Context, address: String? = null, name: String? = null) {
    val intent = Intent(context, UsbLinkForegroundService::class.java).setAction(UsbLinkForegroundService.ACTION_START_WIRELESS)
    if (address != null) intent.putExtra(UsbLinkForegroundService.EXTRA_CAR_ADDRESS, address).putExtra(UsbLinkForegroundService.EXTRA_CAR_NAME, name)
    context.startForegroundService(intent)
}

private fun retryWireless(context: Context, connection: ConnectionSnapshot) {
    val car = carToRetry(connection, AppSettings.wirelessCar(context))
    startWireless(context, car?.address, car?.name)
}

private fun stopLink(context: Context) {
    context.startService(Intent(context, UsbLinkForegroundService::class.java).setAction(UsbLinkForegroundService.ACTION_STOP))
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    val serverRunning = rememberServerRunning()
    val connection = rememberConnection()
    val carConnected = connection.phase.isCarConnected()
    val state = homeState(serverRunning, carConnected, connection.wireless)
    val searchName = carToRetry(connection, AppSettings.wirelessCar(context))?.name
    val carName = connection.carName ?: searchName ?: stringResource(R.string.state_connected)
    val title = when (state) {
        HomeState.READY -> stringResource(R.string.home_ready_title)
        HomeState.NEEDS_TAP -> stringResource(R.string.home_needs_tap_title)
        HomeState.CONNECTED -> carName
        HomeState.SEARCHING, HomeState.CONNECTING -> searchName ?: stringResource(R.string.nearby_your_car)
        HomeState.NOT_FOUND -> if (connection.wireless == WirelessPhase.FAILED)
            stringResource(R.string.home_failed_title, searchName ?: "") else stringResource(R.string.home_not_found_title)
        HomeState.WIFI_OFF -> stringResource(R.string.home_wifi_off_title)
    }
    val body = when (state) {
        HomeState.READY -> R.string.home_ready_body
        HomeState.NEEDS_TAP -> R.string.home_needs_tap_body
        HomeState.CONNECTED -> R.string.home_connected_body
        HomeState.SEARCHING -> R.string.home_searching_body
        HomeState.CONNECTING -> R.string.home_connecting_body
        HomeState.NOT_FOUND -> R.string.home_not_found_body
        HomeState.WIFI_OFF -> R.string.home_wifi_off_body
    }
    Column(Modifier.fillMaxSize().background(Dash.Background).systemBarsPadding()) {
        DashTopBar(onOpenSettings)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                Spacer(Modifier.height(24.dp))
                ConnectionHero(state, title, stringResource(body))
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state == HomeState.NEEDS_TAP) {
                        MenuHint(stringResource(R.string.menu_start_server))
                        Spacer(Modifier.height(16.dp))
                    }
                    HomeActions(state, connection, onOpenNearby)
                }
                if (state == HomeState.NOT_FOUND) {
                    connection.suggestion?.let { found ->
                        Spacer(Modifier.height(12.dp))
                        SuggestionTile(found.name) { startWireless(context, found.address, found.name) }
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.ui_connection_details), color = Dash.Muted,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                Column(Modifier.fillMaxWidth().background(Dash.Tile, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp)) {
                    ConnectionDetail(R.drawable.ic_server, stringResource(R.string.tile_server),
                        stringResource(if (serverRunning) R.string.ui_active else R.string.ui_inactive), serverRunning)
                    HorizontalDivider(color = Dash.TileBorder)
                    val cable = carConnected && connection.link == CarLink.CABLE
                    ConnectionDetail(if (cable) R.drawable.ic_usb else R.drawable.ic_wifi, stringResource(R.string.tile_link),
                        stringResource(if (!carConnected) R.string.ui_not_connected else if (cable) R.string.link_cable_short else R.string.link_wireless_short),
                        carConnected)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConnectionHero(state: HomeState, title: String, body: String) {
    val accent = when (state) {
        HomeState.CONNECTED -> Color(0xFF9ED9B8)
        HomeState.NEEDS_TAP, HomeState.NOT_FOUND, HomeState.WIFI_OFF -> Dash.Amber
        else -> Dash.Teal
    }
    val status = when (state) {
        HomeState.READY -> R.string.ring_ready
        HomeState.CONNECTED -> R.string.ring_connected
        HomeState.SEARCHING -> R.string.ring_searching
        HomeState.CONNECTING -> R.string.ring_connecting
        HomeState.NEEDS_TAP -> R.string.ring_needs_tap
        HomeState.NOT_FOUND -> R.string.ring_not_found
        HomeState.WIFI_OFF -> R.string.ring_wifi_off
    }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF222D40), Dash.Tile)))
            .border(1.dp, Color(0xFF35435A), shape)
            .padding(22.dp),
    ) {
        Row(
            Modifier.background(accent.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (state == HomeState.SEARCHING || state == HomeState.CONNECTING) {
                CircularProgressIndicator(Modifier.size(12.dp), color = accent, strokeWidth = 1.5.dp)
            } else {
                Box(Modifier.size(7.dp).background(accent, CircleShape))
            }
            Text(stringResource(status), color = accent, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(20.dp))
        Image(
            painterResource(R.drawable.ic_connection_hero),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(300f / 128f),
            alpha = if (state == HomeState.CONNECTED) 1f else 0.75f,
        )
        Spacer(Modifier.height(22.dp))
        Text(title, color = Dash.Text, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp))
        Spacer(Modifier.height(8.dp))
        Text(body, color = Dash.Muted, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
    }
}

@Composable
private fun ConnectionDetail(icon: Int, label: String, value: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = Dash.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = Dash.Text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, color = if (active) Color(0xFF8DD8B1) else Dash.Muted,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

/** The main and secondary actions; each state offers its own next step. */
@Composable
private fun HomeActions(state: HomeState, connection: ConnectionSnapshot, onOpenNearby: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        HomeState.READY -> GlowButton(stringResource(R.string.home_connect_wireless), onOpenNearby)
        HomeState.SEARCHING, HomeState.CONNECTING -> DashLink(stringResource(R.string.home_use_another_car), onOpenNearby)
        HomeState.CONNECTED -> if (connection.link == CarLink.WIRELESS) {
            androidx.compose.material3.OutlinedButton(onClick = { stopLink(context) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.home_disconnect))
            }
        }
        HomeState.NEEDS_TAP -> AmberButton(stringResource(R.string.open_aa), { AndroidAutoServer.openSettings(context) })
        HomeState.NOT_FOUND -> {
            GlowButton(stringResource(R.string.home_try_again), { retryWireless(context, connection) })
            Spacer(Modifier.height(4.dp))
            DashLink(stringResource(R.string.home_choose_car), onOpenNearby)
        }
        HomeState.WIFI_OFF -> {
            AmberButton(stringResource(R.string.home_wifi_settings), { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) })
            Spacer(Modifier.height(4.dp))
            DashLink(stringResource(R.string.home_try_again)) { retryWireless(context, connection) }
        }
    }
}

/** Zips the logs and opens the share sheet, so a tester far away can send them over any chat app. */
@Composable
fun SendLogsLink() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(4.dp))
    DashLink(stringResource(R.string.home_send_logs)) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DiagnosticLogger(context, "app").shareAllLogsIntent() } }
                .onSuccess { context.startActivity(Intent.createChooser(it, null)) }
                .onFailure { Toast.makeText(context, it.toString(), Toast.LENGTH_LONG).show() }
        }
    }
}

/** A device with the remembered car's name, one tap away. */
@Composable
private fun SuggestionTile(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Dash.Tile)
            .border(1.dp, Dash.Teal.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = Dash.Teal)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.home_found_nearby, name), color = Dash.Text, style = MaterialTheme.typography.bodyLarge)
    }
}
