package dev.pocketinput.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport

/**
 * Ввод текста через СИСТЕМНУЮ клавиатуру телефона (решение владельца продукта; отменяет
 * собственную раскладку из spec 4.7 — свайп, свои языки и автокоррекция удобнее).
 *
 * На экране невидимое поле ввода, которое держит фокус и вызывает IME. Каждое изменение текста
 * диффится ([TextDiff]) и превращается в HID-отчёты: удаления → Backspace, вставленный текст →
 * по одному `keyTap` через [CharMap]. Панель ниже отправляет то, чего у IME нет: модификаторы
 * (sticky), Esc, Tab, Del, Backspace, стрелки.
 *
 * Кириллица уходит как ПОЗИЦИИ клавиш: буквы появятся на ПК при русской раскладке (spec 4.7).
 */
@Composable
fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    var buffer by remember { mutableStateOf("") }
    var sticky by remember { mutableStateOf(StickyState()) }
    var unsupported by remember { mutableStateOf(0) }

    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current

    // Экран существует только ради набора текста: сразу поднимаем системную клавиатуру.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        ime?.show()
    }

    fun sendKey(usage: Int) {
        transport.keyTap(usage, sticky.mask)
        sticky = KeyMap.afterKeyPress(sticky)
    }

    fun handleChange(next: String) {
        if (next == buffer) return
        val d = TextDiff.diff(buffer, next)

        repeat(d.deleted) { transport.keyTap(Usages.BACKSPACE, sticky.mask) }
        if (d.deleted > 0) sticky = KeyMap.afterKeyPress(sticky)

        var cyrillicTyped = false
        var latinTyped = false
        d.inserted.forEach { ch ->
            if (ch == '\n') {
                sendKey(Usages.ENTER)
                return@forEach
            }
            val mapped = CharMap.map(ch)
            if (mapped == null) {
                unsupported++
                return@forEach
            }
            val (usage, extra) = mapped
            if (ch.isCyrillicLetter()) cyrillicTyped = true
            if (ch in 'a'..'z' || ch in 'A'..'Z') latinTyped = true
            transport.keyTap(usage, sticky.mask or extra)
            sticky = KeyMap.afterKeyPress(sticky)
        }
        if (cyrillicTyped) onLayoutChange("ru") else if (latinTyped) onLayoutChange("en")

        // Поле держим коротким, чтобы IME не накапливал километровый текст.
        buffer = if (next.length >= 24) "" else next
    }

    // Уход с экрана не должен оставить модификатор зажатым на ПК.
    DisposableEffect(Unit) { onDispose { transport.keyUp() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text(stringResource(R.string.back), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(R.string.keyboard_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }

        Text(
            text = stringResource(R.string.kb_system_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.kb_field_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        // Невидимое поле: держит фокус, через него приходит текст системной клавиатуры.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    focus.requestFocus()
                    ime?.show()
                },
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = buffer,
                onValueChange = { handleChange(it) },
                modifier = Modifier.fillMaxSize().focusRequester(focus),
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.None,
                ),
            )
            if (buffer.isEmpty()) {
                Text(
                    text = stringResource(R.string.kb_tap_to_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (sticky.hasLocked()) {
            Text(
                text = stringResource(R.string.keyboard_sticky_hint) + ": " + KeyMap.modifierNames(sticky.locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (unsupported > 0) {
            Text(
                text = stringResource(R.string.kb_unsupported, unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Панель того, чего нет у системной клавиатуры: модификаторы и служебные клавиши.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolKey("Ctrl", Modifier.weight(1f),
                armed = sticky.isArmed(Mods.LCTRL), locked = sticky.isLocked(Mods.LCTRL)) {
                sticky = KeyMap.toggleModifier(sticky, Mods.LCTRL)
            }
            ToolKey("Shift", Modifier.weight(1f),
                armed = sticky.isArmed(Mods.LSHIFT), locked = sticky.isLocked(Mods.LSHIFT)) {
                sticky = KeyMap.toggleModifier(sticky, Mods.LSHIFT)
            }
            ToolKey("Alt", Modifier.weight(1f),
                armed = sticky.isArmed(Mods.LALT), locked = sticky.isLocked(Mods.LALT)) {
                sticky = KeyMap.toggleModifier(sticky, Mods.LALT)
            }
            ToolKey("Win", Modifier.weight(1f),
                armed = sticky.isArmed(Mods.LGUI), locked = sticky.isLocked(Mods.LGUI)) {
                sticky = KeyMap.toggleModifier(sticky, Mods.LGUI)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolKey("Esc", Modifier.weight(1f)) { sendKey(Usages.ESC) }
            ToolKey("Tab", Modifier.weight(1f)) { sendKey(Usages.TAB) }
            ToolKey("Del", Modifier.weight(1f)) { sendKey(Usages.DEL) }
            ToolKey("⌫", Modifier.weight(1f)) { sendKey(Usages.BACKSPACE) }
            ToolKey("↑", Modifier.weight(1f)) { sendKey(Usages.ARROW_UP) }
            ToolKey("↓", Modifier.weight(1f)) { sendKey(Usages.ARROW_DOWN) }
            ToolKey("←", Modifier.weight(1f)) { sendKey(Usages.ARROW_LEFT) }
            ToolKey("→", Modifier.weight(1f)) { sendKey(Usages.ARROW_RIGHT) }
        }
    }
}

private fun Char.isCyrillicLetter(): Boolean =
    this in 'а'..'я' || this in 'А'..'Я' || this == 'ё' || this == 'Ё'

@Composable
private fun ToolKey(
    label: String,
    modifier: Modifier = Modifier,
    armed: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        locked -> scheme.tertiaryContainer
        armed -> scheme.primaryContainer
        else -> scheme.secondaryContainer
    }
    val fg = when {
        locked -> scheme.onTertiaryContainer
        armed -> scheme.onPrimaryContainer
        else -> scheme.onSecondaryContainer
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (locked) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}