package dev.pocketinput.ui.keyboard

import dev.pocketinput.hid.KEYBOARD_QUEUE_CAPACITY

// Для замены нужны максимум две операции на единицу хранимого текста.
internal const val MAX_EDITABLE_TAIL = KEYBOARD_QUEUE_CAPACITY / 2

internal object TextDiff {
    data class Result(val deleted: Int, val inserted: String, val retainedSuffix: Int = 0)

    fun diff(old: String, new: String): Result {
        var prefix = 0
        val maxPrefix = minOf(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

        var suffix = 0
        // Enter может сменить фокус ПК. Стрелки допустимы только внутри одного поля.
        if ('\n' !in new) {
            while (suffix < maxPrefix - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) {
                suffix++
            }
        }
        return Result(old.length - prefix - suffix, new.substring(prefix, new.length - suffix), suffix)
    }
}

internal data class KeyboardEdit(
    val localText: String,
    val queuedText: String,
    val keys: List<Pair<Int, Int>>,
    val skippedCharacters: Int,
    val droppedPrefix: Int,
)

internal fun planKeyboardEdit(
    previouslyQueuedText: String,
    nextText: String,
    russianLayout: Boolean,
): KeyboardEdit {
    val supportedText = hidText(nextText, russianLayout)
    val diff = TextDiff.diff(previouslyQueuedText, supportedText)
    // Курсор ПК остаётся в конце фрагмента между правками.
    val keys = buildList {
        repeat(diff.retainedSuffix) { add(Usages.ARROW_LEFT to 0) }
        repeat(diff.deleted) { add(Usages.BACKSPACE to 0) }
        for (char in diff.inserted) {
            add(if (char == '\n') Usages.ENTER to 0 else checkNotNull(CharMap.map(char, russianLayout)))
        }
        repeat(diff.retainedSuffix) { add(Usages.ARROW_RIGHT to 0) }
    }
    val lineStart = nextText.lastIndexOf('\n') + 1
    var tailStart = maxOf(lineStart, nextText.length - MAX_EDITABLE_TAIL)
    if (tailStart > lineStart && nextText[tailStart].isLowSurrogate()) {
        tailStart++
    }
    val localText = nextText.substring(tailStart)
    return KeyboardEdit(
        localText = localText,
        queuedText = if (tailStart == 0) supportedText else hidText(localText, russianLayout),
        keys = keys,
        skippedCharacters = nextText.codePointCount(0, nextText.length) - supportedText.length,
        droppedPrefix = tailStart,
    )
}

private fun hidText(text: String, russianLayout: Boolean): String = buildString {
    for (char in text) {
        if (char == '\n' || CharMap.map(char, russianLayout) != null) append(char)
    }
}
