package dev.pocketinput.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport

/** Высота ряда клавиш: 52 dp — клавиатура влезает в портрет без прокрутки (brief). */
private val ROW_HEIGHT = 52.dp
private val KEY_GAP = 3.dp

/**
 * Клавиатура фазы 1 (docs/phase1-brief.md, «Клавиатура»).
 *
 * Собственная Compose-клавиатура, не системная IME: слои EN/RU/SYM/SYM2, sticky-модификаторы,
 * отправка через HID usage-коды (`transport.keyTap` / `keyDown`+`keyUp` для Backspace).
 * Весь набор — 5 рядов по 52 dp, без прокрутки.
 */
@Composable
fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Выбранный слой запоминается per-устройство через settings.layout.
    var layer by rememberSaveable { mutableStateOf(KeyMap.layerFromLayout(settings.layout)) }
    var sticky by remember { mutableStateOf(StickyState()) }
    var backspaceHeld by remember { mutableStateOf(false) }

    // Смена layout снаружи (экран настроек) переключает буквенный слой.
    LaunchedEffect(settings.layout) {
        val target = KeyMap.layerFromLayout(settings.layout)
        if (layer != target && (layer == Layer.EN || layer == Layer.RU)) layer = target
    }

    // Уход с экрана не должен оставить Backspace зажатой на ПК.
    DisposableEffect(Unit) {
        onDispose {
            if (backspaceHeld) {
                transport.keyUp()
                backspaceHeld = false
            }
        }
    }

    fun setLayer(target: Layer) {
        layer = target
        KeyMap.layoutOf(target)?.let(onLayoutChange)
    }

    val onKeyPress: (Key) -> Unit = { key ->
        when (key.kind) {
            KeyKind.MOD -> sticky = KeyMap.toggleModifier(sticky, key.modMask)
            KeyKind.LAYER -> key.targetLayer?.let { setLayer(it) }
            KeyKind.BACKSPACE -> Unit // удержание обрабатывается pointerInput клавиши
            else -> {
                val (usage, extraMods) = KeyMap.resolveUsage(key, shifted = sticky.isLocked(Mods.LSHIFT))
                transport.keyTap(usage, sticky.mask or extraMods)
                sticky = KeyMap.afterKeyPress(sticky)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(text = stringResource(R.string.back), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(R.string.keyboard_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }

        // Переключатель слоёв: активный подсвечен.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayerChip(Layer.EN, active = layer == Layer.EN) { setLayer(Layer.EN) }
            LayerChip(Layer.RU, active = layer == Layer.RU) { setLayer(Layer.RU) }
            LayerChip(Layer.SYM, active = layer == Layer.SYM) { setLayer(Layer.SYM) }
            LayerChip(Layer.SYM2, active = layer == Layer.SYM2) { setLayer(Layer.SYM2) }
        }

        // Постоянная подсказка при RU-слое (spec 4.7: HID передаёт позиции, не буквы).
        if (layer == Layer.RU) {
            Text(
                text = stringResource(R.string.keyboard_cyrillic_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // Залипший модификатор — текстом (brief: keyboard_sticky_hint).
        if (sticky.hasLocked()) {
            Text(
                text = stringResource(R.string.keyboard_sticky_hint) + ": " + KeyMap.modifierNames(sticky.locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.Bottom),
        ) {
            KeyMap.rows(layer).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                ) {
                    row.forEach { key ->
                        KeyView(
                            key = key,
                            label = if (key.kind == KeyKind.LAYER) layerLabel(key.targetLayer) else key.label,
                            modifier = Modifier.weight(key.widthWeight),
                            armed = key.kind == KeyKind.MOD && sticky.isArmed(key.modMask),
                            locked = key.kind == KeyKind.MOD && sticky.isLocked(key.modMask),
                            active = key.kind == KeyKind.LAYER && key.targetLayer == layer,
                            onTap = onKeyPress,
                            onHoldStart = if (key.kind == KeyKind.BACKSPACE) {
                                {
                                    backspaceHeld = true
                                    transport.keyDown(Usages.BACKSPACE, sticky.mask)
                                    sticky = KeyMap.afterKeyPress(sticky)
                                }
                            } else null,
                            onHoldEnd = if (key.kind == KeyKind.BACKSPACE) {
                                {
                                    transport.keyUp()
                                    backspaceHeld = false
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun layerLabel(layer: Layer?): String = stringResource(
    when (layer) {
        Layer.EN -> R.string.layer_en
        Layer.RU -> R.string.layer_ru
        Layer.SYM -> R.string.layer_sym
        Layer.SYM2 -> R.string.layer_sym2
        null -> R.string.layer_en
    }
)

@Composable
private fun LayerChip(layer: Layer, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) scheme.primary else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = layerLabel(layer),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
        )
    }
}

/**
 * Одна клавиша. Обычная клавиша отправляется одним `keyTap`; Backspace держится
 * (`keyDown` … `keyUp`), автоповтор делает ОС ПК (brief: на HID-пути автоповтор не реализуется).
 */
@Composable
private fun KeyView(
    key: Key,
    label: String,
    modifier: Modifier = Modifier,
    armed: Boolean = false,
    locked: Boolean = false,
    active: Boolean = false,
    onTap: (Key) -> Unit,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val background: Color = when {
        locked -> scheme.tertiaryContainer
        armed -> scheme.primaryContainer
        active -> scheme.secondaryContainer
        key.kind == KeyKind.CHAR || key.kind == KeyKind.SPACE -> scheme.surfaceVariant
        else -> scheme.secondaryContainer
    }
    val foreground: Color = when {
        locked -> scheme.onTertiaryContainer
        armed -> scheme.onPrimaryContainer
        active -> scheme.onSecondaryContainer
        key.kind == KeyKind.CHAR || key.kind == KeyKind.SPACE -> scheme.onSurface
        else -> scheme.onSecondaryContainer
    }

    val tapState by rememberUpdatedState(onTap)
    val holdStartState by rememberUpdatedState(onHoldStart)
    val holdEndState by rememberUpdatedState(onHoldEnd)

    val gestureModifier = if (onHoldStart != null) {
        Modifier.pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                holdStartState?.invoke()
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                }
                holdEndState?.invoke()
            }
        }
    } else {
        Modifier.pointerInput(key) {
            detectTapGestures { tapState(key) }
        }
    }

    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = if (label.length > 1) 13.sp else 16.sp,
            fontWeight = if (locked) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}