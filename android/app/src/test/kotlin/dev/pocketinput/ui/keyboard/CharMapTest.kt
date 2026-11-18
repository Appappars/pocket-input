package dev.pocketinput.ui.keyboard

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
    fun replaceSelection() {
        // «hel» заменили на «Х»: удалить 1, вставить 1
        assertEquals(TextDiff.Result(2, "X"), TextDiff.diff("hello", "hXlo"))
    }

    @Test
    fun insertInMiddle() {
        assertEquals(TextDiff.Result(0, "XY"), TextDiff.diff("ab", "aXYb"))
    }

    @Test
    fun cyrillicIsCountedAsSingleChars() {
        assertEquals(TextDiff.Result(0, "привет"), TextDiff.diff("", "привет"))
        assertEquals(TextDiff.Result(1, ""), TextDiff.diff("привет", "приве"))
    }
}