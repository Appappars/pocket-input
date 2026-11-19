package dev.pocketinput.ui.keyboard

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal class ImeTextRebase {
    private var discardedPrefix = ""
    private var bufferBeforeLocalEdit: String? = null
    private var trimRevision = 0

    fun editableValue(value: TextFieldValue): TextFieldValue? {
        if (value.text == bufferBeforeLocalEdit) return null
        if (!value.text.startsWith(discardedPrefix)) return null
        return value.withoutPrefix(discardedPrefix.length)
    }

    fun discardPrefix(prefix: String, previousEditorText: String? = null): Int {
        if (previousEditorText != null && bufferBeforeLocalEdit == null) {
            bufferBeforeLocalEdit = discardedPrefix + previousEditorText
        }
        discardedPrefix += prefix
        return ++trimRevision
    }

    fun onValueApplied(revision: Int) {
        if (revision != trimRevision) return
        discardedPrefix = ""
        bufferBeforeLocalEdit = null
    }
}

internal fun TextFieldValue.withoutPrefix(length: Int): TextFieldValue {
    if (length == 0) return this
    val tail = text.substring(length)

    fun TextRange.inTail(): TextRange {
        val start = (start - length).coerceIn(0, tail.length)
        val end = (end - length).coerceIn(0, tail.length)
        return TextRange(start, end)
    }

    return TextFieldValue(
        text = tail,
        selection = selection.inTail(),
        composition = composition?.inTail()?.takeUnless { it.collapsed },
    )
}
