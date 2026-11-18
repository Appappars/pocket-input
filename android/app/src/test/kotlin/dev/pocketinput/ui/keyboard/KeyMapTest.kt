package dev.pocketinput.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистой логики клавиатуры (docs/phase1-brief.md, «Клавиатура»).
 * Таблицы usage-кодов и sticky-модификаторы проверяются без Android.
 */
class KeyMapTest {

    private fun enRows() = KeyMap.rows(Layer.EN)
    private fun letterKeys(layer: Layer): List<Key> = KeyMap.keys(layer).filter {
        it.kind == KeyKind.CHAR && it.label.length == 1 && it.label[0].isLetter() && it.usage in 0x04..0x1D
    }

    // 1
    @Test
    fun `en letters cover 0x04 to 0x1D exactly once`() {
        val keys = letterKeys(Layer.EN)
        assertEquals("английских букв должно быть 26", 26, keys.size)
        val usages = keys.map { it.usage }.sorted()
        assertEquals((0x04..0x1D).toList(), usages)
    }

    // 2
    @Test
    fun `en letter usages are alphabetical`() {
        val byLabel = letterKeys(Layer.EN).associate { it.label to it.usage }
        assertEquals(0x04, byLabel.getValue("a"))
        assertEquals(0x14, byLabel.getValue("q"))
        assertEquals(0x17, byLabel.getValue("t"))
        assertEquals(0x1D, byLabel.getValue("z"))
        assertEquals(0x1C, byLabel.getValue("y"))
    }

    // 3
    @Test
    fun `ru letters cover same 26 usages as en`() {
        val ru = letterKeys(Layer.RU)
        assertEquals("кириллических букв на слое RU", 26, ru.size)
        assertEquals((0x04..0x1D).toList(), ru.map { it.usage }.sorted())
    }

    // 4
    @Test
    fun `ru keys sit on the same physical positions as en`() {
        val en = enRows().take(3)
        val ru = KeyMap.rows(Layer.RU).take(3)
        en.forEachIndexed { rowIndex, enRow ->
            val enLetters = enRow.filter { it.usage in 0x04..0x1D }
            val ruLetters = ru[rowIndex].filter { it.usage in 0x04..0x1D }
            assertEquals("число букв в ряду $rowIndex", enLetters.size, ruLetters.size)
            enLetters.indices.forEach { i ->
                assertEquals(
                    "ряд $rowIndex поз $i: RU должна повторять usage EN",
                    enLetters[i].usage,
                    ruLetters[i].usage,
                )
            }
        }
    }

    // 5
    @Test
    fun `ru row labels are cyrillic and differ from en`() {
        val enLabels = enRows().take(3).flatten().filter { it.kind == KeyKind.CHAR }.map { it.label }
        val ruLabels = KeyMap.rows(Layer.RU).take(3).flatten().filter { it.kind == KeyKind.CHAR }.map { it.label }
        assertTrue(ruLabels.contains("й"))
        assertTrue(ruLabels.contains("я"))
        assertTrue(ruLabels.contains("ь"))
        assertTrue(enLabels.intersect(ruLabels.toSet()).isEmpty())
    }

    // 6
    @Test
    fun `sym digits use 0x1E to 0x27`() {
        val digits = KeyMap.rows(Layer.SYM)[0]
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), digits.map { it.label })
        assertEquals((0x1E..0x27).toList(), digits.map { it.usage })
    }

    // 7
    @Test
    fun `sym punctuation uses brief table`() {
        val row = KeyMap.rows(Layer.SYM)[1]
        assertEquals(listOf("-", "=", "[", "]", ";", "'", ",", ".", "/"), row.map { it.label })
        assertEquals(
            listOf(0x2D, 0x2E, 0x2F, 0x30, 0x33, 0x34, 0x36, 0x37, 0x38),
            row.map { it.usage },
        )
    }

    // 8
    @Test
    fun `exclamation is usage of 1 plus lshift`() {
        val bang = KeyMap.keys(Layer.SYM2).first { it.label == "!" }
        assertEquals(0x1E, bang.usage)
        val (usage, mods) = KeyMap.resolveUsage(bang, shifted = false)
        assertEquals(0x1E, usage)
        assertEquals(Mods.LSHIFT, mods)
    }

    // 9
    @Test
    fun `every sym2 symbol is base usage plus lshift`() {
        val sym2 = KeyMap.keys(Layer.SYM2).filter { it.kind == KeyKind.CHAR && it.label.length == 1 }
        assertEquals(19 + 2, sym2.size)
        sym2.forEach { key ->
            val (usage, mods) = KeyMap.resolveUsage(key, shifted = false)
            assertEquals("символ ${key.label} должен требовать LSHIFT", Mods.LSHIFT, mods)
            assertTrue("usage ${key.label}", usage in 0x1E..0x27 || usage in 0x2D..0x38)
        }
        assertEquals(0x27, sym2.first { it.label == ")" }.usage)
        assertEquals(0x2D, sym2.first { it.label == "_" }.usage)
        assertEquals(0x38, sym2.first { it.label == "?" }.usage)
    }

    // 10
    @Test
    fun `shifted digit key sends lshift`() {
        val one = KeyMap.keys(Layer.SYM).first { it.label == "1" }
        assertEquals(0x1E to 0, KeyMap.resolveUsage(one, shifted = false))
        assertEquals(0x1E to Mods.LSHIFT, KeyMap.resolveUsage(one, shifted = true))
    }

    // 11
    @Test
    fun `control keys use brief usages in every layer`() {
        assertEquals(0x28, Usages.ENTER)
        assertEquals(0x29, Usages.ESC)
        assertEquals(0x2A, Usages.BACKSPACE)
        assertEquals(0x2B, Usages.TAB)
        assertEquals(0x2C, Usages.SPACE)
        assertEquals(0x4C, Usages.DEL)

        val expected = mapOf(
            KeyKind.ENTER to 0x28,
            KeyKind.ESC to 0x29,
            KeyKind.BACKSPACE to 0x2A,
            KeyKind.TAB to 0x2B,
            KeyKind.SPACE to 0x2C,
            KeyKind.DEL to 0x4C,
        )
        Layer.values().forEach { layer ->
            expected.forEach { (kind, usage) ->
                val key = KeyMap.keys(layer).firstOrNull { it.kind == kind }
                assertNotNull("слой $layer без клавиши $kind", key)
                assertEquals("слой $layer клавиша $kind", usage, key!!.usage)
            }
        }
    }

    // 12
    @Test
    fun `arrows use 0x4F to 0x52 in every layer`() {
        val expected = mapOf(
            KeyKind.ARROW_RIGHT to 0x4F,
            KeyKind.ARROW_LEFT to 0x50,
            KeyKind.ARROW_DOWN to 0x51,
            KeyKind.ARROW_UP to 0x52,
        )
        Layer.values().forEach { layer ->
            expected.forEach { (kind, usage) ->
                val key = KeyMap.keys(layer).firstOrNull { it.kind == kind }
                assertNotNull("слой $layer без стрелки $kind", key)
                assertEquals(usage, key!!.usage)
            }
        }
    }

    // 13
    @Test
    fun `modifier masks match brief`() {
        assertEquals(0x01, Mods.LCTRL)
        assertEquals(0x02, Mods.LSHIFT)
        assertEquals(0x04, Mods.LALT)
        assertEquals(0x08, Mods.LGUI)
        assertEquals(0x10, Mods.RCTRL)
        assertEquals(0x20, Mods.RSHIFT)
        assertEquals(0x40, Mods.RALT)
        assertEquals(0x80, Mods.RGUI)

        val mods = KeyMap.keys(Layer.EN).filter { it.kind == KeyKind.MOD }
        assertEquals(
            setOf(Mods.LCTRL, Mods.LALT, Mods.LGUI, Mods.LSHIFT),
            mods.map { it.modMask }.toSet(),
        )
    }

    // 14
    @Test
    fun `armed modifier clears after one key`() {
        var state = KeyMap.toggleModifier(StickyState(), Mods.LCTRL)
        assertTrue(state.isArmed(Mods.LCTRL))
        assertTrue(!state.isLocked(Mods.LCTRL))
        assertEquals(Mods.LCTRL, state.mask)

        val letter = KeyMap.keys(Layer.EN).first { it.label == "c" }
        val (usage, extra) = KeyMap.resolveUsage(letter, shifted = false)
        assertEquals(0x06, usage)
        assertEquals(0, extra)

        state = KeyMap.afterKeyPress(state)
        assertEquals(StickyState(), state)
        assertEquals(0, state.mask)
    }

    // 15
    @Test
    fun `double tap locks modifier until tapped again`() {
        var state = KeyMap.toggleModifier(StickyState(), Mods.LSHIFT) // взведён
        state = KeyMap.toggleModifier(state, Mods.LSHIFT)              // залип
        assertTrue(state.isLocked(Mods.LSHIFT))
        assertTrue(state.hasLocked())
        assertTrue(!state.isArmed(Mods.LSHIFT))

        // обычная клавиша не снимает залипание
        state = KeyMap.afterKeyPress(state)
        assertTrue("залипание сохраняется после клавиши", state.isLocked(Mods.LSHIFT))

        // залип Shift превращает букву в заглавную через маску
        val letter = KeyMap.keys(Layer.EN).first { it.label == "a" }
        assertEquals(0x04 to Mods.LSHIFT, KeyMap.resolveUsage(letter, shifted = state.isLocked(Mods.LSHIFT)))

        // третье нажатие снимает
        state = KeyMap.toggleModifier(state, Mods.LSHIFT)
        assertEquals(StickyState(), state)
    }

    // 16
    @Test
    fun `two modifiers can be armed at once`() {
        var state = KeyMap.toggleModifier(StickyState(), Mods.LCTRL)
        state = KeyMap.toggleModifier(state, Mods.LALT)
        assertEquals(Mods.LCTRL or Mods.LALT, state.mask)
        state = KeyMap.afterKeyPress(state)
        assertEquals(0, state.mask)
    }

    // 17
    @Test
    fun `layer keys switch and report layout strings`() {
        val enKey = KeyMap.keys(Layer.RU).first { it.kind == KeyKind.LAYER && it.targetLayer == Layer.EN }
        assertEquals(Layer.EN, enKey.targetLayer)
        assertEquals("en", KeyMap.layoutOf(Layer.EN))
        assertEquals("ru", KeyMap.layoutOf(Layer.RU))
        assertNull(KeyMap.layoutOf(Layer.SYM))
        assertNull(KeyMap.layoutOf(Layer.SYM2))
        assertEquals(Layer.RU, KeyMap.layerFromLayout("ru"))
        assertEquals(Layer.EN, KeyMap.layerFromLayout("en"))
        assertEquals(Layer.EN, KeyMap.layerFromLayout(""))
        assertTrue(KeyMap.keys(Layer.EN).any { it.kind == KeyKind.LAYER })
    }

    // 18
    @Test
    fun `every layer has shift ctrl alt win and backspace`() {
        Layer.values().forEach { layer ->
            val mods = KeyMap.keys(layer).filter { it.kind == KeyKind.MOD }.map { it.modMask }.toSet()
            assertTrue("слой $layer без Shift", Mods.LSHIFT in mods)
            assertTrue("слой $layer без Ctrl", Mods.LCTRL in mods)
            assertTrue("слой $layer без Alt", Mods.LALT in mods)
            assertTrue("слой $layer без Win", Mods.LGUI in mods)
            assertEquals(
                "слой $layer должен иметь ровно один Backspace",
                1,
                KeyMap.keys(layer).count { it.kind == KeyKind.BACKSPACE },
            )
        }
    }

    // 19
    @Test
    fun `rows fit portrait screen`() {
        Layer.values().forEach { layer ->
            val rows = KeyMap.rows(layer)
            assertTrue("слой $layer: не больше 5 рядов", rows.size <= 5)
            rows.forEach { row ->
                assertTrue("пустой ряд в слое $layer", row.isNotEmpty())
                assertTrue("ряд шире 10 клавиш в слое $layer", row.size <= 12)
            }
        }
    }

    // 20
    @Test
    fun `modifier names list locked modifiers`() {
        assertEquals("Ctrl+Alt", KeyMap.modifierNames(Mods.LCTRL or Mods.LALT))
        assertEquals("Shift", KeyMap.modifierNames(Mods.LSHIFT))
        assertEquals("", KeyMap.modifierNames(0))
    }
}