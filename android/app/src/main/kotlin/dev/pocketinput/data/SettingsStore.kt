package dev.pocketinput.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Состояние настроек фазы 1 (docs/phase1-brief.md, контракт неизменен).
 *
 * Диапазоны из spec 4.8: чувствительность 0.2..4.0, скролл-гейн 0.2..3.0.
 * Значения клампятся при загрузке — невалидное значение не применяется (spec 4.8).
 */
data class SettingsState(
    val sensitivity: Float = 1.0f,
    val scrollGain: Float = 1.0f,
    val vibration: Boolean = true,
    val theme: String = THEME_SYSTEM,
    val layout: String = LAYOUT_EN,
    /** Инверсия направления скролла; по умолчанию инвертирован (решение владельца продукта, 18.11). */
    val invertScroll: Boolean = true,
) {
    companion object {
        const val SENSITIVITY_MIN = 0.2f
        const val SENSITIVITY_MAX = 4.0f
        const val SCROLL_MIN = 0.2f
        const val SCROLL_MAX = 3.0f

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        const val LAYOUT_EN = "en"
        const val LAYOUT_RU = "ru"

        val THEMES = listOf(THEME_SYSTEM, THEME_DARK, THEME_LIGHT)
        val LAYOUTS = listOf(LAYOUT_EN, LAYOUT_RU)

        /** Кламп в допустимый диапазон; NaN/Infinity сводятся к дефолту 1.0. */
        fun clampSensitivity(value: Float): Float =
            if (value.isNaN() || value.isInfinite()) 1.0f
            else value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

        fun clampScrollGain(value: Float): Float =
            if (value.isNaN() || value.isInfinite()) 1.0f
            else value.coerceIn(SCROLL_MIN, SCROLL_MAX)

        fun normalizeTheme(value: String?): String =
            if (value in THEMES) value!! else THEME_SYSTEM

        fun normalizeLayout(value: String?): String =
            if (value in LAYOUTS) value!! else LAYOUT_EN
    }
}

/**
 * Локальное хранилище настроек на SharedPreferences (новых зависимостей нет).
 * Изменение применяется сразу: экран вызывает [save] на каждое изменение.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Читает настройки; отсутствующие ключи — дефолты, вышедшие из диапазона — кламп. */
    fun load(): SettingsState = SettingsState(
        sensitivity = SettingsState.clampSensitivity(
            prefs.getFloat(KEY_SENSITIVITY, SettingsState().sensitivity),
        ),
        scrollGain = SettingsState.clampScrollGain(
            prefs.getFloat(KEY_SCROLL_GAIN, SettingsState().scrollGain),
        ),
        vibration = prefs.getBoolean(KEY_VIBRATION, SettingsState().vibration),
        theme = SettingsState.normalizeTheme(prefs.getString(KEY_THEME, null)),
        layout = SettingsState.normalizeLayout(prefs.getString(KEY_LAYOUT, null)),
        invertScroll = prefs.getBoolean(KEY_INVERT_SCROLL, SettingsState().invertScroll),
    )

    /** Сохраняет настройки; значения клампятся, чтобы битое состояние не попало на диск. */
    fun save(state: SettingsState) {
        prefs.edit()
            .putFloat(KEY_SENSITIVITY, SettingsState.clampSensitivity(state.sensitivity))
            .putFloat(KEY_SCROLL_GAIN, SettingsState.clampScrollGain(state.scrollGain))
            .putBoolean(KEY_VIBRATION, state.vibration)
            .putString(KEY_THEME, SettingsState.normalizeTheme(state.theme))
            .putString(KEY_LAYOUT, SettingsState.normalizeLayout(state.layout))
            .putBoolean(KEY_INVERT_SCROLL, state.invertScroll)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "pocket_input_settings"
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_SCROLL_GAIN = "scroll_gain"
        const val KEY_VIBRATION = "vibration"
        const val KEY_THEME = "theme"
        const val KEY_LAYOUT = "layout"
        const val KEY_INVERT_SCROLL = "invert_scroll"
    }
}