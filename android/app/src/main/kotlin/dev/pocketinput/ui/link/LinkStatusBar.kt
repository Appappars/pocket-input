package dev.pocketinput.ui.link

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.hid.LinkSnapshot
import dev.pocketinput.hid.LinkState
import dev.pocketinput.hid.Quality

/**
 * Полоска состояния канала «телефон ↔ ПК»: одна строка, цветная точка и текст.
 * Тап открывает экран диагностики. Врать не умеет: цвет и текст — прямо из [link],
 * который заполняется колбэками Bluetooth-профиля, а не догадками.
 */
@Composable
fun LinkStatusBar(
    link: LinkSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dotColor: Color = when (link.state) {
        LinkState.CONNECTED -> when (link.quality) {
            Quality.GOOD -> Color(0xFF3EBD6B)
            Quality.MEDIUM -> Color(0xFFE0A93B)
            Quality.POOR -> Color(0xFFD9534F)
            Quality.UNKNOWN -> scheme.primary
        }

        LinkState.REGISTERED, LinkState.INIT -> Color(0xFFE0A93B)
        LinkState.NO_PROFILE, LinkState.FAILED, LinkState.NO_ADAPTER -> Color(0xFFD9534F)
    }

    // stringResource — composable-функция, поэтому строки берём до when, а не внутри лямбд.
    val connectedLabel = stringResource(R.string.link_connected)

    val text: String = when (link.state) {
        LinkState.CONNECTED ->
            if (link.score >= 0) "$connectedLabel · ${link.score}%" else connectedLabel

        LinkState.REGISTERED -> stringResource(R.string.link_no_peer)
        LinkState.INIT -> stringResource(R.string.link_connecting)
        LinkState.NO_PROFILE -> stringResource(R.string.link_no_profile)
        LinkState.FAILED -> stringResource(R.string.link_failed)
        LinkState.NO_ADAPTER -> stringResource(R.string.link_no_adapter)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}