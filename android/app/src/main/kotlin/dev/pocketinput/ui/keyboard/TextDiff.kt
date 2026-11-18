package dev.pocketinput.ui.keyboard

/**
 * Разница между прежним и новым содержимым поля ввода.
 *
 * Системная клавиатура (IME) правит текст как ей удобно: печатает по символу, вставляет слово
 * целиком из автокоррекции, заменяет выделенное, удаляет по одному. Нам нужно из этого потока
 * получить ровно две вещи: сколько символов стёрто (→ Backspace) и что вставлено (→ keyTap по
 * каждому символу). Функция чистая, поэтому покрывается JVM-тестом.
 */
object TextDiff {

    data class Result(val deleted: Int, val inserted: String) {
        val isEmpty: Boolean get() = deleted == 0 && inserted.isEmpty()
    }

    fun diff(old: String, new: String): Result {
        if (old == new) return Result(0, "")
        var prefix = 0
        val maxPrefix = minOf(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

        var suffix = 0
        val maxSuffix = minOf(old.length, new.length) - prefix
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val deleted = old.length - prefix - suffix
        val inserted = new.substring(prefix, new.length - suffix)
        return Result(deleted, inserted)
    }
}