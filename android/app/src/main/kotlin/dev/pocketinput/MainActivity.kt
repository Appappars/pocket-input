package dev.pocketinput

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.service.PocketInputService
import dev.pocketinput.ui.connect.ConnectScreen
import dev.pocketinput.ui.trackpad.TrackpadScreen

/**
 * Точка входа приложения. Фаза 0: спайк HID, один экран без UI-дизайна.
 * Тема Compose берётся напрямую из material3 — собственная тема объявляется
 * в модуле ui (фаза 1).
 */
class MainActivity : ComponentActivity() {

    private lateinit var transport: HidTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(
            this,
            Intent(this, PocketInputService::class.java),
        )

        transport = HidTransport(this)
        transport.start()

        setContent {
            MaterialTheme {
                var connected by remember { mutableStateOf(false) }
                if (connected) {
                    TrackpadScreen(transport = transport)
                } else {
                    ConnectScreen(
                        transport = transport,
                        onConnected = { connected = true },
                    )
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
