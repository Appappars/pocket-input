package dev.pocketinput.ui.keyboard

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeInputTest {
    @Test
    fun staleImeCallbackAfterEnterDoesNotRepeatTheLine() {
        val rebase = ImeTextRebase()
        val processor = EditProcessor()
        processor.reset(TextFieldValue("abc", TextRange(3)), null)

        val entered = processor.apply(listOf(CommitTextCommand("\n", 1)))
        val enter = planKeyboardEdit("abc", entered.text, russianLayout = false)
        val revision = rebase.discardPrefix(entered.text.take(enter.droppedPrefix))
        assertEquals(listOf(Usages.ENTER to 0), enter.keys)
        assertEquals("", enter.localText)

        rebase.onValueApplied(revision - 1)
        val unchanged = processor.apply(listOf(FinishComposingTextCommand()))
        assertEquals("abc\n", unchanged.text)
        val next = planKeyboardEdit(
            enter.queuedText,
            checkNotNull(rebase.editableValue(unchanged)).text,
            russianLayout = false,
        )
        assertEquals(emptyList<Pair<Int, Int>>(), next.keys)
    }

    @Test
    fun typingAfterEnterBeforeComposeAppliesTheTrimSendsOnlyTheNewCharacter() {
        val rebase = ImeTextRebase()
        val processor = EditProcessor()
        processor.reset(TextFieldValue("abc", TextRange(3)), null)

        val entered = processor.apply(listOf(CommitTextCommand("\n", 1)))
        val enter = planKeyboardEdit("abc", entered.text, russianLayout = false)
        rebase.discardPrefix(entered.text.take(enter.droppedPrefix))

        val raw = processor.apply(listOf(CommitTextCommand("x", 1)))
        val next = checkNotNull(rebase.editableValue(raw))
        assertEquals(TextFieldValue("x", TextRange(1)), next)
        assertEquals(
            listOf(CharMap.map('x')),
            planKeyboardEdit(enter.queuedText, next.text, russianLayout = false).keys,
        )
    }

    @Test
    fun retypingSameLineAfterComposeAppliesTheTrimIsAllowed() {
        val rebase = ImeTextRebase()
        val processor = EditProcessor()
        processor.reset(TextFieldValue("abc", TextRange(3)), null)

        val entered = processor.apply(listOf(CommitTextCommand("\n", 1)))
        val enter = planKeyboardEdit("abc", entered.text, russianLayout = false)
        val revision = rebase.discardPrefix(entered.text.take(enter.droppedPrefix))
        processor.reset(entered.withEditableTail(enter), null)
        rebase.onValueApplied(revision)

        val raw = processor.apply(listOf(CommitTextCommand("abc\n", 1)))
        val next = checkNotNull(rebase.editableValue(raw))
        assertEquals(
            listOf(CharMap.map('a'), CharMap.map('b'), CharMap.map('c'), Usages.ENTER to 0),
            planKeyboardEdit(enter.queuedText, next.text, russianLayout = false).keys,
        )
    }

    @Test
    fun twoUnappliedTrimsDoNotResendCharactersPastTheEditableTailLimit() {
        val rebase = ImeTextRebase()
        val processor = EditProcessor()
        val initial = "a".repeat(MAX_EDITABLE_TAIL)
        processor.reset(TextFieldValue(initial, TextRange(initial.length)), null)

        var sent = initial
        for (char in "bcd") {
            val raw = processor.apply(listOf(CommitTextCommand(char.toString(), 1)))
            val editable = checkNotNull(rebase.editableValue(raw))
            val update = planKeyboardEdit(sent, editable.text, russianLayout = false)
            assertEquals(listOf(CharMap.map(char)), update.keys)
            assertEquals(MAX_EDITABLE_TAIL, update.localText.length)
            rebase.discardPrefix(editable.text.take(update.droppedPrefix))
            sent = update.queuedText
        }
        assertEquals("a".repeat(MAX_EDITABLE_TAIL - 3) + "bcd", sent)
    }

    @Test
    fun staleImeEditOfDiscardedTextIsIgnored() {
        val rebase = ImeTextRebase()
        rebase.discardPrefix("abc\n")

        assertNull(rebase.editableValue(TextFieldValue("xbc\n")))
        assertEquals(
            TextFieldValue("x", TextRange(1), TextRange(0, 1)),
            rebase.editableValue(TextFieldValue("abc\nx", TextRange(5), TextRange(4, 5))),
        )
    }

    @Test
    fun unchangedImeBufferDoesNotUndoSymbolInsertedFromThePanel() {
        val rebase = ImeTextRebase()
        val previous = "a".repeat(MAX_EDITABLE_TAIL)
        val revision = rebase.discardPrefix("a", previousEditorText = previous)

        assertNull(rebase.editableValue(TextFieldValue(previous, TextRange(previous.length - 1))))

        rebase.onValueApplied(revision)
        val intentionalEdit = TextFieldValue(previous, TextRange(previous.length))
        assertEquals(intentionalEdit, rebase.editableValue(intentionalEdit))
    }
}
