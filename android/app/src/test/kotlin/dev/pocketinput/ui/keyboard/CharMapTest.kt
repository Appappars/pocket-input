package dev.pocketinput.ui.keyboard

import dev.pocketinput.hid.KEYBOARD_QUEUE_CAPACITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(0x14 to 0, CharMap.map('й'))
        assertEquals(0x04 to 0, CharMap.map('ф'))
        assertEquals(0x1D to 0, CharMap.map('я'))
        assertEquals(0x35 to 0, CharMap.map('ё'))
        // все 33 буквы покрыты
        val alphabet = "йцукенгшщзхъфывапролджэячсмитьбюё"
        assertEquals(33, alphabet.length)
        assertEquals(0, CharMap.unsupportedCount(alphabet))
    }

    @Test
    fun cyrillicUppercase_requiresShift() {
        assertEquals(0x14 to CharMap.LSHIFT, CharMap.map('Й'))
        assertEquals(0x04 to CharMap.LSHIFT, CharMap.map('Ф'))
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
        // буквы не зависят от режима
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
        assertEquals(2, CharMap.unsupportedCount("ok🙂"))
        assertEquals(1, CharMap.unsupportedCount("ok漢"))  // 'o','k' выразимы, '漢' — нет
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
    fun replaceSelection_rewritesFromFirstDifference() {
        assertEquals(TextDiff.Result(2, "xc"), TextDiff.diff("abc", "axc"))
    }

    @Test
    fun insertInMiddle_recreatesUnchangedSuffixAtHostCaret() {
        assertEquals(TextDiff.Result(1, "XYb"), TextDiff.diff("ab", "aXYb"))
    }

    @Test
    fun cyrillicIsCountedAsSingleChars() {
        assertEquals(TextDiff.Result(0, "привет"), TextDiff.diff("", "привет"))
        assertEquals(TextDiff.Result(1, ""), TextDiff.diff("привет", "приве"))
    }
}

class KeyboardEditTest {

    @Test
    fun deletingSkippedEmoji_doesNotDeleteSentText() {
        val insertion = checkNotNull(planKeyboardEdit("a", "a🙂", composing = false, russianLayout = false))
        assertEquals("a", insertion.queuedText)
        assertEquals(1, insertion.skippedCharacters)
        assertEquals(emptyList<Pair<Int, Int>>(), insertion.keys)

        val deletion = checkNotNull(planKeyboardEdit(insertion.queuedText, "a", composing = false, russianLayout = false))
        assertEquals(0, deletion.skippedCharacters)
        assertEquals(emptyList<Pair<Int, Int>>(), deletion.keys)
    }

    @Test
    fun deletingUnsupportedTextInMiddle_preservesTheWholeSentSuffix() {
        val initial = checkNotNull(planKeyboardEdit("", "a漢b", composing = false, russianLayout = false))
        assertEquals("ab", initial.queuedText)
        assertEquals(listOf(0x04 to 0, 0x05 to 0), initial.keys)

        val corrected = checkNotNull(planKeyboardEdit(initial.queuedText, "ab", composing = false, russianLayout = false))
        assertEquals(emptyList<Pair<Int, Int>>(), corrected.keys)
    }

    @Test
    fun composingWord_waitsForCommitBeforeSendingKeys() {
        assertNull(planKeyboardEdit("a", "ab", composing = true, russianLayout = false))
        assertNull(planKeyboardEdit("a", "abc", composing = true, russianLayout = false))
        val committed = checkNotNull(planKeyboardEdit("a", "abc", composing = false, russianLayout = false))
        assertEquals(listOf(0x05 to 0, 0x06 to 0), committed.keys)

        val selectionOnly = checkNotNull(planKeyboardEdit(committed.queuedText, "abc", composing = false, russianLayout = false))
        assertEquals(emptyList<Pair<Int, Int>>(), selectionOnly.keys)
    }

    @Test
    fun cancellingComposition_doesNotSendDeletes() {
        assertNull(planKeyboardEdit("a", "aя", composing = true, russianLayout = false))
        val cancelled = checkNotNull(planKeyboardEdit("a", "a", composing = false, russianLayout = false))
        assertEquals(emptyList<Pair<Int, Int>>(), cancelled.keys)
    }

    @Test
    fun replacingMiddle_deletesAndReplaysSuffixInOrder() {
        val edit = checkNotNull(planKeyboardEdit("abc", "axc", composing = false, russianLayout = false))
        assertEquals("axc", edit.queuedText)
        assertEquals(listOf(Usages.BACKSPACE to 0, Usages.BACKSPACE to 0, 0x1B to 0, 0x06 to 0), edit.keys)
    }

    @Test
    fun russianContext_mapsPunctuationAndKeepsEnter() {
        val edit = checkNotNull(planKeyboardEdit("", "ф,\n", composing = false, russianLayout = false))
        assertEquals(listOf(0x04 to 0, 0x38 to CharMap.LSHIFT, Usages.ENTER to 0), edit.keys)
        assertEquals(true, edit.russianLayout)
    }

    @Test
    fun enterForgetsPreviousFieldSoImeBackspaceCannotDeleteIt() {
        val enter = checkNotNull(planKeyboardEdit("abc", "abc\n", composing = false, russianLayout = false))
        assertEquals(listOf(Usages.ENTER to 0), enter.keys)
        assertEquals("", enter.localText)
        assertEquals("", enter.queuedText)

        val deletion = checkNotNull(planKeyboardEdit(enter.queuedText, "", composing = false, russianLayout = false))
        assertEquals(emptyList<Pair<Int, Int>>(), deletion.keys)
    }

    @Test
    fun multilinePastePreservesOnlyLastEditableLineIncludingSkippedCharacters() {
        val paste = checkNotNull(planKeyboardEdit("", "a\nb\n🙂c", composing = false, russianLayout = false))
        assertEquals(listOf(0x04 to 0, Usages.ENTER to 0, 0x05 to 0, Usages.ENTER to 0, 0x06 to 0), paste.keys)
        assertEquals("🙂c", paste.localText)
        assertEquals("c", paste.queuedText)

        val deletion = checkNotNull(planKeyboardEdit(paste.queuedText, "🙂", composing = false, russianLayout = false))
        assertEquals(listOf(Usages.BACKSPACE to 0), deletion.keys)
    }

    @Test
    fun entireEditableTailCanBeReplacedWithinQueueCapacity() {
        val longText = "a".repeat(KEYBOARD_QUEUE_CAPACITY)
        val paste = checkNotNull(planKeyboardEdit("", longText, composing = false, russianLayout = false))
        val retained = "a".repeat(MAX_EDITABLE_TAIL)
        assertEquals(KEYBOARD_QUEUE_CAPACITY, paste.keys.size)
        assertEquals(retained, paste.localText)
        assertEquals(retained, paste.queuedText)

        val replacement = "b".repeat(MAX_EDITABLE_TAIL)
        val edit = checkNotNull(planKeyboardEdit(paste.queuedText, replacement, composing = false, russianLayout = false))
        assertEquals(KEYBOARD_QUEUE_CAPACITY, edit.keys.size)
        assertEquals(List(MAX_EDITABLE_TAIL) { Usages.BACKSPACE to 0 }, edit.keys.take(MAX_EDITABLE_TAIL))
        assertEquals(replacement, edit.queuedText)
    }

    @Test
    fun trimmingEditableTailDoesNotSplitSurrogatePair() {
        val suffix = "b".repeat(MAX_EDITABLE_TAIL - 1)
        val edit = checkNotNull(planKeyboardEdit("", "a🙂$suffix", composing = false, russianLayout = false))
        assertEquals(suffix, edit.localText)
        assertEquals(suffix, edit.queuedText)
        assertEquals(1, edit.skippedCharacters)
    }
}
