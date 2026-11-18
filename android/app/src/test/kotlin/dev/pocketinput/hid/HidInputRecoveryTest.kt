package dev.pocketinput.hid

import dev.pocketinput.hid.HidInputQueueTest.Companion.key
import dev.pocketinput.hid.HidInputQueueTest.Companion.mouse
import dev.pocketinput.hid.HidInputQueueTest.Rig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HidInputRecoveryTest {
    @Test
    fun ordinaryScrollWaitsForSuccessfulCtrlRecovery() {
        val rig = Rig().connected()
        var releasesToFail = 2
        rig.onTransmit = { it != key(0) || releasesToFail-- <= 0 }
        rig.queue.zoom(1)
        rig.queue.scroll(0, 9)
        rig.clock.advance(9)
        assertEquals(listOf(key(1), mouse(0, wheel = 1), key(0), key(0)), rig.reports)

        rig.clock.advance(1)
        assertEquals(listOf(key(0), mouse(0, wheel = 9)), rig.reports.takeLast(2))
        assertEquals(listOf(true, true, false, false, true, true), rig.results.map { it.ok })
    }

    @Test
    fun mouseTimerAssignedBeforeRecoveryAlsoWaits() {
        val rig = Rig().connected()
        var releasesToFail = 2
        rig.onTransmit = { it != key(0) || releasesToFail-- <= 0 }
        rig.queue.keyTap(0x04)
        rig.clock.advance(19)
        rig.queue.sendMotion(1, 0)
        rig.clock.advance(0)
        rig.queue.scroll(0, 9)
        rig.clock.advance(10)
        assertEquals(listOf(key(0, 0x04), mouse(0, 1), key(0), key(0)), rig.reports)

        rig.clock.advance(1)
        assertEquals(listOf(key(0), mouse(0, wheel = 9)), rig.reports.takeLast(2))
    }

    @Test
    fun exhaustedRecoveryDropsHeldInputAndStaysPausedUntilReset() {
        val rig = Rig().connected()
        rig.onTransmit = { it != key(0) }
        rig.queue.pressButton(1)
        rig.queue.zoom(1)
        rig.queue.scroll(0, 9)
        rig.clock.advance(100)
        val expected = listOf(mouse(1), key(1), mouse(1, wheel = 1), key(0), key(0), key(0), mouse(0))
        assertEquals(expected, rig.reports)
        assertFalse(rig.queue.keyTap(0x04))
        rig.queue.tap()
        rig.queue.sendMotion(2, 0)
        rig.clock.advance(100)
        assertEquals(expected, rig.reports)

        rig.queue.resetInput()
        rig.clock.advance(100)
        assertFalse(rig.queue.keyTap(0x04))
        val afterFailedReset = rig.reports.size

        rig.onTransmit = { true }
        rig.queue.resetInput()
        rig.clock.advance(0)
        rig.queue.scroll(0, 1)
        rig.clock.advance(100)
        assertEquals(listOf(mouse(0), key(0), mouse(0, wheel = 1)), rig.reports.drop(afterFailedReset))
    }

    @Test
    fun cancellingScrollDropsPanAndZoomAndKeepsOrdinaryReports() {
        val rig = Rig().connected()
        rig.queue.keyTap(0x04)
        rig.queue.scroll(3, 5)
        rig.queue.zoom(2)
        rig.queue.sendMotion(7, 0)
        rig.queue.tap()
        rig.queue.cancelScroll()
        rig.clock.advance(100)

        assertEquals(listOf(key(0, 0x04), mouse(0, 7), key(0), mouse(1), mouse(0)), rig.reports)
    }

    @Test
    fun cancellingScrollAfterCtrlRestoresItBeforeOtherInput() {
        val rig = Rig().connected()
        rig.onTransmit = {
            if (it == key(1)) rig.queue.cancelScroll()
            true
        }
        rig.queue.zoom(200)
        rig.queue.tap()
        rig.queue.keyTap(0x04)
        rig.clock.advance(100)

        assertEquals(listOf(key(1), key(0), mouse(1), mouse(0), key(0, 0x04), key(0)), rig.reports)
    }
}
