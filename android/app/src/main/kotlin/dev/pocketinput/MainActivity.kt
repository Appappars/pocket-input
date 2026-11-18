package dev.pocketinput

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.pocketinput.data.SettingsState
import dev.pocketinput.data.SettingsStore
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.service.PocketInputService
import dev.pocketinput.ui.connect.ConnectScreen
import dev.pocketinput.ui.keyboard.KeyboardScreen
import dev.pocketinput.ui.link.DiagnosticsScreen
import dev.pocketinput.ui.settings.SettingsScreen
import dev.pocketinput.ui.theme.PocketInputTheme
import dev.pocketinput.ui.trackpad.TrackpadScreen

/**
 * Точка входа приложения.
 *
 * Навигация фазы 1 — enum + rememberSaveable (navigation-compose в проекте нет):
 * CONNECT -> TRACKPAD -> KEYBOARD / SETTINGS и назад.
 * Транспорт HID один на всё приложение: клавиатура и трекпад работают через него,
 * поэтому переход между экранами не пересоздаёт и не рвёт соединение.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { CONNECT, TRACKPAD, KEYBOARD, SETTINGS, DIAGNOSTICS }

    private lateinit var transport: HidTransport
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(
            this,
            Intent(this, PocketInputService::class.java),
        )

        transport = HidTransport(this)
        transport.start()
        store = SettingsStore(this)

        setContent {
            var settings by remember { mutableStateOf(store.load()) }
            // enum храним строкой: rememberSaveable переживает перезапуск процесса.
            var screenName by rememberSaveable { mutableStateOf(Screen.CONNECT.name) }
            val screen = remember(screenName) {
                Screen.entries.firstOrNull { it.name == screenName } ?: Screen.CONNECT
            }

            // Изменение применяется сразу: состояние + диск (spec 4.8).
            fun apply(next: SettingsState) {
                settings = next
                store.save(next)
            }

            fun goTo(target: Screen) {
                // Уходя с трекпада, не оставляем зажатую кнопку на ПК.
                if (screen == Screen.TRACKPAD && target != Screen.TRACKPAD) {
                    transport.releaseAllButtons()
                }
                screenName = target.name
            }

            // Системная «назад» на вложенных экранах возвращает на трекпад, а не закрывает приложение.
            BackHandler(
                enabled = screen == Screen.KEYBOARD ||
                    screen == Screen.SETTINGS ||
                    screen == Screen.DIAGNOSTICS,
            ) {
                goTo(Screen.TRACKPAD)
            }

            PocketInputTheme(theme = settings.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (screen) {
                        Screen.CONNECT -> ConnectScreen(
                            transport = transport,
                            onConnected = { goTo(Screen.TRACKPAD) },
                        )

                        Screen.TRACKPAD -> TrackpadScreen(
                            transport = transport,
                            settings = settings,
                            onOpenKeyboard = { goTo(Screen.KEYBOARD) },
                            onOpenSettings = { goTo(Screen.SETTINGS) },
                            onOpenDiagnostics = { goTo(Screen.DIAGNOSTICS) },
                        )

                        Screen.KEYBOARD -> KeyboardScreen(
                            transport = transport,
                            settings = settings,
                            onLayoutChange = { layout ->
                                apply(settings.copy(layout = layout))
                            },
                            onBack = { goTo(Screen.TRACKPAD) },
                            onOpenDiagnostics = { goTo(Screen.DIAGNOSTICS) },
                        )

                        Screen.DIAGNOSTICS -> DiagnosticsScreen(
                            transport = transport,
                            onBack = { goTo(Screen.TRACKPAD) },
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            state = settings,
                            onChange = { next -> apply(next) },
                            onBack = { goTo(Screen.TRACKPAD) },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::transport.isInitialized) {
            transport.stop()
        }
        super.onDestroy()
    }
}