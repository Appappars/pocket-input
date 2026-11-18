package dev.pocketinput.ui.trackpad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport

// Бит 1 = ЛКМ, бит 2 = ПКМ (HidTransport.pressButton/releaseButton).
private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2

/**
 * Экран трекпада: подсказка, сенсорная поверхность ([TrackpadSurface]) и панель кнопок.
 * Вся логика жестов живёт в поверхности — она же используется на экране клавиатуры.
 */
@Composable
fun TrackpadScreen(
    transport: HidTransport,
    settings: SettingsState,
    onOpenKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var lmbLatched by rememberSaveable { mutableStateOf(false) }

    // Уход в фон: отпускаем кнопки на ПК и снимаем латч (сама поверхность тоже отпускает).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lmbLatched = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun bump() {
        if (settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = stringResource(R.string.trackpad_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.trackpad_hint_full),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        TrackpadSurface(
            transport = transport,
            settings = settings,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    bump()
                    if (lmbLatched) {
                        transport.releaseButton(BUTTON_LEFT)
                        lmbLatched = false
                    } else {
                        transport.pressButton(BUTTON_LEFT)
                        lmbLatched = true
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(
                    text = if (lmbLatched) {
                        stringResource(R.string.btn_lmb_latched)
                    } else {
                        stringResource(R.string.btn_lmb)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = {
                    bump()
                    transport.tap(BUTTON_RIGHT)
                },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.btn_rmb),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onOpenKeyboard,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(text = stringResource(R.string.btn_keyboard))
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(text = stringResource(R.string.btn_settings))
            }
        }
    }
}