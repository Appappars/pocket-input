package dev.pocketinput.ui.link

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.hid.LinkState
import dev.pocketinput.hid.Quality
import kotlinx.coroutines.delay

/**
 * Экран диагностики канала: состояние, качество и сырые числа, по которым качество считается.
 *
 * Важно: RSSI («уровень сигнала») для классического Bluetooth-HID Android приложению не отдаёт,
 * поэтому качество — это пригодность канала: задержка очереди отправки, доля отказов и разрывы.
 */
@Composable
fun DiagnosticsScreen(transport: HidTransport, onBack: () -> Unit) {
    val link = transport.link
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    // Часы для «на связи N секунд»: без тика время замирает на моменте входа на экран.
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val uptimeSeconds = if (link.connectedSinceMs > 0L) {
        ((nowMs - link.connectedSinceMs) / 1000L).coerceAtLeast(0L)
    } else {
        -1L
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                text = stringResource(R.string.diag_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Крупный вердикт: есть связь или нет.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (link.state) {
                        LinkState.CONNECTED -> stringResource(R.string.diag_state_connected)
                        LinkState.REGISTERED -> stringResource(R.string.diag_state_registered)
                        LinkState.INIT -> stringResource(R.string.link_connecting)
                        LinkState.NO_PROFILE -> stringResource(R.string.link_no_profile)
                        LinkState.FAILED -> stringResource(R.string.link_failed)
                        LinkState.NO_ADAPTER -> stringResource(R.string.link_no_adapter)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(12.dp))

            MetricRow(
                label = stringResource(R.string.diag_peer),
                value = link.peer ?: "—",
            )
            MetricRow(
                label = stringResource(R.string.diag_quality),
                value = when {
                    link.score < 0 -> "—"
                    else -> "${link.score}% · " + qualityWord(link.quality)
                },
            )
            MetricRow(
                label = stringResource(R.string.diag_uptime),
                value = if (uptimeSeconds < 0) "—" else "$uptimeSeconds s",
            )
            MetricRow(
                label = stringResource(R.string.diag_p50),
                value = if (link.totalSends == 0L) "—" else "${format1(link.p50Ms)} ms",
            )
            MetricRow(
                label = stringResource(R.string.diag_p95),
                value = if (link.totalSends == 0L) "—" else "${format1(link.p95Ms)} ms",
            )
            MetricRow(
                label = stringResource(R.string.diag_sent),
                value = link.sentOk.toString(),
            )
            MetricRow(
                label = stringResource(R.string.diag_failed),
                value = link.sendFailed.toString(),
            )
            MetricRow(
                label = stringResource(R.string.diag_disconnects),
                value = link.disconnects.toString(),
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.diag_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (link.quality == Quality.POOR) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.diag_advice_poor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { testResult = transport.selfTest() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.diag_test))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (testResult) {
                    null -> stringResource(R.string.diag_test_hint)
                    true -> stringResource(R.string.diag_test_sent)
                    false -> stringResource(R.string.diag_test_failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun qualityWord(quality: Quality): String = when (quality) {
    Quality.GOOD -> stringResource(R.string.quality_good)
    Quality.MEDIUM -> stringResource(R.string.quality_medium)
    Quality.POOR -> stringResource(R.string.quality_poor)
    Quality.UNKNOWN -> "—"
}

private fun format1(value: Float): String {
    val tenths = (value * 10f).toInt()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}