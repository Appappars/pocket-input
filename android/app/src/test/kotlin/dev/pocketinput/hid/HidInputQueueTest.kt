package dev.pocketinput.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit

class HidInputQueueTest {
    @Test
    fun neutralReportsPrecedeNewInput() {
        val rig = Rig()
        rig.open(1)
        rig.queue.pressButton(1)
        rig.queue.keyTap(0x04)
        rig.clock.advance(0)

        assertEquals(listOf(mouse(0), key(0), mouse(1), key(0, 0x04)), rig.reports)
    }

    @Test
    fun fastPressAndReleaseKeepBothEdges() {
        val rig = Rig().connected()
        rig.queue.pressButton(1)
        rig.queue.releaseButton(1)
        rig.clock.advance(0)

        assertEquals(listOf(mouse(1), mouse(0)), rig.reports)
    }

    @Test
    fun consecutiveClicksKeepBothPressesAndReleases() {
        val rig = Rig().connected()
        rig.queue.tap()
        rig.queue.tap()
        rig.clock.advance(39)
        assertEquals(listOf(mouse(1)), rig.reports)

        rig.clock.advance(41)
        assertEquals(listOf(mouse(1), mouse(0), mouse(1), mouse(0)), rig.reports)
    }

    @Test
    fun rightClickAndProbePreserveHeldLeftButton() {
        val rig = Rig().connected()
        val probes = mutableListOf<Boolean>()
        rig.queue.pressButton(1)
        rig.queue.tap(button = 2)
        rig.queue.probe { probes += it }
        rig.clock.advance(39)
        assertEquals(listOf(mouse(1), mouse(3)), rig.reports)
        assertTrue(probes.isEmpty())
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), rig.queue.currentReport(MOUSE))

        rig.clock.advance(1)
        assertEquals(listOf(mouse(1), mouse(3), mouse(1), mouse(1)), rig.reports)
        assertEquals(listOf(true), probes)
        assertEquals(1, rig.queue.currentButtons)
    }

    @Test
    fun mouseMovesWhileKeyIsHeldAndTemporaryModifiersAreReleased() {
        val rig = Rig().connected()
        rig.queue.keyTap(0x04, mods = 2)
        rig.clock.advance(0)
        rig.clock.advance(5)
        rig.queue.sendMotion(7, -9)
        rig.clock.advance(0)

        assertEquals(listOf(key(2, 0x04), mouse(0, 7, -9)), rig.reports)
        assertEquals(listOf(0L, 5L), rig.sendTimes)
        assertArrayEquals(byteArrayOf(2, 0, 4, 0, 0, 0, 0, 0), rig.queue.currentReport(KEYBOARD))

        rig.clock.advance(15)
        assertEquals(key(0), rig.reports.last())
        assertEquals(20L, rig.sendTimes.last())
        assertArrayEquals(ByteArray(8), rig.queue.currentReport(KEYBOARD))
    }

    @Test
    fun earlierTextFinishesBeforeMouseClickCanChangeFocus() {
        val rig = Rig().connected()
        assertTrue(rig.queue.keyTaps(listOf(0x04 to 0, 0x05 to 0), expectedSessionId = 1))
        rig.queue.tap()
        rig.clock.advance(39)
        assertEquals(listOf(key(0, 0x04), key(0), key(0, 0x05)), rig.reports)

        rig.clock.advance(41)
        assertEquals(listOf(key(0, 0x04), key(0), key(0, 0x05), key(0), mouse(1), mouse(0)), rig.reports)
    }

    @Test
    fun laterTextWaitsForMouseClickToFinish() {
        val rig = Rig().connected()
        rig.queue.tap()
        assertTrue(rig.queue.keyTaps(listOf(0x04 to 0, 0x05 to 0), expectedSessionId = 1))
        rig.clock.advance(39)
        assertEquals(listOf(mouse(1)), rig.reports)

        rig.clock.advance(41)
        assertEquals(listOf(mouse(1), mouse(0), key(0, 0x04), key(0), key(0, 0x05), key(0)), rig.reports)
    }

    @Test
    fun releaseAllButtonsDoesNotWaitForKeyboard() {
        val rig = Rig().connected()
        rig.queue.pressButton(1)
        rig.queue.keyTap(0x04, mods = 1)
        rig.clock.advance(0)
        rig.queue.releaseAllButtons()
        rig.clock.advance(0)

        assertEquals(listOf(mouse(1), key(1, 0x04), mouse(0)), rig.reports)
    }

    @Test
    fun cancellationDropsADeferredDragBeforeTheKeyboardBatchFinishes() {
        val rig = Rig().connected()
        assertTrue(rig.queue.keyTaps(listOf(0x04 to 0, 0x05 to 0), expectedSessionId = 1))
        rig.clock.advance(0)
        rig.queue.pressButton(1)
        rig.queue.sendMotion(7, 5)
        rig.queue.cancelMouseInput(keepMask = 0)
        rig.clock.advance(100)

        assertEquals(listOf(mouse(0)), rig.reports.filter { it.id == MOUSE })
        assertEquals(listOf(key(0, 0x04), key(0), key(0, 0x05), key(0)), rig.reports.filter { it.id == KEYBOARD })
    }

    @Test
    fun cancellationKeepsTheExplicitMouseButtonMask() {
        val rig = Rig().connected()
        rig.queue.pressButton(1)
        rig.clock.advance(0)
        rig.queue.tap(button = 2)
        rig.clock.advance(0)
        rig.queue.sendMotion(5, 0)
        rig.queue.cancelMouseInput(keepMask = 1)
        rig.clock.advance(100)

        assertEquals(listOf(mouse(1), mouse(3), mouse(1)), rig.reports)
        assertEquals(1, rig.queue.currentButtons)
    }

    @Test
    fun mouseDeltasAreSplitAtHidLimitWithoutCrossingButtonEdges() {
        val rig = Rig().connected()
        rig.queue.sendMotion(80, -110)
        rig.queue.sendMotion(110, -40)
        rig.queue.pressButton(1)
        rig.queue.sendMotion(8, 0)
        rig.queue.wheel(-260)
        rig.queue.wheel(-10)
        rig.queue.releaseButton(1)
        rig.clock.advance(100)

        assertEquals(
            listOf(
                mouse(0, 127, -127), mouse(0, 63, -23), mouse(1), mouse(1, 8),
                mouse(1, wheel = -127), mouse(1, wheel = -127), mouse(1, wheel = -16), mouse(0),
            ),
            rig.reports,
        )
    }

    @Test
    fun disconnectCancelsOldTimersAndOnlyNewInputFollowsNeutralReports() {
        val rig = Rig().connected()
        val probes = mutableListOf<Boolean>()
        rig.queue.pressButton(1)
        rig.queue.keyTap(0x04, mods = 1)
        rig.clock.advance(0)
        rig.queue.tap(button = 2)
        rig.queue.sendMotion(16, 0)
        rig.queue.probe { probes += it }
        rig.queue.disconnect()
        rig.open(2)
        assertFalse(rig.queue.keyTap(0x05, expectedSessionId = 1))
        assertTrue(rig.queue.keyTap(0x06, expectedSessionId = 2))
        rig.clock.advance(100)

        assertEquals(listOf(mouse(1), key(1, 0x04), mouse(0), key(0), key(0, 0x06), key(0)), rig.reports)
        assertEquals(listOf(1L, 1L, 2L, 2L, 2L, 2L), rig.results.map { it.sessionId })
        assertEquals(listOf(false), probes)
    }

    @Test
    fun resetClearsQueuedInputAndActiveHoldsInBothLanes() {
        val rig = Rig().connected()
        val before = rig.queue.keyboardRevision
        rig.queue.pressButton(1)
        rig.queue.keyTap(0x04, mods = 1)
        rig.clock.advance(0)
        rig.queue.keyTap(0x05)
        rig.queue.resetInput()
        assertEquals(before + 1, rig.queue.keyboardRevision)
        assertFalse(rig.queue.keyTaps(listOf(0x2A to 0), 1, before))
        rig.queue.sendMotion(1, 2)
        rig.clock.advance(100)

        assertEquals(listOf(mouse(1), key(1, 0x04), mouse(0), key(0), mouse(0, 1, 2)), rig.reports)
        assertEquals(0, rig.queue.currentButtons)
    }

    @Test
    fun keyBatchIsRejectedTogetherWhenCapacityIsInsufficient() {
        val rig = Rig().connected()
        assertTrue(rig.queue.keyTaps(List(127) { 0x04 to 0 }, expectedSessionId = 1))
        assertFalse(rig.queue.keyTaps(listOf(0x05 to 0, 0x06 to 0), expectedSessionId = 1))
        assertTrue(rig.queue.keyTap(0x07, expectedSessionId = 1))
        assertFalse(rig.queue.keyTap(0x08, expectedSessionId = 1))
        rig.clock.advance(3000)

        assertEquals(256, rig.reports.size)
        assertEquals(listOf(key(0, 0x07), key(0)), rig.reports.takeLast(2))
        assertFalse(key(0, 0x05) in rig.reports)
        assertFalse(key(0, 0x06) in rig.reports)
    }

    @Test
    fun explicitReleaseKeepsTextThatWasAlreadyAccepted() {
        val rig = Rig().connected()
        assertTrue(rig.queue.keyTaps(List(128) { 0x04 to 0 }, expectedSessionId = 1))
        assertTrue(rig.queue.keyUp(expectedSessionId = 1))
        assertTrue(rig.queue.keyUp(expectedSessionId = 1))
        rig.clock.advance(3000)

        assertEquals(128, rig.reports.count { it == key(0, 0x04) })
        assertEquals(257, rig.reports.size)
        assertEquals(key(0), rig.reports.last())
    }

    @Test
    fun mouseReleaseFailureRetriesWithoutInvalidatingKeyboard() {
        val rig = Rig().connected()
        val before = rig.queue.keyboardRevision
        rig.queue.tap()
        rig.clock.advance(0)
        rig.failuresRemaining = 1
        rig.clock.advance(40)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), rig.queue.currentReport(MOUSE))

        rig.clock.advance(100)
        assertEquals(listOf(mouse(1), mouse(0), mouse(0)), rig.reports)
        assertEquals(listOf(true, false, true), rig.results.map { it.ok })
        assertEquals(before, rig.queue.keyboardRevision)
        assertArrayEquals(ByteArray(4), rig.queue.currentReport(MOUSE))
    }

    @Test
    fun failedCharacterDropsDependentBackspaceAndRejectsStaleEdits() {
        val rig = Rig().connected()
        val before = rig.queue.keyboardRevision
        assertTrue(rig.queue.keyTaps(listOf(0x06 to 0), 1, before))
        assertTrue(rig.queue.keyTaps(listOf(0x2A to 0, 0x1B to 0), 1, before))
        rig.failuresRemaining = 1
        rig.clock.advance(100)

        assertEquals(listOf(key(0, 0x06), key(0)), rig.reports)
        assertEquals(before + 1, rig.queue.keyboardRevision)
        assertFalse(rig.queue.keyTaps(listOf(0x2A to 0, 0x1B to 0), 1, before))
        assertTrue(rig.queue.keyTaps(listOf(0x1B to 0), 1, rig.queue.keyboardRevision))
        rig.clock.advance(20)
        assertEquals(listOf(key(0, 0x1B), key(0)), rig.reports.takeLast(2))
    }

    @Test
    fun keyboardReleaseFailureRestartsWithAtMostTwoNeutralAttempts() {
        val rig = Rig().connected()
        val before = rig.queue.keyboardRevision
        rig.queue.keyTap(0x04, mods = 1)
        rig.clock.advance(0)
        rig.failuresRemaining = 3
        assertTrue(rig.queue.keyTaps(listOf(0x2A to 0), 1, before))
        rig.clock.advance(200)

        assertEquals(listOf(key(1, 0x04), key(0), key(0), key(0)), rig.reports)
        assertEquals(listOf(true, false, false, false), rig.results.map { it.ok })
        assertEquals(before + 1, rig.queue.keyboardRevision)
        assertArrayEquals(ByteArray(8), rig.queue.currentReport(KEYBOARD))
    }

    @Test
    fun probeReportsTheResultOfItsOwnSendAndShutdownRejectsNewSessions() {
        val rig = Rig().connected()
        val probes = mutableListOf<Boolean>()
        rig.failuresRemaining = 1
        rig.queue.probe { probes += it }
        rig.clock.advance(0)
        assertEquals(listOf(false), probes)
        assertEquals(listOf(Result(1, 0f, false)), rig.results)

        rig.queue.probe { probes += it }
        rig.queue.close()
        rig.open(2)
        assertFalse(rig.queue.keyTap(0x04))
        rig.clock.advance(100)
        assertEquals(listOf(false, false), probes)
        assertEquals(listOf(mouse(0)), rig.reports)
    }

    private class Rig {
        val clock = TestScheduler()
        val reports = mutableListOf<Report>()
        val results = mutableListOf<Result>()
        val sendTimes = mutableListOf<Long>()
        val queue = HidInputQueue(
            onSendResult = { sessionId, waitedMs, ok -> results += Result(sessionId, waitedMs, ok) },
            scheduler = clock,
        )
        var failuresRemaining = 0

        fun open(id: Long) {
            queue.open(id) { reportId, packet ->
                reports += Report(reportId, packet.map(Byte::toInt))
                sendTimes += TimeUnit.NANOSECONDS.toMillis(clock.nowNanos())
                if (failuresRemaining > 0) {
                    failuresRemaining--
                    false
                } else {
                    true
                }
            }
        }

        fun connected(): Rig = apply {
            open(1)
            clock.advance(0)
            reports.clear()
            results.clear()
            sendTimes.clear()
        }
    }

    private class TestScheduler : HidQueueScheduler {
        private data class Task(val at: Long, val order: Long, val action: () -> Unit)

        private val tasks = PriorityQueue(compareBy(Task::at, Task::order))
        private var time = 0L
        private var nextOrder = 0L

        override fun nowNanos(): Long = time

        override fun schedule(delayNanos: Long, action: () -> Unit): ScheduledHidTask {
            tasks.add(Task(time + delayNanos, nextOrder++, action))
            // Model a callback already dispatched at the moment its timer is cancelled.
            return ScheduledHidTask {}
        }

        override fun close() = Unit

        fun advance(ms: Long) {
            val until = time + TimeUnit.MILLISECONDS.toNanos(ms)
            while (tasks.peek()?.let { it.at <= until } == true) {
                val task = tasks.remove()
                time = task.at
                task.action()
            }
            time = until
        }
    }

    private data class Report(val id: Int, val bytes: List<Int>)
    private data class Result(val sessionId: Long, val waitedMs: Float, val ok: Boolean)

    private companion object {
        const val KEYBOARD = 1
        const val MOUSE = 2

        fun mouse(buttons: Int, x: Int = 0, y: Int = 0, wheel: Int = 0) =
            Report(MOUSE, listOf(buttons, x, y, wheel))

        fun key(modifiers: Int, usage: Int = 0) =
            Report(KEYBOARD, listOf(modifiers, 0, usage, 0, 0, 0, 0, 0))
    }
}
