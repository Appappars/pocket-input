package dev.pocketinput.ui.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketinput.R
import dev.pocketinput.data.SettingsState
import dev.pocketinput.hid.HidTransport
import dev.pocketinput.ui.trackpad.TrackpadSurface

/** Невидимый символ-заполнитель: держит в поле запас, чтобы удаление всегда меняло текст. */
private const val FILLER_CHAR = '\u200B'
private const val FILLER_COUNT = 48
private const val FILLER_MIN = 8
private const val BUFFER_MAX = 200

/**
 * Экран набора текста (решение владельца продукта, 18.11.2026):
 * **системная клавиатура телефона + тачпад в свободном месте над ней**, без собственной
 * раскладки и без панели спецклавиш.
 *
 * Механика: в поле-невидимке лежит «заполнитель» из невидимых символов, поэтому нажатие
 * «стереть» в IME всегда меняет текст — и мы видим его в диффе и отправляем Backspace на ПК
 * (иначе на пустом поле кнопка стирания не делает ничего и на ПК ничего не уходит).
 *
 * Изменения текста диффятся ([TextDiff]): удаления → Backspace, вставленный текст → по одному
 * `keyTap` через [CharMap]. Кириллица уходит позициями клавиш; если набирают кириллицу, знаки
 * препинания маппятся по правилам русской раскладки (запятая = Shift+«/»), иначе — по US.
 */
@Composable
fun KeyboardScreen(
    transport: HidTransport,
    settings: SettingsState,
    onLayoutChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val filler = remember { FILLER_CHAR.toString().repeat(FILLER_COUNT) }
    // Курсор держим В КОНЦЕ поля: иначе «стереть» в IME удаляет перед курсором,
    // а курсор стоит в начале строки — и на ПК не уходит ничего (находка владельца продукта).
    var field by remember {
        mutableStateOf(TextFieldValue(filler, selection = TextRange(filler.length)))
    }
    var ruLayout by remember { mutableStateOf(settings.layout.equals("ru", ignoreCase = true)) }
    var unsupported by remember { mutableStateOf(0) }

    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current

    // Экран существует ради набора: сразу поднимаем системную клавиатуру.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        ime?.show()
    }

    // Уход с экрана не должен оставить модификатор зажатым на ПК.
    DisposableEffect(Unit) { onDispose { transport.keyUp() } }

    fun handleChange(newValue: TextFieldValue) {
        val next = newValue.text
        if (next == field.text) {
            // Текст не изменился, но курсор мог уехать — возвращаем его в конец.
            field = newValue.copy(selection = TextRange(next.length))
            return
        }
        val d = TextDiff.diff(field.text, next)

        // Всё, что исчезло из поля, включая невидимые заполнители, — это Backspace на ПК.
        if (d.deleted > 0) repeat(d.deleted) { transport.keyTap(Usages.BACKSPACE) }

        d.inserted.filter { it != FILLER_CHAR }.forEach { ch ->
            if (ch == '\n') {
                transport.keyTap(Usages.ENTER)
                return@forEach
            }
            val mapped = CharMap.map(ch, ruLayout)
            if (mapped == null) {
                unsupported++
                return@forEach
            }
            val (usage, extra) = mapped
            transport.keyTap(usage, extra)
            when {
                ch.isCyrillicLetter() -> {
                    if (!ruLayout) {
                        ruLayout = true
                        onLayoutChange("ru")
                    }
                }
                ch in 'a'..'z' || ch in 'A'..'Z' -> {
                    if (ruLayout) {
                        ruLayout = false
                        onLayoutChange("en")
                    }
                }
            }
        }

        // Держим запас заполнителей: иначе «стереть» на опустевшем поле ничего не делает.
        val updated = when {
            next.length > BUFFER_MAX -> filler
            next.count { it == FILLER_CHAR } < FILLER_MIN -> filler + next
            else -> next
        }
        field = TextFieldValue(updated, selection = TextRange(updated.length))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.back), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(R.string.keyboard_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { focus.requestFocus(); ime?.show() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("⌨", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Свободное место над системной клавиатурой работает тачпадом: переключаться не нужно.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TrackpadSurface(
                transport = transport,
                settings = settings,
                modifier = Modifier.fillMaxSize(),
            )
            // Поле-невидимка: только фокус и приём текста от IME, размер 1 dp.
            BasicTextField(
                value = field,
                onValueChange = { handleChange(it) },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .focusRequester(focus),
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.None,
                ),
            )
        }

        if (unsupported > 0) {
            Text(
                text = stringResource(R.string.kb_unsupported, unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun Char.isCyrillicLetter(): Boolean =
    this in 'а'..'я' || this in 'А'..'Я' || this == 'ё' || this == 'Ё'