package dev.pocketinput.ui.trackpad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import dev.pocketinput.ui.link.LinkStatusBar

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
    onOpenDiagnostics: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val link = transport.link
    val sessionId = link.sessionId
    val connected = sessionId != 0L && link.state == LinkState.CONNECTED
    var lmbLatched by remember(transport, sessionId) { mutableStateOf(false) }
    var gestureHold by remember(transport, sessionId) { mutableStateOf(false) }

    fun sessionIsCurrent(): Boolean {
        val current = transport.link
        return connected && current.sessionId == sessionId && current.state == LinkState.CONNECTED
    }

    fun updateHold(latch: Boolean = lmbLatched, gesture: Boolean = gestureHold) {
        val wasHeld = lmbLatched || gestureHold
        lmbLatched = latch
        gestureHold = gesture
        val held = latch || gesture
        if (held == wasHeld || !sessionIsCurrent()) return
        if (held) transport.pressButton(BUTTON_LEFT) else transport.releaseButton(BUTTON_LEFT)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, transport, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) updateHold(latch = false, gesture = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updateHold(latch = false, gesture = false)
        }
    }

    fun bump() {
        if (settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        LinkStatusBar(link = link, onClick = if (connected) onOpenDiagnostics else onOpenConnection)
        Text(
            text = stringResource(R.string.trackpad_title),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (settings.tapDragEnabled) R.string.trackpad_hint_tap_drag
                else R.string.trackpad_hint_full,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        TrackpadSurface(
            transport = transport,
            settings = settings,
            modifier = Modifier.fillMaxWidth().weight(1f),
            leftLatched = lmbLatched,
            onLeftHold = { updateHold(gesture = it) },
            onLeftHoldCancelled = {
                gestureHold = false
                lmbLatched
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (sessionIsCurrent()) {
                        updateHold(latch = !lmbLatched)
                        bump()
                    }
                },
                enabled = connected,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                    toggleableState = if (lmbLatched) ToggleableState.On else ToggleableState.Off
                },
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
                    if (sessionIsCurrent()) {
                        transport.tap(BUTTON_RIGHT)
                        bump()
                    }
                },
                enabled = connected,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
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
