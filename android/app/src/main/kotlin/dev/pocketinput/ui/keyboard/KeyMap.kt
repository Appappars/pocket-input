package dev.pocketinput.ui.keyboard

/**
 * Раскладки фазы 1 (docs/phase1-brief.md, раздел «Клавиатура»).
 *
 * Объект без Android-зависимостей: таблицы слоёв, usage-коды HID (Usage Page 0x07),
 * маски модификаторов и чистая логика sticky-модификаторов. Покрывается JVM-тестами
 * (KeyMapTest), поэтому ни одного android.* / androidx.* импорта здесь быть не должно.
 *
 * Правило брифа: RU-слой отправляет ТЕ ЖЕ usage-коды, что и EN на тех же физических
 * позициях — HID передаёт позиции клавиш, а не буквы.
 */

/** Слои клавиатуры. */
enum class Layer { EN, RU, SYM, SYM2 }

/** Вид клавиши: по нему UI решает, что отправить и как отрисовать. */
enum class KeyKind {
    CHAR,
    MOD,
    BACKSPACE,
    ENTER,
    SPACE,
    TAB,
    ESC,
    ARROW_LEFT,
    ARROW_RIGHT,
    ARROW_UP,
    ARROW_DOWN,
    DEL,
    LAYER,
}

/** Маски модификаторов HID (brief: LCTRL 0x01 … RGUI 0x80). */
object Mods {
    const val LCTRL = 0x01
    const val LSHIFT = 0x02
    const val LALT = 0x04
    const val LGUI = 0x08
    const val RCTRL = 0x10
    const val RSHIFT = 0x20
    const val RALT = 0x40
    const val RGUI = 0x80
}

/** Обязательные usage-коды из таблицы брифа. */
object Usages {
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val DEL = 0x4C
    const val ARROW_RIGHT = 0x4F
    const val ARROW_LEFT = 0x50
    const val ARROW_DOWN = 0x51
    const val ARROW_UP = 0x52
}

/**
 * Клавиша слоя.
 *
 * @param usage      базовый usage-код (для CHAR — код физической позиции)
 * @param shiftLabel надпись shift-варианта (например, «1» → «!»); null — shift-варианта нет
 * @param needsShift этот символ всегда отправляется с LSHIFT (слой SYM2)
 * @param modMask    для MOD: какая маска взводится/залипает
 * @param targetLayer для LAYER: на какой слой переключает
 */
data class Key(
    val label: String,
    val usage: Int,
    val kind: KeyKind = KeyKind.CHAR,
    val widthWeight: Float = 1f,
    val shiftLabel: String? = null,
    val needsShift: Boolean = false,
    val modMask: Int = 0,
    val targetLayer: Layer? = null,
)

/** Состояние sticky-модификаторов: взведённые на одну клавишу и залипшие. */
data class StickyState(val armed: Int = 0, val locked: Int = 0) {
    /** Итоговая маска, которая уходит в HID. */
    val mask: Int get() = armed or locked

    fun isArmed(mask: Int): Boolean = armed and mask != 0
    fun isLocked(mask: Int): Boolean = locked and mask != 0
    fun hasLocked(): Boolean = locked != 0
}

object KeyMap {

    /** Физические ряды EN (QWERTY) — источник usage-кодов для всех слоёв. */
    private val EN_LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** RU (ЙЦУКЕН) на тех же физических позициях, что EN. */
    private val RU_LETTERS = listOf("йцукенгшщз", "фывапролд", "ячсмить")

    /** usage буквы EN: a=0x04 … z=0x1D (таблица брифа). */
    fun enLetterUsage(c: Char): Int {
        require(c in 'a'..'z') { "не буква EN: $c" }
        return 0x04 + (c - 'a')
    }

    /** usage буквы на той же позиции, что и EN (для RU-слоя). */
    fun letterUsageAt(row: Int, col: Int): Int = enLetterUsage(EN_LETTERS[row][col])

    /** usage базовой клавиши для символа shift-слоя: «!» → usage «1». */
    val SYM2_PAIRS: List<Pair<String, Int>> = listOf(
        "!" to 0x1E, "@" to 0x1F, "#" to 0x20, "$" to 0x21, "%" to 0x22,
        "^" to 0x23, "&" to 0x24, "*" to 0x25, "(" to 0x26, ")" to 0x27,
        "_" to 0x2D, "+" to 0x2E, "{" to 0x2F, "}" to 0x30, ":" to 0x33,
        "\"" to 0x34, "<" to 0x36, ">" to 0x37, "?" to 0x38,
    )

    /** Нижняя строка цифр/пунктуации слоя SYM: (надпись, usage, shift-надпись или null). */
    private val SYM_ROW1 = listOf(
        Triple("1", 0x1E, "!"), Triple("2", 0x1F, "@"), Triple("3", 0x20, "#"),
        Triple("4", 0x21, "$"), Triple("5", 0x22, "%"), Triple("6", 0x23, "^"),
        Triple("7", 0x24, "&"), Triple("8", 0x25, "*"), Triple("9", 0x26, "("),
        Triple("0", 0x27, ")"),
    )

    private val SYM_ROW2 = listOf(
        Triple("-", 0x2D, "_"), Triple("=", 0x2E, "+"), Triple("[", 0x2F, "{"),
        Triple("]", 0x30, "}"), Triple(";", 0x33, ":"), Triple("'", 0x34, "\""),
        Triple(",", 0x36, "<"), Triple(".", 0x37, ">"), Triple("/", 0x38, "?"),
    )

    // --- Служебные клавиши ---

    fun modKey(label: String, mask: Int, widthWeight: Float = 1.2f) =
        Key(label = label, usage = 0, kind = KeyKind.MOD, widthWeight = widthWeight, modMask = mask)

    fun layerKey(target: Layer, widthWeight: Float = 1.3f) =
        Key(label = target.name, usage = 0, kind = KeyKind.LAYER, widthWeight = widthWeight, targetLayer = target)

    private val BACKSPACE = Key("⌫", Usages.BACKSPACE, KeyKind.BACKSPACE, widthWeight = 1.6f)
    private val ENTER = Key("⏎", Usages.ENTER, KeyKind.ENTER, widthWeight = 1.6f)
    private val SPACE = Key("␣", Usages.SPACE, KeyKind.SPACE, widthWeight = 4f)
    private val SHIFT = modKey("⇧", Mods.LSHIFT, 1.6f)

    /** Ряд навигации — присутствует в КАЖДОМ слое (brief: обязательные клавиши). */
    private val NAV_ROW = listOf(
        Key("Esc", Usages.ESC, KeyKind.ESC, 1f),
        Key("Tab", Usages.TAB, KeyKind.TAB, 1.2f),
        Key("Del", Usages.DEL, KeyKind.DEL, 1.1f),
        Key("←", Usages.ARROW_LEFT, KeyKind.ARROW_LEFT, 1f),
        Key("↑", Usages.ARROW_UP, KeyKind.ARROW_UP, 1f),
        Key("↓", Usages.ARROW_DOWN, KeyKind.ARROW_DOWN, 1f),
        Key("→", Usages.ARROW_RIGHT, KeyKind.ARROW_RIGHT, 1f),
    )

    /** Нижняя строка: клавиши слоёв + Ctrl/Alt/Win + Space + Enter. */
    private fun bottomRow(layerKeys: List<Key>, withCommaDot: Boolean = false): List<Key> {
        val tail = mutableListOf<Key>()
        if (withCommaDot) {
            tail.add(Key(",", 0x36, widthWeight = 1f, shiftLabel = "<"))
            tail.add(Key(".", 0x37, widthWeight = 1f, shiftLabel = ">"))
        }
        tail.add(SPACE)
        tail.add(ENTER)
        return layerKeys + listOf(
            modKey("Ctrl", Mods.LCTRL),
            modKey("Alt", Mods.LALT),
            modKey("Win", Mods.LGUI),
        ) + tail
    }

    /** Строки буквенного слоя из надписей + usage-рядов EN (позиции совпадают). */
    private fun letterRows(labels: List<String>): List<List<Key>> =
        labels.mapIndexed { r, row ->
            row.mapIndexed { c, ch -> Key(ch.toString(), letterUsageAt(r, c)) }
        }.mapIndexed { r, row ->
            if (r == 2) listOf(SHIFT) + row + listOf(BACKSPACE) else row
        }

    private val EN_ROWS: List<List<Key>> =
        letterRows(EN_LETTERS) + listOf(NAV_ROW, bottomRow(listOf(layerKey(Layer.SYM)), withCommaDot = true))

    private val RU_ROWS: List<List<Key>> =
        letterRows(RU_LETTERS) + listOf(
            NAV_ROW,
            bottomRow(listOf(layerKey(Layer.EN), layerKey(Layer.SYM)), withCommaDot = true),
        )

    private val SYM_ROWS: List<List<Key>> = listOf(
        SYM_ROW1.map { (label, usage, shifted) ->
            Key(label, usage, widthWeight = 1f, shiftLabel = shifted)
        },
        SYM_ROW2.map { (label, usage, shifted) ->
            Key(label, usage, widthWeight = 1f, shiftLabel = shifted)
        },
        listOf(SHIFT, Key("\\", 0x31, widthWeight = 1f, shiftLabel = "|"), Key("`", 0x35, widthWeight = 1f, shiftLabel = "~"), BACKSPACE),
        NAV_ROW,
        bottomRow(listOf(layerKey(Layer.SYM2), layerKey(Layer.EN))),
    )

    private val SYM2_ROWS: List<List<Key>> = listOf(
        SYM2_PAIRS.take(10).map { (symbol, usage) -> Key(symbol, usage, needsShift = true) },
        SYM2_PAIRS.drop(10).map { (symbol, usage) -> Key(symbol, usage, needsShift = true) },
        listOf(
            SHIFT,
            Key("|", 0x31, needsShift = true),
            Key("~", 0x35, needsShift = true),
            BACKSPACE,
        ),
        NAV_ROW,
        bottomRow(listOf(layerKey(Layer.SYM), layerKey(Layer.EN))),
    )

    /** Строки слоя в порядке отрисовки (сверху вниз). */
    fun rows(layer: Layer): List<List<Key>> = when (layer) {
        Layer.EN -> EN_ROWS
        Layer.RU -> RU_ROWS
        Layer.SYM -> SYM_ROWS
        Layer.SYM2 -> SYM2_ROWS
    }

    /** Все клавиши слоя одним списком (для проверок и поиска). */
    fun keys(layer: Layer): List<Key> = rows(layer).flatten()

    /** Слой из settings.layout ("en" | "ru"). */
    fun layerFromLayout(layout: String): Layer = if (layout.equals("ru", ignoreCase = true)) Layer.RU else Layer.EN

    /** "en" | "ru" для settings.layout; null для символьных слоёв. */
    fun layoutOf(layer: Layer): String? = when (layer) {
        Layer.EN -> "en"
        Layer.RU -> "ru"
        Layer.SYM, Layer.SYM2 -> null
    }

    /**
     * Что отправить при нажатии: (usage, extraMods).
     * @param shifted Shift удерживается (взведён/залип) — к маске добавляется LSHIFT:
     *   буква становится заглавной, у цифры/пунктуации берётся shift-символ, usage не меняется.
     */
    fun resolveUsage(key: Key, shifted: Boolean = false): Pair<Int, Int> {
        var mods = if (key.needsShift) Mods.LSHIFT else 0
        if (shifted) mods = mods or Mods.LSHIFT
        return key.usage to mods
    }

    /**
     * Sticky-модификатор: первое нажатие взводит на одну клавишу, второе залипает,
     * третье снимает.
     */
    fun toggleModifier(state: StickyState, mask: Int): StickyState = when {
        state.isLocked(mask) -> StickyState(
            armed = state.armed and mask.inv(),
            locked = state.locked and mask.inv(),
        )
        state.isArmed(mask) -> StickyState(
            armed = state.armed and mask.inv(),
            locked = state.locked or mask,
        )
        else -> StickyState(armed = state.armed or mask, locked = state.locked)
    }

    /** После нажатия обычной клавиши взведённые модификаторы сбрасываются, залипшие остаются. */
    fun afterKeyPress(state: StickyState): StickyState = StickyState(armed = 0, locked = state.locked)

    /** Имена активных модификаторов для подсказки (латиница, как на клавишах). */
    fun modifierNames(mask: Int): String {
        val names = mutableListOf<String>()
        if (mask and Mods.LCTRL != 0) names.add("Ctrl")
        if (mask and (Mods.LSHIFT or Mods.RSHIFT) != 0) names.add("Shift")
        if (mask and (Mods.LALT or Mods.RALT) != 0) names.add("Alt")
        if (mask and (Mods.LGUI or Mods.RGUI) != 0) names.add("Win")
        return names.joinToString("+")
    }
}