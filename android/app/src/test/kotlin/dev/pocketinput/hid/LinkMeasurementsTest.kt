package dev.pocketinput.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkMeasurementsTest {
    @Test
    fun countsEveryLocalRejection() {
        val measurements = LinkMeasurements()
        repeat(3) { measurements.record(0f, false) }

        val snapshot = measurements.update(LinkSnapshot())
        assertEquals(3, snapshot.sampleCount)
        assertEquals(3, snapshot.recentFailures)
        assertEquals(0L, snapshot.sentOk)
        assertEquals(3L, snapshot.sendFailed)
    }

    @Test
    fun latestReportsDetermineQueueDelayAndRecentFailures() {
        val measurements = LinkMeasurements()
        measurements.record(500f, false)
        repeat(LinkMeasurements.WINDOW) { measurements.record((it + 1).toFloat(), true) }

        val snapshot = measurements.update(LinkSnapshot())
        assertEquals(LinkMeasurements.WINDOW, snapshot.sampleCount)
        assertEquals(0, snapshot.recentFailures)
        assertEquals(1L, snapshot.sendFailed)
        assertEquals(61f, snapshot.queueP95Ms, 0f)
    }

    @Test
    fun nextSessionStartsWithNoMeasurements() {
        val measurements = LinkMeasurements()
        measurements.record(500f, false)
        measurements.reset()

        val nextSession = LinkSnapshot(state = LinkState.CONNECTED, sessionId = 2)
        assertEquals(nextSession, measurements.update(nextSession))
    }

    @Test
    fun recoveryAttemptsAreBoundedUntilReset() {
        val backoff = LinkBackoff()

        assertEquals(listOf(750L, 2_000L, 5_000L), List(3) { backoff.takeDelayMs() })
        assertEquals(0, backoff.remaining)
        assertNull(backoff.takeDelayMs())

        backoff.reset()
        assertEquals(3, backoff.remaining)
        assertEquals(750L, backoff.takeDelayMs())
    }
}
