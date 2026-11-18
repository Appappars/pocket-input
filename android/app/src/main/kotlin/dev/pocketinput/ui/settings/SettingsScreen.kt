package dev.pocketinput.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import kotlin.math.roundToInt

/**
 * Экран настроек (spec 4.8): чувствительность 0.2–4.0, скорость скролла 0.2–3.0,
 * вибро-отклик, тема. Изменение применяется сразу через [onChange].
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onChange: (SettingsState) -> Unit,
    onBack: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                // Чувствительность: 0.2–4.0, шаг 0.1 (38 интервалов → 37 промежуточных точек).
                SettingSlider(
                    label = stringResource(R.string.settings_sensitivity),
                    value = state.sensitivity,
                    range = SettingsState.SENSITIVITY_MIN..SettingsState.SENSITIVITY_MAX,
                    steps = 37,
                    onValueChange = {
                        onChange(state.copy(sensitivity = SettingsState.clampSensitivity(roundTenth(it))))
                    },
                )

                Spacer(Modifier.height(8.dp))

                // Скорость скролла: 0.2–3.0, шаг 0.1 (28 интервалов → 27 промежуточных точек).
                SettingSlider(
                    label = stringResource(R.string.settings_scroll),
                    value = state.scrollGain,
                    range = SettingsState.SCROLL_MIN..SettingsState.SCROLL_MAX,
                    steps = 27,
                    onValueChange = {
                        onChange(state.copy(scrollGain = SettingsState.clampScrollGain(roundTenth(it))))
                    },
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.settings_gestures),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))

                // Пороги жестов: те же числа, что использует TrackpadSurface.
                SettingIntSlider(
                    label = stringResource(R.string.settings_double_tap_window),
                    value = state.doubleTapWindowMs,
                    range = SettingsState.DOUBLE_TAP_MIN_MS..SettingsState.DOUBLE_TAP_MAX_MS,
                    step = 50,
                    unit = "ms",
                    onValueChange = {
                        onChange(state.copy(doubleTapWindowMs = SettingsState.clampDoubleTapWindow(it)))
                    },
                )
                SettingIntSlider(
                    label = stringResource(R.string.settings_tap_hold),
                    value = state.tapAndHoldMs,
                    range = SettingsState.TAP_HOLD_MIN_MS..SettingsState.TAP_HOLD_MAX_MS,
                    step = 20,
                    unit = "ms",
                    onValueChange = {
                        onChange(state.copy(tapAndHoldMs = SettingsState.clampTapAndHold(it)))
                    },
                )
                SettingIntSlider(
                    label = stringResource(R.string.settings_long_press),
                    value = state.longPressMs,
                    range = SettingsState.LONG_PRESS_MIN_MS..SettingsState.LONG_PRESS_MAX_MS,
                    step = 50,
                    unit = "ms",
                    onValueChange = {
                        onChange(state.copy(longPressMs = SettingsState.clampLongPress(it)))
                    },
                )
                SettingIntSlider(
                    label = stringResource(R.string.settings_tap_slop),
                    value = state.tapSlopDp.roundToInt(),
                    range = SettingsState.TAP_SLOP_MIN_DP.toInt()..SettingsState.TAP_SLOP_MAX_DP.toInt(),
                    step = 1,
                    unit = "dp",
                    onValueChange = {
                        onChange(state.copy(tapSlopDp = SettingsState.clampTapSlop(it.toFloat())))
                    },
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.vibration,
                            role = Role.Switch,
                            onValueChange = { onChange(state.copy(vibration = it)) },
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_vibration),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.vibration,
                        onCheckedChange = { onChange(state.copy(vibration = it)) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Направление скролла: владелец просил инверсию (18.11), поэтому дефолт — включена.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.invertScroll,
                            role = Role.Switch,
                            onValueChange = { onChange(state.copy(invertScroll = it)) },
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_invert_scroll),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = state.invertScroll,
                        onCheckedChange = { onChange(state.copy(invertScroll = it)) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(
                        label = stringResource(R.string.settings_theme_system),
                        selected = state.theme == SettingsState.THEME_SYSTEM,
                        onClick = { onChange(state.copy(theme = SettingsState.THEME_SYSTEM)) },
                    )
                    ThemeChip(
                        label = stringResource(R.string.settings_theme_dark),
                        selected = state.theme == SettingsState.THEME_DARK,
                        onClick = { onChange(state.copy(theme = SettingsState.THEME_DARK)) },
                    )
                    ThemeChip(
                        label = stringResource(R.string.settings_theme_light),
                        selected = state.theme == SettingsState.THEME_LIGHT,
                        onClick = { onChange(state.copy(theme = SettingsState.THEME_LIGHT)) },
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            // TODO(i18n): числовое значение с множителем, ресурса нет.
            Text(
                text = "×" + formatOneDecimal(value),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatOneDecimal(range.start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatOneDecimal(range.endInclusive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String,
    onValueChange: (Int) -> Unit,
) {
    val coefficients = (range.last - range.first) / step
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$value $unit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { raw -> onValueChange((raw / step).roundToInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (coefficients - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

/** Округление до десятых без локали: "1.0", "2.4". */
private fun roundTenth(value: Float): Float = (value * 10f).roundToInt() / 10f

private fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}