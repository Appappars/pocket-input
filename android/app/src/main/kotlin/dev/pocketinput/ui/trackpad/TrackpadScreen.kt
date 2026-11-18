package dev.pocketinput.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.HidTransport
import kotlin.math.sqrt

// Пороги из spec/05-roadmap-qa.md 5.3 п.6 — живут в TrackpadMath (zero-decision rule).
private const val BUTTON_LEFT = 1                // левая кнопка мыши = 1

/**
 * Фаза 0, spec/05-roadmap-qa.md 5.3 п.6: минимальный трекпад.
 * dp -> px: dx_px = dx_dp * densityDpi / 160f * sensitivity.
 */
@Composable
fun TrackpadScreen(transport: HidTransport, sensitivity: Float = 1.0f) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.trackpad_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = stringResource(R.string.trackpad_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val pxPerDp = TrackpadMath.pxPerDp(LocalConfiguration.current.densityDpi, sensitivity)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(transport, sensitivity, pxPerDp) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val pointerId = down.id
                                        var totalShiftDp = 0f
                                        var lastReportNs = 0L
                                        var dragging = false

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                            if (change.changedToUpIgnoreConsumed()) {
                                                change.consume()
                                                break
                                            }
                                            val delta = change.positionChange()
                                            change.consume()
                                            if (delta.x == 0f && delta.y == 0f) continue

                                            val dxDp = delta.x / pxPerDp
                                            val dyDp = delta.y / pxPerDp
                                            totalShiftDp += sqrt(dxDp * dxDp + dyDp * dyDp).toFloat()
                                            // Как только сдвиг перевалил порог тапа — жест считается drag до конца.
                                            if (!dragging && !TrackpadMath.isTap(totalShiftDp)) dragging = true

                                            if (dragging) {
                                                // троттлинг: не чаще 120 Гц
                                                val now = System.nanoTime()
                                                if (TrackpadMath.shouldSend(now, lastReportNs)) {
                                                    lastReportNs = now
                                                    sendClamped(
                                                        transport,
                                                        dxPx = dxDp * pxPerDp,
                                                        dyPx = dyDp * pxPerDp
                                                    )
                                                }
                                            }
                                        }

                                        // Палец ушёл, не сдвинувшись дальше порога — это тап (левый клик).
                                        if (!dragging && TrackpadMath.isTap(totalShiftDp)) {
                                            transport.tap(BUTTON_LEFT)
                                        }
                                    }
                                }
        )
        // TODO(phase 1): скролл двумя пальцами (wheel) — в фазе 0 не реализуется,
        // см. spec/05-roadmap-qa.md фаза 1.
    }
}

/**
 * Дельта больше 127 px режется до 127, остаток досылается следующим пакетом.
 * Сама нарезка — в TrackpadMath.splitDeltas (покрыта JVM-тестом).
 */
private fun sendClamped(transport: HidTransport, dxPx: Float, dyPx: Float) {
    for ((dx, dy) in TrackpadMath.splitDeltas(dxPx, dyPx)) {
        transport.sendMouse(0, dx, dy, 0)
    }
}
