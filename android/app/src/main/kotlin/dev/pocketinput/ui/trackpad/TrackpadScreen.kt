package dev.pocketinput.ui.trackpad

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Бит 1 = ЛКМ, бит 2 = ПКМ (HidTransport.pressButton/releaseButton).
private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2

// Маски модификаторов HID (docs/phase1-brief.md).
private const val MOD_LSHIFT = 0x02
private const val MOD_LALT = 0x04
private const val MOD_LGUI = 0x08

// Usage-коды: D (Win+D — рабочий стол), Tab (Alt+Tab — переключение окон).
private const val KEY_D = 0x07
private const val KEY_TAB = 0x2B

/** Второй клик двойного тапа уходит с этой задержкой (brief, таблица жестов). */
private const val SECOND_CLICK_DELAY_MS = 40L

/** Сколько двухпальцевый жест должен прожить, прежде чем начнёт крутить колесо. */
private const val TWO_FINGER_ARM_MS = 40L

/**
 * Трекпад фазы 1: движение, тап, двойной тап, долгое нажатие (ПКМ), drag,
 * скролл двумя пальцами, свайпы тремя пальцами. Все числа — в [TrackpadMath],
 * здесь только раскладка события на жест и вызовы [HidTransport].
 *
 * @param settings чувствительность и скролл-гейн применяются на каждом движении;
 * @param onOpenKeyboard переход на собственную клавиатуру;
 * @param onOpenSettings переход на экран настроек.
 */
@Composable
fun TrackpadScreen(
    transport: HidTransport,
    settings: SettingsState,
    onOpenKeyboard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    // dp -> px: densityDpi/160 * sensitivity (LocalDensity.density == densityDpi / 160).
    val pxPerDp = TrackpadMath.pxPerDp((density * 160f).roundToInt(), settings.sensitivity)
    val scrollGain = settings.scrollGain
    val vibration = settings.vibration
    val scope = rememberCoroutineScope()

    var lmbLatched by rememberSaveable { mutableStateOf(false) }
    var lastTapUpMs by remember { mutableLongStateOf(0L) }

    // Уход в фон: висящая кнопка на ПК = зажатая мышь, поэтому отпускаем всё.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, transport) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                transport.releaseAllButtons()
                lmbLatched = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            transport.releaseAllButtons()
            lmbLatched = false
        }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .pointerInput(transport, pxPerDp, scrollGain, vibration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downUptime = down.uptimeMillis

                        var totalShiftDp = 0f
                        var dragging = false
                        var scrollMode = false
                        var threeMode = false
                        var longPressFired = false
                        var maxPointers = 1
                        var prevPointers = 1
                        var lastCentroid: Offset? = null
                        var threeDx = 0f
                        var threeDy = 0f
                        var wheelRemainder = 0f
                        var wheelPending = 0
                        var twoFingerSinceMs = -1L
                        var lastMotionNs = 0L
                        var lastWheelNs = 0L

                        fun flushWheel(force: Boolean) {
                            if (wheelPending == 0) return
                            val now = System.nanoTime()
                            val due = abs(wheelPending) >= 100 ||
                                TrackpadMath.shouldSend(
                                    now,
                                    lastWheelNs,
                                    TrackpadMath.MIN_WHEEL_INTERVAL_NS,
                                )
                            if (!force && !due) return
                            transport.wheel(wheelPending)
                            wheelPending = 0
                            lastWheelNs = now
                        }

                        while (true) {
                            // Пока палец лежит без движения, событий нет — ждём их
                            // только до момента, когда удержание становится ПКМ.
                            val nowMs = SystemClock.uptimeMillis()
                            val canLongPress = !longPressFired && !dragging &&
                                !scrollMode && !threeMode && maxPointers == 1
                            val remaining = TrackpadMath.LONG_PRESS_MS - (nowMs - downUptime)
                            val event = if (canLongPress && remaining > 0L) {
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                // 500 мс без движения: правая кнопка, левый клик не шлём.
                                longPressFired = true
                                vibrate(vibration, haptic)
                                transport.tap(BUTTON_RIGHT)
                                continue
                            }

                            event.changes.forEach { it.consume() }
                            val pressed = event.changes.count { it.pressed }
                            if (pressed > maxPointers) maxPointers = pressed
                            if (maxPointers >= 3 && !threeMode) {
                                // Третий палец отменяет скролл: это свайп, а не колесо.
                                threeMode = true
                                scrollMode = false
                                wheelPending = 0
                                threeDx = 0f
                                threeDy = 0f
                            }

                            if (pressed == 0) break

                            if (pressed != prevPointers) {
                                // Центроид прыгает при добавлении/снятии пальца — перевешиваем якорь.
                                lastCentroid = null
                                prevPointers = pressed
                            }
                            if (pressed == 2 && twoFingerSinceMs < 0L) {
                                twoFingerSinceMs = nowMs
                            }

                            val active = event.changes.filter { it.pressed }
                            val centroid = centroidOf(active)
                            val prev = lastCentroid
                            lastCentroid = centroid
                            if (prev == null) continue

                            val dxDp = (centroid.x - prev.x) / pxPerDp
                            val dyDp = (centroid.y - prev.y) / pxPerDp
                            if (dxDp == 0f && dyDp == 0f) continue

                            when {
                                threeMode -> {
                                    threeDx += dxDp
                                    threeDy += dyDp
                                }

                                pressed >= 2 -> {
                                    scrollMode = true
                                    if (nowMs - twoFingerSinceMs < TWO_FINGER_ARM_MS) continue
                                    // Палец вверх = колесо вверх (положительные единицы HID).
                                    val step = TrackpadMath.accumulateScroll(
                                        wheelRemainder,
                                        -dyDp * TrackpadMath.SCROLL_UNITS_PER_DP * scrollGain,
                                    )
                                    wheelRemainder = step.remainder
                                    wheelPending += step.units
                                    flushWheel(force = false)
                                }

                                else -> {
                                    totalShiftDp += TrackpadMath.distanceDp(dxDp, dyDp)
                                    if (!dragging && !TrackpadMath.isTap(totalShiftDp)) {
                                        dragging = true
                                    }
                                    if (dragging && !longPressFired) {
                                        val now = System.nanoTime()
                                        if (TrackpadMath.shouldSend(now, lastMotionNs)) {
                                            lastMotionNs = now
                                            sendClamped(
                                                transport,
                                                dxPx = dxDp * pxPerDp,
                                                dyPx = dyDp * pxPerDp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Жест закончился — решаем, что отправить.
                        val releaseMs = SystemClock.uptimeMillis()
                        when {
                            threeMode -> {
                                when (TrackpadMath.classifyThreeFinger(threeDx, threeDy)) {
                                    TrackpadMath.ThreeFinger.UP -> {
                                        vibrate(vibration, haptic)
                                        transport.keyTap(KEY_D, MOD_LGUI)
                                    }

                                    TrackpadMath.ThreeFinger.RIGHT -> {
                                        vibrate(vibration, haptic)
                                        transport.keyTap(KEY_TAB, MOD_LALT)
                                    }

                                    TrackpadMath.ThreeFinger.LEFT -> {
                                        vibrate(vibration, haptic)
                                        transport.keyTap(KEY_TAB, MOD_LALT or MOD_LSHIFT)
                                    }

                                    TrackpadMath.ThreeFinger.NONE -> Unit
                                }
                            }

                            scrollMode -> flushWheel(force = true)

                            // Правый клик уже ушёл по таймеру — левый не дублируем.
                            longPressFired -> Unit

                            // Курсор уже двигался: это drag, не клик.
                            dragging -> Unit

                            TrackpadMath.isTap(totalShiftDp) -> {
                                if (TrackpadMath.isDoubleTap(lastTapUpMs, releaseMs, totalShiftDp)) {
                                    lastTapUpMs = 0L
                                    // Первый клик уже ушёл прошлым тапом, второй — с задержкой 40 мс.
                                    scope.launch {
                                        delay(SECOND_CLICK_DELAY_MS)
                                        transport.tap(BUTTON_LEFT)
                                    }
                                } else {
                                    lastTapUpMs = releaseMs
                                    transport.tap(BUTTON_LEFT)
                                }
                                vibrate(vibration, haptic)
                            }
                        }
                    }
                },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    vibrate(vibration, haptic)
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
                    vibrate(vibration, haptic)
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

/** Средняя точка всех нажатых пальцев: движение двух пальцев считается по центроиду. */
private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sx = 0f
    var sy = 0f
    var count = 0
    for (change in changes) {
        sx += change.position.x
        sy += change.position.y
        count++
    }
    if (count == 0) return Offset.Zero
    return Offset(sx / count, sy / count)
}

/** Вибро-отклик на клик и захват, если он включён в настройках. */
private fun vibrate(enabled: Boolean, haptic: HapticFeedback) {
    if (enabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
}

/**
 * Дельта больше 127 px режется до 127, остаток досылается следующим пакетом.
 * Сама нарезка — в TrackpadMath.splitDeltas (покрыта JVM-тестом).
 */
private fun sendClamped(transport: HidTransport, dxPx: Float, dyPx: Float) {
    for ((dx, dy) in TrackpadMath.splitDeltas(dxPx, dyPx)) {
        transport.sendMotion(dx, dy)
    }
}