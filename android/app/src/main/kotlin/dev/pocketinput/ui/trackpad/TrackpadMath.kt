package dev.pocketinput.ui.trackpad

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

    /** Коэффициент перевода dp в px с учётом чувствительности. */
    fun pxPerDp(densityDpi: Int, sensitivity: Float = 1f): Float =
        densityDpi / 160f * sensitivity

    /** Тап, если палец почти не сдвинулся. */
    fun isTap(totalShiftDp: Float, thresholdDp: Float = TAP_THRESHOLD_DP): Boolean =
        totalShiftDp < thresholdDp

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