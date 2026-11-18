package dev.pocketinput.ui.keyboard

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.pocketinput.hid.KEYBOARD_QUEUE_CAPACITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты преобразования символа в HID usage-код и разницы текста от IME.
 * Числа — из docs/phase1-brief.md (таблица usage-кодов и маски модификаторов).
 */
class CharMapTest {

    @Test
    fun latinLowercase_mapsToSequentialUsages() {
        assertEquals(0x04 to 0, CharMap.map('a'))
        assertEquals(0x1D to 0, CharMap.map('z'))
        // все 26 букв покрыты и идут подряд
        for (i in 0..25) assertEquals((0x04 + i) to 0, CharMap.map('a' + i))
    }

    @Test
    fun latinUppercase_requiresShift() {
        assertEquals(0x04 to CharMap.LSHIFT, CharMap.map('A'))
        assertEquals(0x1D to CharMap.LSHIFT, CharMap.map('Z'))
    }

    @Test
    fun digits_andZero_useCorrectUsages() {
        assertEquals(0x1E to 0, CharMap.map('1'))
        assertEquals(0x26 to 0, CharMap.map('9'))
        assertEquals(0x27 to 0, CharMap.map('0'))
    }

    @Test
    fun punctuation_plainAndShifted() {
        assertEquals(0x2D to 0, CharMap.map('-'))
        assertEquals(0x2D to CharMap.LSHIFT, CharMap.map('_'))
        assertEquals(0x1E to CharMap.LSHIFT, CharMap.map('!'))
        assertEquals(0x27 to CharMap.LSHIFT, CharMap.map(')'))
        assertEquals(0x38 to CharMap.LSHIFT, CharMap.map('?'))
        assertEquals(0x2C to 0, CharMap.map(' '))
    }

    @Test
    fun cyrillic_usesPhysicalPositionsOfRussianLayout() {
        // Й на месте Q, Ф на месте A, Я на месте Z, Ё на месте `
        assertEquals(0x14 to 0, CharMap.map('й', ruLayout = true))
        assertEquals(0x04 to 0, CharMap.map('ф', ruLayout = true))
        assertEquals(0x1D to 0, CharMap.map('я', ruLayout = true))
        assertEquals(0x35 to 0, CharMap.map('ё', ruLayout = true))
        // все 33 буквы покрыты
        val alphabet = "йцукенгшщзхъфывапролджэячсмитьбюё"
        assertEquals(33, alphabet.length)
        assertTrue(alphabet.all { CharMap.map(it, ruLayout = true) != null })
        assertNull(CharMap.map('й'))
        assertNull(CharMap.map('q', ruLayout = true))
    }

    @Test
    fun cyrillicUppercase_requiresShift() {
        assertEquals(0x14 to CharMap.LSHIFT, CharMap.map('Й', ruLayout = true))
        assertEquals(0x04 to CharMap.LSHIFT, CharMap.map('Ф', ruLayout = true))
    }

    @Test
    fun russianLayout_movesPunctuationToRussianKeys() {
        // В русской раскладке запятая — это Shift+«/», точка — «/», иначе выходило «б» вместо запятой.
        assertEquals(0x38 to CharMap.LSHIFT, CharMap.map(',', ruLayout = true))
        assertEquals(0x38 to 0, CharMap.map('.', ruLayout = true))
        assertEquals(0x31 to CharMap.LSHIFT, CharMap.map('/', ruLayout = true))
        // знаки, которые в RU стоят там же, что и в US, не меняются
        assertEquals(0x2D to 0, CharMap.map('-', ruLayout = true))
        assertEquals(0x1E to CharMap.LSHIFT, CharMap.map('!', ruLayout = true))
        assertEquals(0x36 to 0, CharMap.map('б', ruLayout = true))
        // US-режим остаётся прежним
        assertEquals(0x36 to 0, CharMap.map(','))
    }

    @Test
    fun emojiAndCjk_areNotExpressible() {
        // Эмодзи вне BMP: в строке это две суррогатные единицы, каждая не имеет usage-кода.
        assertNull(CharMap.map('\uD83D'))
        assertNull(CharMap.map('\uDE42'))
        assertNull(CharMap.map('漢'))
    }
}

class TextDiffTest {

    @Test
    fun noChange_isEmpty() {
        assertEquals(TextDiff.Result(0, ""), TextDiff.diff("abc", "abc"))
    }

    @Test
    fun appendSingleChar() {
        assertEquals(TextDiff.Result(0, "d"), TextDiff.diff("abc", "abcd"))
    }

    @Test
    fun appendWordFromAutocorrect() {
        assertEquals(TextDiff.Result(0, "hello"), TextDiff.diff("", "hello"))
    }

    @Test
    fun singleDelete() {
        assertEquals(TextDiff.Result(1, ""), TextDiff.diff("abc", "ab"))
    }

    @Test
    fun wordDeleteFromIme() {
        assertEquals(TextDiff.Result(5, ""), TextDiff.diff("hello", ""))
    }

    @Test
    fun replaceSelection_retainsCommonSuffix() {
        assertEquals(TextDiff.Result(1, "x", 1), TextDiff.diff("abc", "axc"))
    }

    @Test
    fun insertInMiddle_retainsCommonSuffix() {
        assertEquals(TextDiff.Result(0, "XY", 1), TextDiff.diff("ab", "aXYb"))
    }

    @Test
    fun cyrillicIsCountedAsSingleChars() {
        assertEquals(TextDiff.Result(0, "привет"), TextDiff.diff("", "привет"))
        assertEquals(TextDiff.Result(1, ""), TextDiff.diff("привет", "приве"))
    }
}

class KeyboardEditTest {

    @Test
    fun composingLetterIsSentImmediately() {
        val composing = TextFieldValue("п", selection = TextRange(1), composition = TextRange(0, 1))
        val edit = planKeyboardEdit("", composing.text, russianLayout = true)
        assertEquals(listOf(0x0A to 0), edit.keys)
        assertEquals(composing, composing.withEditableTail(edit))
    }

    @Test
    fun trimmingAdjustsSelectionAndPreservesTheRemainingComposition() {
        val longText = "a".repeat(MAX_EDITABLE_TAIL + 3)
        val value = TextFieldValue(
            longText,
            selection = TextRange(10, 7),
            composition = TextRange(2, 11),
        )
        val edit = planKeyboardEdit("", value.text, russianLayout = false)

        val trimmed = value.withEditableTail(edit)
        assertEquals("a".repeat(MAX_EDITABLE_TAIL), trimmed.text)
        assertEquals(TextRange(7, 4), trimmed.selection)
        assertEquals(TextRange(0, 8), trimmed.composition)
    }

    @Test
    fun middleCorrectionKeepsUnchangedLetters() {
        val edit = planKeyboardEdit("abc", "axc", russianLayout = false)
        assertEquals(listOf(
            Usages.ARROW_LEFT to 0,
            Usages.BACKSPACE to 0,
            0x1B to 0,
            Usages.ARROW_RIGHT to 0,
        ), edit.keys)
    }

    @Test
    fun questionMarkWithRussianLayoutUsesShiftAndSeven() {
        assertEquals(0x24 to CharMap.LSHIFT, CharMap.map('?', ruLayout = true))
    }

    @Test
    fun deletingSkippedEmoji_doesNotDeleteSentText() {
        val insertion = planKeyboardEdit("a", "a🙂", russianLayout = false)
        assertEquals("a", insertion.queuedText)
        assertEquals(1, insertion.skippedCharacters)
        assertEquals(emptyList<Pair<Int, Int>>(), insertion.keys)

        val deletion = planKeyboardEdit(insertion.queuedText, "a", russianLayout = false)
        assertEquals(0, deletion.skippedCharacters)
        assertEquals(emptyList<Pair<Int, Int>>(), deletion.keys)
    }

    @Test
    fun deletingUnsupportedTextInMiddle_preservesTheWholeSentSuffix() {
        val initial = planKeyboardEdit("", "a漢b", russianLayout = false)
        assertEquals("ab", initial.queuedText)
        assertEquals(listOf(0x04 to 0, 0x05 to 0), initial.keys)

        val corrected = planKeyboardEdit(initial.queuedText, "ab", russianLayout = false)
        assertEquals(emptyList<Pair<Int, Int>>(), corrected.keys)
    }

    @Test
    fun composingWordAppearsIncrementallyAndCommitDoesNotRepeatIt() {
        val first = planKeyboardEdit("", "a", russianLayout = false)
        val second = planKeyboardEdit(first.queuedText, "ab", russianLayout = false)
        val third = planKeyboardEdit(second.queuedText, "abc", russianLayout = false)

        assertEquals(listOf(0x04 to 0), first.keys)
        assertEquals(listOf(0x05 to 0), second.keys)
        assertEquals(listOf(0x06 to 0), third.keys)
        assertTrue(planKeyboardEdit(third.queuedText, "abc", russianLayout = false).keys.isEmpty())
        assertEquals(
            listOf(Usages.SPACE to 0),
            planKeyboardEdit(third.queuedText, "abc ", russianLayout = false).keys,
        )
    }

    @Test
    fun removingComposingLetterRetractsOnlyThatLetter() {
        val preview = planKeyboardEdit("a", "ab", russianLayout = false)
        val cancelled = planKeyboardEdit(preview.queuedText, "a", russianLayout = false)
        assertEquals(listOf(Usages.BACKSPACE to 0), cancelled.keys)
    }

    @Test
    fun russianTextUsesRussianPunctuationAndKeepsEnter() {
        val edit = planKeyboardEdit("", "ф?:;,\n", russianLayout = true)
        assertEquals(listOf(
            0x04 to 0,
            0x24 to CharMap.LSHIFT,
            0x23 to CharMap.LSHIFT,
            0x21 to CharMap.LSHIFT,
            0x38 to CharMap.LSHIFT,
            Usages.ENTER to 0,
        ), edit.keys)
        assertEquals(6, edit.droppedPrefix)
        assertEquals("", edit.queuedText)
    }

    @Test
    fun symbolsFollowTheSelectedWindowsLayout() {
        val russian = CharMap.symbols(ruLayout = true)
        val english = CharMap.symbols(ruLayout = false)

        assertTrue(listOf('?', '!', ':', ';', '№').all { it in russian })
        assertTrue(listOf('?', '!', ':', ';', '@').all { it in english })
        assertFalse('@' in russian)
        assertFalse('№' in english)
        assertEquals(0x38 to CharMap.LSHIFT, CharMap.map('?'))
    }

    @Test
    fun enterInMiddleDoesNotMoveCursorInTheNextField() {
        val edit = planKeyboardEdit("abc", "a\nbc", russianLayout = false)
        assertEquals(listOf(
            Usages.BACKSPACE to 0,
            Usages.BACKSPACE to 0,
            Usages.ENTER to 0,
            0x05 to 0,
            0x06 to 0,
        ), edit.keys)
        assertEquals("bc", edit.localText)
        assertEquals(2, edit.droppedPrefix)
    }

    @Test
    fun unsupportedLayoutCharacterDoesNotCauseRemovalOfSentText() {
        val edit = planKeyboardEdit("ab", "aяb", russianLayout = false)
        assertTrue(edit.keys.isEmpty())
        assertEquals("ab", edit.queuedText)
        assertEquals(1, edit.skippedCharacters)
    }

    @Test
    fun enterForgetsPreviousFieldSoImeBackspaceCannotDeleteIt() {
        val enter = planKeyboardEdit("abc", "abc\n", russianLayout = false)
        assertEquals(listOf(Usages.ENTER to 0), enter.keys)
        assertEquals("", enter.localText)
        assertEquals("", enter.queuedText)

        val deletion = planKeyboardEdit(enter.queuedText, "", russianLayout = false)
        assertEquals(emptyList<Pair<Int, Int>>(), deletion.keys)
    }

    @Test
    fun multilinePastePreservesOnlyLastEditableLineIncludingSkippedCharacters() {
        val paste = planKeyboardEdit("", "a\nb\n🙂c", russianLayout = false)
        assertEquals(listOf(0x04 to 0, Usages.ENTER to 0, 0x05 to 0, Usages.ENTER to 0, 0x06 to 0), paste.keys)
        assertEquals("🙂c", paste.localText)
        assertEquals("c", paste.queuedText)

        val deletion = planKeyboardEdit(paste.queuedText, "🙂", russianLayout = false)
        assertEquals(listOf(Usages.BACKSPACE to 0), deletion.keys)
    }

    @Test
    fun entireEditableTailCanBeReplacedWithinQueueCapacity() {
        val longText = "a".repeat(KEYBOARD_QUEUE_CAPACITY)
        val paste = planKeyboardEdit("", longText, russianLayout = false)
        val retained = "a".repeat(MAX_EDITABLE_TAIL)
        assertEquals(KEYBOARD_QUEUE_CAPACITY, paste.keys.size)
        assertEquals(retained, paste.localText)
        assertEquals(retained, paste.queuedText)

        val replacement = "b".repeat(MAX_EDITABLE_TAIL)
        val edit = planKeyboardEdit(paste.queuedText, replacement, russianLayout = false)
        assertEquals(KEYBOARD_QUEUE_CAPACITY, edit.keys.size)
        assertEquals(List(MAX_EDITABLE_TAIL) { Usages.BACKSPACE to 0 }, edit.keys.take(MAX_EDITABLE_TAIL))
        assertEquals(replacement, edit.queuedText)
    }

    @Test
    fun trimmingEditableTailDoesNotSplitSurrogatePair() {
        val suffix = "b".repeat(MAX_EDITABLE_TAIL - 1)
        val edit = planKeyboardEdit("", "a🙂$suffix", russianLayout = false)
        assertEquals(suffix, edit.localText)
        assertEquals(suffix, edit.queuedText)
        assertEquals(1, edit.skippedCharacters)
    }
}
