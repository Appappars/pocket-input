package dev.pocketinput.ui.keyboard

/** HID-позиции для стандартных раскладок Windows US и ЙЦУКЕН. */
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

    private val russianSymbols: Map<Char, Pair<Int, Int>> = mapOf(
        '!' to (0x1E to LSHIFT), '"' to (0x1F to LSHIFT), '№' to (0x20 to LSHIFT),
        ';' to (0x21 to LSHIFT), '%' to (0x22 to LSHIFT), ':' to (0x23 to LSHIFT),
        '?' to (0x24 to LSHIFT), '*' to (0x25 to LSHIFT), '(' to (0x26 to LSHIFT),
        ')' to (0x27 to LSHIFT), '_' to (0x2D to LSHIFT), '+' to (0x2E to LSHIFT),
        ',' to (0x38 to LSHIFT), '.' to (0x38 to 0),
        '/' to (0x31 to LSHIFT), '\\' to (0x31 to 0),
    )

    private const val SYMBOLS = "?!:;.,@#№\$%&*+-=()_\"'/\\[]{}<>^~|\u0060"

    fun map(ch: Char, ruLayout: Boolean = false): Pair<Int, Int>? {
        if (ruLayout) {
            return russianSymbols[ch] ?: when (ch) {
                in '0'..'9', ' ', '-', '=' -> latin.getValue(ch) to 0
                in 'А'..'Я', 'Ё' -> cyrillic.getValue(ch.lowercaseChar()) to LSHIFT
                else -> cyrillic[ch]?.let { it to 0 }
            }
        }
        return latin[ch]?.let { it to 0 }
            ?: shifted[ch]?.let { it to LSHIFT }
            ?: if (ch in 'A'..'Z') latin.getValue(ch.lowercaseChar()) to LSHIFT else null
    }

    fun symbols(ruLayout: Boolean): List<Char> =
        SYMBOLS.filter { map(it, ruLayout) != null }.toList()
}
