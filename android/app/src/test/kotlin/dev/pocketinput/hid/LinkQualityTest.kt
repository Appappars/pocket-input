package dev.pocketinput.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Оценка качества канала — числа из docs/… и LinkQuality: штраф за задержку очереди
 * (до 45), за долю отказов (до 30) и за разрывы (по 7, не больше 20).
 */
class LinkQualityTest {

    @Test
    fun perfectChannel_isHundred() {
        assertEquals(100, LinkQuality.score(p50Ms = 0f, failedRatio = 0f, disconnects = 0))
        assertEquals(100, LinkQuality.score(p50Ms = LinkQuality.LATENCY_FREE_MS, failedRatio = 0f, disconnects = 0))
    }

    @Test
    fun latencyUpToFreeThreshold_doesNotPenalize() {
        assertEquals(100, LinkQuality.score(p50Ms = 6f, failedRatio = 0f, disconnects = 0))
        // 8 мс — граница бесплатной зоны
        assertEquals(100, LinkQuality.score(p50Ms = 8f, failedRatio = 0f, disconnects = 0))
    }

    @Test
    fun badLatency_takesFullPenalty() {
        // 40 мс и выше — максимальный штраф 45 баллов
        assertEquals(55, LinkQuality.score(p50Ms = 40f, failedRatio = 0f, disconnects = 0))
        assertEquals(55, LinkQuality.score(p50Ms = 200f, failedRatio = 0f, disconnects = 0))
    }

    @Test
    fun halfWayLatency_takesHalfPenalty() {
        // (24 − 8) / (40 − 8) = 0.5 → 22.5 балла → 77 после округления вниз
        assertEquals(77, LinkQuality.score(p50Ms = 24f, failedRatio = 0f, disconnects = 0))
    }

    @Test
    fun sendFailures_penalizeUpToThirty() {
        assertEquals(70, LinkQuality.score(p50Ms = 0f, failedRatio = 1f, disconnects = 0))
        assertEquals(85, LinkQuality.score(p50Ms = 0f, failedRatio = 0.5f, disconnects = 0))
    }

    @Test
    fun disconnects_penalizeSevenEachCappedAtTwenty() {
        assertEquals(93, LinkQuality.score(p50Ms = 0f, failedRatio = 0f, disconnects = 1))
        assertEquals(80, LinkQuality.score(p50Ms = 0f, failedRatio = 0f, disconnects = 10))
    }

    @Test
    fun levelThresholds() {
        assertEquals(Quality.UNKNOWN, LinkQuality.level(-1))
        assertEquals(Quality.GOOD, LinkQuality.level(85))
        assertEquals(Quality.MEDIUM, LinkQuality.level(84))
        assertEquals(Quality.MEDIUM, LinkQuality.level(55))
        assertEquals(Quality.POOR, LinkQuality.level(54))
    }

    @Test
    fun percentile95_usesNearestRank() {
        assertEquals(0f, LinkQuality.percentile95(emptyList()), 1e-6f)
        // индекс = floor((n − 1) · 0.95): для 5 значений это индекс 3 → элемент «4»
        assertEquals(4f, LinkQuality.percentile95(listOf(1f, 2f, 3f, 4f, 5f)), 1e-6f)
        // 100 значений: floor(99 · 0.95) = 94 → 95-й элемент
        val hundred = (1..100).map { it.toFloat() }
        assertEquals(95f, LinkQuality.percentile95(hundred), 1e-6f)
    }
}