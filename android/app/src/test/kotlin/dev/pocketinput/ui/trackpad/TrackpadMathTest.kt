package dev.pocketinput.ui.trackpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты математики трекпада. Не требуют телефона и Bluetooth:
 * проверяют ровно те числа, которые задаёт spec/05-roadmap-qa.md 5.3 п.6.
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
}