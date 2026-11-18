package dev.pocketinput.ui.trackpad

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import kotlin.math.abs
import kotlin.math.roundToInt
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

/** Сколько двухпальцевый жест должен прожить, прежде чем начнёт крутить колесо. */
private const val TWO_FINGER_ARM_MS = 40L

/**
 * Сенсорная поверхность трекпада: движение, тап, двойной тап, двойной тап с удержанием
 * (перетаскивание), удержание (ПКМ), скролл двумя пальцами, свайпы тремя пальцами.
 * Используется и на экране трекпада, и на экране клавиатуры (место над системной клавиатурой).
 *
 * Жест «двойной тап + удержание» = перетаскивание (решение владельца продукта, 18.11.2026):
 * первый тап кликает, второе касание сразу нажимает ЛКМ и ведёт объект, отпускание отпускает.
 * Двойного клика нет: он разворачивал окно вместо перетаскивания.
 */
@Composable
fun TrackpadSurface(
    transport: HidTransport,
    settings: SettingsState,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // Плотность экрана: px в одном dp (densityDpi / 160). Чувствительность применяется
    // к выходной дельте отдельно, иначе множители сокращаются и ползунок не действует.
    val density = LocalDensity.current.density
    val sensitivity = settings.sensitivity
    val scrollGain = settings.scrollGain
    val invertScroll = settings.invertScroll
    val vibration = settings.vibration

    var lastTapUpMs by remember { mutableLongStateOf(0L) }

    // Уход в фон: висящая кнопка на ПК = зажатая мышь, поэтому отпускаем всё.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, transport) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) transport.releaseAllButtons()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            transport.releaseAllButtons()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .pointerInput(transport, density, sensitivity, scrollGain, invertScroll, vibration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val downMs = down.uptimeMillis

                    // ── Второе касание: удержание = перетаскивание, короткое = ничего.
                    if (TrackpadMath.isDoubleTap(lastTapUpMs, downMs, 0f)) {
                        lastTapUpMs = 0L
                        // Двойного клика НЕТ (он разворачивал окно вместо перетаскивания):
                        // второе касание либо сразу тянет объект, либо не делает ничего.
                        val whileHeld = withTimeoutOrNull(TrackpadMath.TAP_AND_HOLD_MS) {
                            awaitPointerEvent()
                        }
                        val stillDown = whileHeld?.changes?.any { it.pressed } ?: false
                        if (stillDown) {
                            transport.pressButton(BUTTON_LEFT)
                            vibrate(vibration, haptic)
                            dragUntilRelease(transport, down.id, sensitivity)
                            transport.releaseButton(BUTTON_LEFT)
                        }
                        return@awaitEachGesture
                    }

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
                        val remaining = TrackpadMath.LONG_PRESS_MS - (nowMs - downMs)
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

                        val rawDx = centroid.x - prev.x
                        val rawDy = centroid.y - prev.y
                        val dxDp = rawDx / density
                        val dyDp = rawDy / density
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
                                    TrackpadMath.scrollUnits(dyDp, scrollGain, invertScroll),
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
                                            dxPx = rawDx * sensitivity,
                                            dyPx = rawDy * sensitivity,
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
                            // Первый тап: клик. Признак «был тап» запоминаем на DOUBLE_TAP_MS,
                            // чтобы следующий короткий тап стал двойным кликом, а с удержанием — drag.
                            lastTapUpMs = releaseMs
                            transport.tap(BUTTON_LEFT)
                            vibrate(vibration, haptic)
                        }
                    }
                }
            },
    )
}

/**
 * Ведение объекта при зажатой ЛКМ: движение отправляется, пока палец на экране.
 * Кнопку нажимает и отпускает вызывающий код — здесь только поток дельт.
 */
private suspend fun AwaitPointerEventScope.dragUntilRelease(
    transport: HidTransport,
    pointerId: androidx.compose.ui.input.pointer.PointerId,
    sensitivity: Float,
) {
    var lastSentNs = 0L
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        val delta = change.positionChange()
        change.consume()
        if (!change.pressed) return
        if (delta.x == 0f && delta.y == 0f) continue

        val now = System.nanoTime()
        if (TrackpadMath.shouldSend(now, lastSentNs)) {
            lastSentNs = now
            sendClamped(
                transport,
                dxPx = delta.x * sensitivity,
                dyPx = delta.y * sensitivity,
            )
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