package dev.pocketinput.ui.keyboard

/**
 * Преобразование символа в HID usage-код + модификаторы.
 *
 * Зачем: с 0.2.1 текст набирается системной клавиатурой телефона (пользовательский выбор),
 * а на ПК уходит позиция клавиши. Для латиницы это прямая раскладка US, для кириллицы —
 * позиции клавиш стандартной русской раскладки Windows (ЙЦУКЕН на физической US-клавиатуре):
 * так русские буквы появятся на ПК, если там включена русская раскладка, и латинские — если нет.
 */
object CharMap {

    // Маски модификаторов HID (Usage Page 0x07, modifier byte).
    const val LCTRL = 0x01
    const val LSHIFT = 0x02
    const val LALT = 0x04
    const val LGUI = 0x08

    /** usage-коды базовых клавиш латинского ряда. */
    private val latin: Map<Char, Int> = buildMap {
        for (i in 0..25) put('a' + i, 0x04 + i)
        for (i in 0..8) put('1' + i, 0x1E + i)
        put('0', 0x27)
        put(' ', 0x2C)
        put('-', 0x2D); put('=', 0x2E); put('[', 0x2F); put(']', 0x30); put('\\', 0x31)
        put(';', 0x33); put('\'', 0x34); put('`', 0x35)
        put(',', 0x36); put('.', 0x37); put('/', 0x38)
    }

    /** Символы, которым нужен Shift, и их базовые клавиши. */
    private val shifted: Map<Char, Int> = mapOf(
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22, '^' to 0x23,
        '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
    )

    /** Кириллица → позиция клавиши в раскладке ЙЦУКЕН на физической US-клавиатуре. */
    private val cyrillic: Map<Char, Int> = mapOf(
        // верхний ряд: ЙЦУКЕНГШЩЗХЪ
        'й' to 0x14, 'ц' to 0x1A, 'у' to 0x08, 'к' to 0x15, 'е' to 0x17, 'н' to 0x1C,
        'г' to 0x18, 'ш' to 0x0C, 'щ' to 0x12, 'з' to 0x13, 'х' to 0x2F, 'ъ' to 0x30,
        // домашний ряд: ФЫВАПРОЛДЖЭ
        'ф' to 0x04, 'ы' to 0x16, 'в' to 0x07, 'а' to 0x09, 'п' to 0x0A, 'р' to 0x0B,
        'о' to 0x0D, 'л' to 0x0E, 'д' to 0x0F, 'ж' to 0x33, 'э' to 0x34,
        // нижний ряд: ЯЧСМИТЬБЮЁ
        'я' to 0x1D, 'ч' to 0x1B, 'с' to 0x06, 'м' to 0x19, 'и' to 0x05, 'т' to 0x11,
        'ь' to 0x10, 'б' to 0x36, 'ю' to 0x37, 'ё' to 0x35,
    )

    /** Результат: usage-код и дополнительные модификаторы (0, если не нужны). */
    fun map(ch: Char): Pair<Int, Int>? {
        latin[ch]?.let { return it to 0 }
        shifted[ch]?.let { return it to LSHIFT }
        cyrillic[ch]?.let { return it to 0 }
        val lower = ch.lowercaseChar()
        if (lower != ch) {
            if (latin.containsKey(lower)) return latin.getValue(lower) to LSHIFT
            if (cyrillic.containsKey(lower)) return cyrillic.getValue(lower) to LSHIFT
        }
        return null // эмодзи, иероглифы и прочее в HID-ветке не выражаются (фаза 2: агент)
    }

    /** Сколько символов строки вообще выразимы в HID-ветке (для предупреждения в UI). */
    fun unsupportedCount(text: String): Int = text.count { map(it) == null }
}