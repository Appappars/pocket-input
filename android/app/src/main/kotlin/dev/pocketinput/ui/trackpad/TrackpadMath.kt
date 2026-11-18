package dev.pocketinput.ui.trackpad

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Чистая математика трекпада — без Android-зависимостей, чтобы тестировать на JVM.
 *
 * Источник значений: spec/05-roadmap-qa.md 5.3 п.6 (zero-decision rule):
 *  - dp -> px: dx_px = dx_dp * densityDpi / 160 * sensitivity;
 *  - порог тапа: суммарный сдвиг < 8 dp; drag — >= 8 dp;
 *  - частота репортов не выше 120 Гц (интервал 8 333 333 нс);
 *  - ось мыши int8: дельта больше 127 px режется до 127, остаток досылается следующим пакетом.
 */
object TrackpadMath {

    const val TAP_THRESHOLD_DP = 8f
    const val MIN_REPORT_INTERVAL_NS = 8_333_333L
    const val MAX_DELTA_PX = 127

    /** Скролл отправляется не чаще 60 Гц (docs/phase1-brief.md, «Скролл двумя пальцами»). */
    const val MIN_WHEEL_INTERVAL_NS = 16_666_667L

    /** Одно деление HID-колеса на 20 dp движения пальца. */
    const val SCROLL_DP_PER_DETENT = 20f

    /**
     * Окно второго касания (мс). По умолчанию 500: 350 оказалось мало — второе касание
     * для перетаскивания не успевало попасть в окно (замечание владельца, 18.11).
     * Значение настраивается (SettingsState.doubleTapWindowMs).
     */
    const val DOUBLE_TAP_MS = 500L

    /** Удержание для правой кнопки, мс. */
    const val LONG_PRESS_MS = 500L

    /**
     * Второй тап, который держат дольше этого времени, — не двойной клик, а начало
     * перетаскивания (как «tap twice and drag» на тачпаде ноутбука).
     */
    const val TAP_AND_HOLD_MS = 150L

    /** Порог свайпа тремя пальцами, dp. */
    const val THREE_FINGER_DP = 60f

    /** Направление свайпа тремя пальцами. Свайп вниз действием не задан — NONE. */
    enum class ThreeFinger { NONE, UP, LEFT, RIGHT }

    /** Сколько целых единиц колеса отдать и какой дробный остаток оставить. */
    data class ScrollStep(val units: Int, val remainder: Float)

    /** Коэффициент перевода dp в px с учётом чувствительности. */
    fun pxPerDp(densityDpi: Int, sensitivity: Float = 1f): Float =
        densityDpi / 160f * sensitivity

    /** Тап, если палец почти не сдвинулся. */
    fun isTap(totalShiftDp: Float, thresholdDp: Float = TAP_THRESHOLD_DP): Boolean =
        totalShiftDp < thresholdDp

    /** Длина сдвига в dp по двум осям. */
    fun distanceDp(dxDp: Float, dyDp: Float): Float = sqrt(dxDp * dxDp + dyDp * dyDp)

    /**
     * Копит дробную часть скролла: [remainder] — остаток с прошлого раза, [unitsDelta] —
     * приращение в делениях колеса. Целые уходят в отчёт,
     * дробь остаётся, поэтому медленное движение тоже когда-нибудь прокрутит.
     * Усечение к нулю: знак остатка совпадает со знаком движения.
     */
    fun accumulateScroll(remainder: Float, unitsDelta: Float): ScrollStep {
        val total = remainder + unitsDelta
        val units = total.toInt()
        return ScrollStep(units, total - units)
    }

    /**
     * Единицы колеса для вертикального движения двух пальцев.
     * [invert] меняет направление (настройка «инверсия скролла», по умолчанию включена).
     */
    fun scrollUnits(dyDp: Float, gain: Float = 1f, invert: Boolean = false): Float {
        val units = dyDp / SCROLL_DP_PER_DETENT * gain
        return if (invert) units else -units
    }

    /**
     * Второе касание — двойной тап, если уложилось в [windowMs] и почти без сдвига.
     * [tPrevMs] <= 0 означает «предыдущего тапа нет».
     */
    fun isDoubleTap(
        tPrevMs: Long,
        tNowMs: Long,
        movementDp: Float,
        windowMs: Long = DOUBLE_TAP_MS,
        thresholdDp: Float = TAP_THRESHOLD_DP,
    ): Boolean {
        if (tPrevMs <= 0L) return false
        val gap = tNowMs - tPrevMs
        return gap in 0..windowMs && movementDp < thresholdDp
    }

    /** Удержание без сдвига не меньше [holdThresholdMs] — правая кнопка. */
    fun isLongPress(
        holdMs: Long,
        movementDp: Float,
        holdThresholdMs: Long = LONG_PRESS_MS,
        thresholdDp: Float = TAP_THRESHOLD_DP,
    ): Boolean = holdMs >= holdThresholdMs && movementDp < thresholdDp

    /**
     * Классификация свайпа тремя пальцами: решает доминирующая ось, сдвиг строго больше
     * порога (60 dp — ещё NONE, 61 dp — жест). dx > 0 — вправо, dy < 0 — вверх.
     */
    fun classifyThreeFinger(
        dxDp: Float,
        dyDp: Float,
        thresholdDp: Float = THREE_FINGER_DP,
    ): ThreeFinger {
        val absX = abs(dxDp)
        val absY = abs(dyDp)
        return if (absX >= absY) {
            when {
                absX <= thresholdDp -> ThreeFinger.NONE
                dxDp > 0f -> ThreeFinger.RIGHT
                else -> ThreeFinger.LEFT
            }
        } else {
            when {
                absY <= thresholdDp -> ThreeFinger.NONE
                dyDp < 0f -> ThreeFinger.UP
                else -> ThreeFinger.NONE
            }
        }
    }

    /** Пора ли отправлять репорт: не чаще 120 Гц. */
    fun shouldSend(
        nowNs: Long,
        lastReportNs: Long,
        minIntervalNs: Long = MIN_REPORT_INTERVAL_NS,
    ): Boolean = nowNs - lastReportNs >= minIntervalNs

    /**
     * Режет дельту на пакеты так, чтобы каждая компонента помещалась в int8.
     * Возвращает список (dx, dy); сумма компонент равна исходной дельте.
     */
    fun splitDeltas(dxPx: Float, dyPx: Float, maxDelta: Int = MAX_DELTA_PX): List<Pair<Int, Int>> {
        var remX = dxPx.toInt()
        var remY = dyPx.toInt()
        val out = ArrayList<Pair<Int, Int>>(2)
        var guard = 0
        while ((remX != 0 || remY != 0) && guard < 64) {
            val stepX = remX.coerceIn(-maxDelta, maxDelta)
            val stepY = remY.coerceIn(-maxDelta, maxDelta)
            out.add(stepX to stepY)
            remX -= stepX
            remY -= stepY
            guard++
        }
        return out
    }
}
