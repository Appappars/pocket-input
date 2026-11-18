package dev.pocketinput.ui.trackpad

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState

private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2
private const val MOD_LSHIFT = 0x02
private const val MOD_LALT = 0x04
private const val MOD_LGUI = 0x08
private const val KEY_D = 0x07
private const val KEY_TAB = 0x2B

@Composable
fun TrackpadSurface(
    transport: HidTransport,
    settings: SettingsState,
    modifier: Modifier = Modifier,
    leftLatched: Boolean = false,
    onLeftHold: ((Boolean) -> Unit)? = null,
    onLeftHoldCancelled: (() -> Boolean)? = null,
    onFocusChangingInput: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val link = transport.link
    val sessionId = link.sessionId.takeIf { resumed && link.state == LinkState.CONNECTED } ?: 0L
    val isLatched by rememberUpdatedState(leftLatched)
    val onHold by rememberUpdatedState(onLeftHold)
    val onHoldCancelled by rememberUpdatedState(onLeftHoldCancelled)
    val onFocusChange by rememberUpdatedState(onFocusChangingInput)
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .onSizeChanged { padSize = it }
            .pointerInput(transport, sessionId, density, settings) {
                if (sessionId == 0L) return@pointerInput

                fun vibrate() {
                    if (settings.vibration) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }

                fun hold(active: Boolean) {
                    val handler = onHold
                    if (handler != null) {
                        handler(active)
                    } else if (active) {
                        transport.pressButton(BUTTON_LEFT)
                    } else {
                        transport.releaseButton(BUTTON_LEFT)
                    }
                }

                val recognizer = TrackpadGestures(
                    settings,
                    density,
                    padSize = {
                        val measured = padSize
                        TrackpadSize(measured.width / density, measured.height / density)
                    },
                    isLeftLatched = { isLatched },
                ) { action ->
                    val current = transport.link
                    val sameSession = current.sessionId == sessionId && current.state == LinkState.CONNECTED
                    val finishingGesture = action == TrackpadAction.Button.RELEASE_LEFT ||
                        action == TrackpadAction.Button.CANCEL_DRAG || action == TrackpadAction.CancelScroll
                    if (sameSession && (resumed || finishingGesture)) {
                        when (action) {
                            TrackpadAction.Button.LEFT_CLICK -> if (!isLatched) {
                                onFocusChange()
                                transport.tap(BUTTON_LEFT)
                                vibrate()
                            }
                            TrackpadAction.Button.RIGHT_CLICK -> {
                                onFocusChange()
                                transport.tap(BUTTON_RIGHT)
                                vibrate()
                            }
                            TrackpadAction.Button.HOLD_LEFT -> {
                                if (!isLatched) onFocusChange()
                                hold(true)
                                if (!isLatched) vibrate()
                            }
                            TrackpadAction.Button.RELEASE_LEFT -> hold(false)
                            TrackpadAction.Button.CANCEL_DRAG -> {
                                val keepLeft = onHoldCancelled?.invoke() ?: isLatched
                                transport.cancelPointerHold(keepLeft)
                            }
                            is TrackpadAction.Motion -> transport.sendMotion(action.dx, action.dy)
                            is TrackpadAction.Wheel -> transport.scroll(action.horizontal, action.detents)
                            is TrackpadAction.Zoom -> transport.zoom(action.detents)
                            TrackpadAction.CancelScroll -> transport.cancelScroll()
                            is TrackpadAction.Swipe -> {
                                onFocusChange()
                                when (action.direction) {
                                    TrackpadMath.ThreeFinger.UP -> transport.keyTap(KEY_D, MOD_LGUI)
                                    TrackpadMath.ThreeFinger.RIGHT -> transport.keyTap(KEY_TAB, MOD_LALT)
                                    TrackpadMath.ThreeFinger.LEFT -> transport.keyTap(KEY_TAB, MOD_LALT or MOD_LSHIFT)
                                    TrackpadMath.ThreeFinger.NONE -> Unit
                                }
                                vibrate()
                            }
                        }
                    }
                }

                try {
                    awaitPointerEventScope {
                        var needsDown = true
                        var waitForUp = false
                        while (true) {
                            val deadline = recognizer.nextDeadlineMs
                            val nowMs = SystemClock.uptimeMillis()
                            val event = if (deadline == null) {
                                awaitPointerEvent()
                            } else {
                                withTimeoutOrNull((deadline - nowMs).coerceAtLeast(1L)) { awaitPointerEvent() }
                            }
                            if (event == null) {
                                recognizer.advance(SystemClock.uptimeMillis())
                                continue
                            }
                            if (event.changes.isEmpty()) continue

                            val pressed = event.changes.any { it.pressed }
                            if (event.changes.any { it.isConsumed }) {
                                recognizer.cancel()
                                waitForUp = pressed
                                needsDown = true
                                continue
                            }
                            if (waitForUp) {
                                waitForUp = pressed
                                continue
                            }
                            if (needsDown && event.changes.none { it.pressed && !it.previousPressed }) {
                                waitForUp = pressed
                                continue
                            }

                            recognizer.onFrame(
                                event.changes.first().uptimeMillis,
                                event.changes.map {
                                    TrackpadContact(
                                        it.id.value,
                                        TrackpadPoint(it.position.x / density, it.position.y / density),
                                        it.pressed,
                                    )
                                },
                            )
                            event.changes.forEach { it.consume() }
                            needsDown = !pressed
                        }
                    }
                } finally {
                    recognizer.cancel()
                }
            },
    )
}
