package dev.pocketinput

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.pocketinput.service.PocketInputService

/**
 * Точка входа приложения. Фаза 0: спайк HID, один экран без UI-дизайна.
 * Тема Compose берётся напрямую из material3 — собственная тема объявляется
 * в модуле ui (фаза 1).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(
            this,
            Intent(this, PocketInputService::class.java),
        )

        setContent {
            MaterialTheme {
                Text(text = stringResource(R.string.trackpad_title))
            }
        }
    }
}
