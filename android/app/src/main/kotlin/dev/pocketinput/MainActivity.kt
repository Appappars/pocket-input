package dev.pocketinput

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.pocketinput.data.SettingsState
import dev.pocketinput.data.SettingsStore
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkCause
import dev.pocketinput.hid.LinkSnapshot
import dev.pocketinput.hid.LinkState
import dev.pocketinput.service.PocketInputService
import dev.pocketinput.ui.connect.ConnectScreen
import dev.pocketinput.ui.keyboard.KeyboardScreen
import dev.pocketinput.ui.link.DiagnosticsScreen
import dev.pocketinput.ui.settings.SettingsScreen
import dev.pocketinput.ui.theme.PocketInputTheme
import dev.pocketinput.ui.trackpad.TrackpadScreen

class MainActivity : ComponentActivity() {
    private enum class Screen { CONNECT, TRACKPAD, KEYBOARD, SETTINGS, DIAGNOSTICS }

    private lateinit var store: SettingsStore
    private var service: PocketInputService? by mutableStateOf(null)
    private var serviceBound = false
    private var visible = false
    private var permissionGranted by mutableStateOf(false)
    private var permissionSettingsRequired by mutableStateOf(false)
    private var serviceStartFailed by mutableStateOf(false)
    private var servicePaused by mutableStateOf(false)
    private var showNotificationPermission by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = (binder as PocketInputService.LocalBinder).service
            service = connected
            serviceStartFailed = false
            connected.setControlVisible(visible)
            connected.transport.refreshBluetooth()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            unbind()
            serviceStartFailed = true
        }
    }

    private val requestBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermission()
        if (permissionGranted && !servicePaused) startServiceAndBind()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotificationPermission()
        service?.refreshNotification()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)

        setContent {
            var settings by remember { mutableStateOf(store.load()) }
            var screenName by rememberSaveable { mutableStateOf(Screen.CONNECT.name) }
            var controlScreenName by rememberSaveable { mutableStateOf(Screen.TRACKPAD.name) }
            var restoringConnection by rememberSaveable { mutableStateOf(false) }
            val screen = Screen.valueOf(screenName)
            val controlScreen = Screen.valueOf(controlScreenName)
            val transport = service?.transport
            val link = when {
                !permissionGranted -> LinkSnapshot(state = LinkState.NEEDS_PERMISSION)
                serviceStartFailed -> LinkSnapshot(state = LinkState.FAILED, cause = LinkCause.SERVICE_FAILED)
                transport != null -> transport.link
                servicePaused -> LinkSnapshot(state = LinkState.STOPPED)
                else -> LinkSnapshot(state = LinkState.INIT)
            }

            fun apply(next: SettingsState) {
                settings = next
                store.save(next)
            }

            fun goTo(target: Screen) {
                if (target == Screen.TRACKPAD || target == Screen.KEYBOARD) {
                    controlScreenName = target.name
                }
                restoringConnection = target == Screen.CONNECT
                screenName = target.name
            }

            BackHandler(enabled = screen == Screen.DIAGNOSTICS ||
                screen == Screen.SETTINGS || screen == Screen.KEYBOARD ||
                (screen == Screen.CONNECT && restoringConnection)) {
                goTo(
                    if (screen == Screen.DIAGNOSTICS || screen == Screen.CONNECT) controlScreen
                    else Screen.TRACKPAD,
                )
            }

            PocketInputTheme(theme = settings.theme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (screen == Screen.CONNECT || transport == null) {
                        ConnectScreen(
                            link = link,
                            permissionSettingsRequired = permissionSettingsRequired,
                            onRequestPermission = ::requestBluetoothPermission,
                            onOpenBluetoothSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                            onRetry = ::retryConnection,
                            onConnected = { goTo(controlScreen) },
                            onBack = if (restoringConnection) ({ goTo(controlScreen) }) else null,
                            onStopSession = if (transport != null &&
                                link.state != LinkState.STOPPED && link.state != LinkState.NEEDS_PERMISSION &&
                                link.cause != LinkCause.SERVICE_FAILED
                            ) ({ service?.stopSession() }) else null,
                        )
                    } else {
                        when (screen) {
                            Screen.TRACKPAD -> TrackpadScreen(
                                transport = transport,
                                settings = settings,
                                onOpenKeyboard = { goTo(Screen.KEYBOARD) },
                                onOpenSettings = { goTo(Screen.SETTINGS) },
                                onOpenDiagnostics = { goTo(Screen.DIAGNOSTICS) },
                                onOpenConnection = { goTo(Screen.CONNECT) },
                            )

                            Screen.KEYBOARD -> KeyboardScreen(
                                transport = transport,
                                settings = settings,
                                onLayoutChange = { layout -> apply(settings.copy(layout = layout)) },
                                onBack = { goTo(Screen.TRACKPAD) },
                                onOpenDiagnostics = { goTo(Screen.DIAGNOSTICS) },
                                onOpenConnection = { goTo(Screen.CONNECT) },
                            )

                            Screen.DIAGNOSTICS -> DiagnosticsScreen(
                                transport = transport,
                                onBack = { goTo(controlScreen) },
                                onOpenConnection = { goTo(Screen.CONNECT) },
                                onStopSession = { service?.stopSession() },
                                onEnableNotifications = if (showNotificationPermission) ::enableNotifications else null,
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                state = settings,
                                onChange = { next -> apply(next) },
                                onBack = { goTo(Screen.TRACKPAD) },
                            )

                            Screen.CONNECT -> Unit
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        servicePaused = PocketInputService.isPaused(this)
        refreshPermission()
        if (permissionGranted && !servicePaused && service == null && !serviceBound && !serviceStartFailed) {
            startServiceAndBind()
        }
        service?.setControlVisible(visible)
    }

    override fun onResume() {
        super.onResume()
        val wasGranted = permissionGranted
        refreshPermission()
        refreshNotificationPermission()
        if (permissionGranted && !wasGranted && !servicePaused) startServiceAndBind()
        service?.transport?.refreshBluetooth()
        service?.refreshNotification()
    }

    override fun onStop() {
        visible = false
        service?.setControlVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        unbind()
        super.onDestroy()
    }

    private fun refreshPermission() {
        val allowedBefore = permissionGranted
        permissionGranted = HidTransport.hasBluetoothPermission(this)
        permissionSettingsRequired = !permissionGranted &&
            getPreferences(Context.MODE_PRIVATE).getBoolean(PERMISSION_REQUESTED_KEY, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT)
        if (permissionGranted && !allowedBefore && service == null) {
            serviceStartFailed = false
        }
    }

    private fun requestBluetoothPermission() {
        if (permissionSettingsRequired) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            startActivity(intent)
        } else {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean(PERMISSION_REQUESTED_KEY, true).apply()
            requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun refreshNotificationPermission() {
        showNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun enableNotifications() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val preferences = getPreferences(Context.MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_REQUESTED_KEY, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        ) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            preferences.edit().putBoolean(NOTIFICATION_REQUESTED_KEY, true).apply()
            requestNotifications.launch(permission)
        }
    }

    private fun retryConnection() {
        val current = service?.transport
        if (current == null || current.link.state == LinkState.STOPPED ||
            current.link.cause == LinkCause.SERVICE_FAILED) {
            startServiceAndBind()
        } else {
            current.retry()
        }
    }

    private fun startServiceAndBind() {
        if (!permissionGranted || !visible) return
        servicePaused = false
        serviceStartFailed = try {
            ContextCompat.startForegroundService(this, serviceIntent())
            if (!serviceBound) serviceBound = bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
            !serviceBound
        } catch (_: SecurityException) {
            true
        } catch (_: IllegalStateException) {
            true
        }
        service?.setControlVisible(true)
    }

    private fun unbind() {
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
        service = null
    }

    private fun serviceIntent() = Intent(this, PocketInputService::class.java)

    private companion object {
        const val PERMISSION_REQUESTED_KEY = "bluetooth_permission_requested"
        const val NOTIFICATION_REQUESTED_KEY = "notification_permission_requested"
    }
}
