package dev.pocketinput.ui.keyboard

import dev.pocketinput.hid.KEYBOARD_QUEUE_CAPACITY

// Полная замена хвоста требует до двух операций на один сохранённый символ.
internal const val MAX_EDITABLE_TAIL = KEYBOARD_QUEUE_CAPACITY / 2

internal object TextDiff {

    data class Result(val deleted: Int, val inserted: String)

    // HID Backspace работает у курсора в конце. Даже общий суффикс нужно набрать заново.
    fun diff(old: String, new: String): Result {
        var prefix = 0
        val maxPrefix = minOf(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        return Result(old.length - prefix, new.substring(prefix))
    }
}

internal data class KeyboardEdit(
    val localText: String,
    val queuedText: String,
    val keys: List<Pair<Int, Int>>,
    val skippedCharacters: Int,
    val russianLayout: Boolean,
)

internal fun planKeyboardEdit(
    previouslyQueuedText: String,
    nextText: String,
    composing: Boolean,
    russianLayout: Boolean,
): KeyboardEdit? {
    if (composing) return null

    val supportedText = hidText(nextText)
    val diff = TextDiff.diff(previouslyQueuedText, supportedText)
    var useRussianLayout = russianLayout
    val keys = buildList {
        repeat(diff.deleted) { add(Usages.BACKSPACE to 0) }
        for (char in diff.inserted) {
            val key = if (char == '\n') Usages.ENTER to 0 else checkNotNull(CharMap.map(char, useRussianLayout))
            add(key)
            when (char.lowercaseChar()) {
                in 'а'..'я', 'ё' -> useRussianLayout = true
                in 'a'..'z' -> useRussianLayout = false
            }
        }
    }
    // Enter может перенести фокус Windows. Доступна правка только текста после Enter.
    val line = nextText.substringAfterLast('\n')
    val localText = line.takeLast(MAX_EDITABLE_TAIL).let { tail ->
        if (line.length > tail.length && tail.first().isLowSurrogate()) tail.drop(1) else tail
    }
    val queuedText = if (localText == nextText) supportedText else hidText(localText)
    return KeyboardEdit(
        localText = localText,
        queuedText = queuedText,
        keys = keys,
        skippedCharacters = nextText.codePointCount(0, nextText.length) - supportedText.length,
        russianLayout = useRussianLayout,
    )
}

private fun hidText(text: String): String = buildString {
    for (char in text) {
        if (char == '\n' || CharMap.map(char) != null) append(char)
    }
}
