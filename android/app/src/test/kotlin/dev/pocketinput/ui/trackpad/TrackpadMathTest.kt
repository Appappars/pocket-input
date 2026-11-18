package dev.pocketinput.ui.trackpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты математики трекпада. Не требуют телефона и Bluetooth:
 * проверяют ровно те числа, которые задаёт docs/phase1-brief.md
 * («Нормативные числа фазы 1») и spec 4.6.
 */
class TrackpadMathTest {

    @Test
    fun pxPerDp_usesDensityOver160() {
        // 320 dpi -> 2.0 px на dp
        assertEquals(2.0f, TrackpadMath.pxPerDp(320), 1e-6f)
        // 420 dpi при чувствительности 1.5 -> 420/160*1.5 = 3.9375
        assertEquals(3.9375f, TrackpadMath.pxPerDp(420, 1.5f), 1e-6f)
    }

    @Test
    fun tapThreshold_isEightDp() {
        assertTrue(TrackpadMath.isTap(0f))
        assertTrue(TrackpadMath.isTap(7.99f))
        assertFalse(TrackpadMath.isTap(8f))
        assertFalse(TrackpadMath.isTap(12f))
    }

    @Test
    fun reportRate_isCappedAt120Hz() {
        // 120 Гц = 8 333 333 нс
        assertTrue(TrackpadMath.shouldSend(8_333_333L, 0L))
        assertFalse(TrackpadMath.shouldSend(8_333_332L, 0L))
        assertTrue(TrackpadMath.shouldSend(16_700_000L, 8_333_333L))
    }

    @Test
    fun wheelRate_isCappedAt60Hz() {
        assertEquals(16_666_667L, TrackpadMath.MIN_WHEEL_INTERVAL_NS)
        assertTrue(TrackpadMath.shouldSend(16_666_667L, 0L, TrackpadMath.MIN_WHEEL_INTERVAL_NS))
        assertFalse(TrackpadMath.shouldSend(16_666_666L, 0L, TrackpadMath.MIN_WHEEL_INTERVAL_NS))
    }

    @Test
    fun deltasSplitAt127_andSumIsPreserved() {
        val packets = TrackpadMath.splitDeltas(300f, 0f)
        assertEquals(listOf(127 to 0, 127 to 0, 46 to 0), packets)
        assertEquals(300, packets.sumOf { it.first })
    }

    @Test
    fun negativeDeltasSplitToo() {
        val packets = TrackpadMath.splitDeltas(-300f, 50f)
        assertEquals(-300, packets.sumOf { it.first })
        assertEquals(50, packets.sumOf { it.second })
        assertTrue(packets.all { it.first in -127..127 && it.second in -127..127 })
    }

    @Test
    fun zeroDeltaProducesNoPackets() {
        assertTrue(TrackpadMath.splitDeltas(0f, 0f).isEmpty())
        // дробный сдвиг меньше пикселя тоже не отправляется
        assertTrue(TrackpadMath.splitDeltas(0.4f, -0.6f).isEmpty())
    }

    // --- скролл двумя пальцами: 6 единиц на 1 dp, дробный остаток копится ---

    @Test
    fun scroll_givesSixUnitsPerDp() {
        val step = TrackpadMath.accumulateScroll(0f, 1f * TrackpadMath.SCROLL_UNITS_PER_DP)
        assertEquals(6, step.units)
        assertEquals(0f, step.remainder, 1e-4f)
    }

    @Test
    fun scroll_accumulatesFractionalRemainder() {
        // 0.05 dp = 0.3 единицы: первых трёх движений не хватает на целый щелчок
        var rem = 0f
        var sent = 0
        repeat(3) {
            val step = TrackpadMath.accumulateScroll(rem, 0.05f * TrackpadMath.SCROLL_UNITS_PER_DP)
            sent += step.units
            rem = step.remainder
        }
        assertEquals(0, sent)
        assertEquals(0.9f, rem, 1e-3f)

        // четвёртое движение добивает до 1.2 -> уходит 1 единица, 0.2 остаётся
        val fourth = TrackpadMath.accumulateScroll(rem, 0.05f * TrackpadMath.SCROLL_UNITS_PER_DP)
        assertEquals(1, fourth.units)
        assertEquals(0.2f, fourth.remainder, 1e-3f)
    }

    @Test
    fun scroll_keepsSignOnReversal() {
        // остаток +0.4 единицы и движение вниз на 0.4 dp (-2.4) -> -2.0: уходит -2, остаток 0
        val down = TrackpadMath.accumulateScroll(0.4f, -0.4f * TrackpadMath.SCROLL_UNITS_PER_DP)
        assertEquals(-2, down.units)
        assertEquals(0f, down.remainder, 1e-4f)

        // остаток -0.9 и ещё -1.2 -> -2.1: уходит -2, остаётся -0.1
        val neg = TrackpadMath.accumulateScroll(-0.9f, -0.2f * TrackpadMath.SCROLL_UNITS_PER_DP)
        assertEquals(-2, neg.units)
        assertEquals(-0.1f, neg.remainder, 1e-4f)
    }

    @Test
    fun scroll_gainScalesUnits() {
        val step = TrackpadMath.accumulateScroll(0f, 2f * TrackpadMath.SCROLL_UNITS_PER_DP * 1.5f)
        assertEquals(18, step.units)
        assertEquals(0f, step.remainder, 1e-4f)
    }

    // --- двойной тап: окно 350 мс ---

    @Test
    fun doubleTap_windowBoundaries() {
        assertTrue("349 мс — ещё двойной тап", TrackpadMath.isDoubleTap(1000L, 1349L, 0f))
        assertTrue("350 мс — граница включительно", TrackpadMath.isDoubleTap(1000L, 1350L, 0f))
        assertFalse("351 мс — уже одиночный", TrackpadMath.isDoubleTap(1000L, 1351L, 0f))
    }

    @Test
    fun doubleTap_rejectsMovementAndMissingPreviousTap() {
        assertFalse("нет предыдущего тапа", TrackpadMath.isDoubleTap(0L, 100L, 0f))
        assertTrue("сдвиг 7.99 dp", TrackpadMath.isDoubleTap(1000L, 1200L, 7.99f))
        assertFalse("сдвиг 8 dp — это уже drag", TrackpadMath.isDoubleTap(1000L, 1200L, 8f))
        assertFalse("время назад", TrackpadMath.isDoubleTap(1000L, 900L, 0f))
    }

    // --- долгое нажатие: 500 мс без сдвига ---

    @Test
    fun longPress_holdBoundaries() {
        assertFalse("499 мс — рано", TrackpadMath.isLongPress(499L, 0f))
        assertTrue("500 мс — срабатывает", TrackpadMath.isLongPress(500L, 0f))
        assertTrue("501 мс — тоже", TrackpadMath.isLongPress(501L, 0f))
    }

    @Test
    fun longPress_cancelledByMovement() {
        assertTrue("7.99 dp — ещё удержание", TrackpadMath.isLongPress(600L, 7.99f))
        assertFalse("8 dp — уже drag", TrackpadMath.isLongPress(600L, 8f))
        assertFalse("499 мс и без сдвига — рано", TrackpadMath.isLongPress(499L, 0f))
    }

    // --- три пальца: порог 60 dp, направления ---

    @Test
    fun threeFinger_verticalThresholdBoundaries() {
        assertEquals(TrackpadMath.ThreeFinger.NONE, TrackpadMath.classifyThreeFinger(0f, -59f))
        assertEquals(TrackpadMath.ThreeFinger.NONE, TrackpadMath.classifyThreeFinger(0f, -60f))
        assertEquals(TrackpadMath.ThreeFinger.UP, TrackpadMath.classifyThreeFinger(0f, -61f))
    }

    @Test
    fun threeFinger_horizontalThresholdBoundaries() {
        assertEquals(TrackpadMath.ThreeFinger.NONE, TrackpadMath.classifyThreeFinger(59f, 0f))
        assertEquals(TrackpadMath.ThreeFinger.NONE, TrackpadMath.classifyThreeFinger(60f, 0f))
        assertEquals(TrackpadMath.ThreeFinger.RIGHT, TrackpadMath.classifyThreeFinger(61f, 0f))
        assertEquals(TrackpadMath.ThreeFinger.LEFT, TrackpadMath.classifyThreeFinger(-61f, 0f))
    }

    @Test
    fun threeFinger_allDirections() {
        // вверх -> рабочий стол (LGUI + D), вправо/влево -> Alt+Tab
        assertEquals(TrackpadMath.ThreeFinger.UP, TrackpadMath.classifyThreeFinger(10f, -120f))
        assertEquals(TrackpadMath.ThreeFinger.RIGHT, TrackpadMath.classifyThreeFinger(120f, -10f))
        assertEquals(TrackpadMath.ThreeFinger.LEFT, TrackpadMath.classifyThreeFinger(-120f, 10f))
        // вниз действием не задан
        assertEquals(TrackpadMath.ThreeFinger.NONE, TrackpadMath.classifyThreeFinger(0f, 120f))
        // доминирующая ось решает: 70 dp вправо против 65 dp вверх = вправо
        assertEquals(TrackpadMath.ThreeFinger.RIGHT, TrackpadMath.classifyThreeFinger(70f, -65f))
    }

    @Test
    fun distanceIsEuclidean() {
        assertEquals(5.0f, TrackpadMath.distanceDp(3f, 4f), 1e-5f)
        assertEquals(0f, TrackpadMath.distanceDp(0f, 0f), 1e-5f)
    }
}