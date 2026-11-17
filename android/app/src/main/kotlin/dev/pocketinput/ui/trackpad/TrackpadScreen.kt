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

// Пороги из spec/05-roadmap-qa.md 5.3 п.6 — не менять (zero-decision rule).
private const val TAP_THRESHOLD_DP = 8f          // сдвиг < 8 dp = тап, >= 8 dp = drag
private const val MIN_REPORT_INTERVAL_NS = 8_333_333L // не чаще 120 Гц
private const val MAX_DELTA_PX = 127             // int8-ось: дельта режется до 127
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
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val pxPerDp = LocalConfiguration.current.densityDpi / 160f
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
                            if (totalShiftDp >= TAP_THRESHOLD_DP) {
                                // троттлинг: не чаще 120 Гц
                                val now = System.nanoTime()
                                if (now - lastReportNs >= MIN_REPORT_INTERVAL_NS) {
                                    lastReportNs = now
                                    sendClamped(
                                        transport,
                                        dxPx = dxDp * pxPerDp * sensitivity,
                                        dyPx = dyDp * pxPerDp * sensitivity
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            // порог тапа: суммарный сдвиг < 8 dp
                            if (totalShiftDp < TAP_THRESHOLD_DP) {
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
 * Дельта больше 127 px режется до 127, остаток досылается следующим пакетом (цикл while).
 */
private fun sendClamped(transport: HidTransport, dxPx: Float, dyPx: Float) {
    var remX = dxPx.toInt()
    var remY = dyPx.toInt()
    while (remX != 0 || remY != 0) {
        val stepX = remX.coerceIn(-MAX_DELTA_PX, MAX_DELTA_PX)
        val stepY = remY.coerceIn(-MAX_DELTA_PX, MAX_DELTA_PX)
        transport.sendMouse(0, stepX, stepY, 0)
        remX -= stepX
        remY -= stepY
    }
}
