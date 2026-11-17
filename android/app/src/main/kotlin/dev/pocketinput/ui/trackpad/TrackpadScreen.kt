package dev.pocketinput.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
                    var lastReportNs = 0L
                    var totalShiftDp = 0f
                    detectDragGestures(
                        onDragStart = { totalShiftDp = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // dragAmount в px; переводим в dp по densityDpi/160
                            val dxDp = dragAmount.x / pxPerDp
                            val dyDp = dragAmount.y / pxPerDp
                            totalShiftDp += sqrt(dxDp * dxDp + dyDp * dyDp).toFloat()
                            // порог drag: >= 8 dp
                            if (!TrackpadMath.isTap(totalShiftDp)) {
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
                        },
                        onDragEnd = {
                            // порог тапа: суммарный сдвиг < 8 dp
                            if (TrackpadMath.isTap(totalShiftDp)) {
                                transport.sendMouse(BUTTON_LEFT, 0, 0, 0)
                                transport.sendMouse(0, 0, 0, 0)
                            }
                        },
                        onDragCancel = { }
                    )
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
