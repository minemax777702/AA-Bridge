package dev.caraalink

import dev.caraalink.R

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.AboutScreen
import dev.caraalink.ui.HelpScreen
import dev.caraalink.ui.HomeScreen
import dev.caraalink.ui.NearbyCarsScreen
import dev.caraalink.ui.SettingsScreen
import dev.caraalink.ui.SetupScreen
import dev.caraalink.ui.theme.AaBridgeTheme

enum class Screen { SETUP, HOME, SETTINGS, HELP, ABOUT, NEARBY }

/** The phone app: one Compose activity; also where Android sends the car's USB attach. */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the system bar icons stay light even in the phone's light mode.
        enableEdgeToEdge(
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        Log.i(TAG, "onCreate: launch intent action=${intent?.action}")
        dev.caraalink.diagnostics.CrashMarker.consumeLastCrash(this)?.let { summary ->
            Toast.makeText(this, getString(R.string.crash_restarted, summary), Toast.LENGTH_LONG).show()
        }
        maybeAutoStartOnAttach(intent, recreated = savedInstanceState != null)
        dev.caraalink.diagnostics.LegacyAaDiagnostics.handle(this, intent)
        val firstScreen = if (AppSettings.setupComplete(this)) Screen.HOME else Screen.SETUP
        setContent {
            AaBridgeTheme {
                var screen by rememberSaveable { mutableStateOf(firstScreen) }
                var fromSettings by rememberSaveable { mutableStateOf(false) }
                var nearbyFrom by rememberSaveable { mutableStateOf(Screen.HOME) }
                val back = {
                    screen = when {
                        screen == Screen.NEARBY -> nearbyFrom
                        fromSettings && (screen == Screen.HELP || screen == Screen.ABOUT) -> Screen.SETTINGS
                        else -> Screen.HOME
                    }
                    if (screen == Screen.HOME) fromSettings = false
                }
                val openNearby = { from: Screen -> nearbyFrom = from; screen = Screen.NEARBY }
                BackHandler(enabled = screen != Screen.HOME && screen != Screen.SETUP) { back() }
                when (screen) {
                    Screen.SETUP -> SetupScreen(onDone = { screen = Screen.HOME }, onOpenNearby = { openNearby(Screen.SETUP) })
                    Screen.HOME -> HomeScreen(
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenNearby = { openNearby(Screen.HOME) },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        onBack = back,
                        onHelp = { fromSettings = true; screen = Screen.HELP },
                        onAbout = { fromSettings = true; screen = Screen.ABOUT },
                    )
                    Screen.HELP -> HelpScreen(onBack = back, onRunSetup = { fromSettings = false; screen = Screen.SETUP })
                    Screen.ABOUT -> AboutScreen(onBack = back)
                    Screen.NEARBY -> NearbyCarsScreen(onBack = back, onConnecting = { screen = Screen.HOME })
                }
            }
        }
    }

    private fun maybeAutoStartOnAttach(intent: Intent?, recreated: Boolean = false) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (dev.caraalink.ui.isRepeatedAttach(recreated || fromRecents, dev.caraalink.connection.ConnectionStateHolder.snapshot)) return
        @Suppress("DEPRECATION")
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
        Log.i(TAG, "maybeAutoStartOnAttach: launched via attach intent, starting car session")
        val serviceIntent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_SESSION)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(serviceIntent)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app connects to the remembered car; the cable and a running attempt both win.
        // Without Nearby devices the scan can only fail; Home's Connect without a cable asks for it instead.
        val canScan = checkSelfPermission(dev.caraalink.ui.NEARBY_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canScan && dev.caraalink.ui.shouldAutoConnect(dev.caraalink.connection.ConnectionStateHolder.snapshot, AppSettings.wirelessCar(this) != null)) {
            dev.caraalink.ui.startWireless(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action}")
        maybeAutoStartOnAttach(intent)
        dev.caraalink.diagnostics.LegacyAaDiagnostics.handle(this, intent)
    }

}
