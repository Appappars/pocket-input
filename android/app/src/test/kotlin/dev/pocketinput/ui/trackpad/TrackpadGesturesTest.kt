package dev.pocketinput.ui.trackpad

import dev.pocketinput.data.SettingsState
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.CANCEL_DRAG
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.HOLD_LEFT
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.LEFT_CLICK
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.RELEASE_LEFT
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.RIGHT_CLICK
import dev.pocketinput.ui.trackpad.TrackpadAction.Motion
import dev.pocketinput.ui.trackpad.TrackpadAction.Swipe
import dev.pocketinput.ui.trackpad.TrackpadAction.Wheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGesturesTest {
    @Test
    fun twoFastTapsClickImmediatelyByDefault() {
        val input = Input()
        input.tap(1000)
        assertEquals(listOf(LEFT_CLICK), input.actions)
        input.tap(1050)
        assertEquals(listOf(LEFT_CLICK, LEFT_CLICK), input.actions)
    }

    @Test
    fun tapDragModeDelaysASingleClickUntilItsDeadline() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.gestures.advance(1509)
        assertTrue(input.actions.isEmpty())
        assertEquals(1510L, input.gestures.nextDeadlineMs)
        input.gestures.advance(1510)
        assertEquals(listOf(LEFT_CLICK), input.actions)
    }

    @Test
    fun stationarySecondTouchStartsDragWithoutAFirstClick() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.frame(1100, finger(2, x = 3f))
        input.gestures.advance(1249)
        assertTrue(input.actions.isEmpty())
        input.gestures.advance(1250)
        input.frame(1255, finger(2, x = 15f))
        input.frame(1258, finger(2, x = 20f, pressed = false))
        assertEquals(listOf(HOLD_LEFT, Motion(12, 0), Motion(5, 0), RELEASE_LEFT), input.actions)
    }

    @Test
    fun twoShortTapsStillClickTwiceWhenTapDragIsEnabled() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.tap(1070, x = 2f)
        assertEquals(listOf(LEFT_CLICK, LEFT_CLICK), input.actions)
    }

    @Test
    fun distantSecondTapIsIndependent() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.tap(1070, x = 20f)
        assertEquals(listOf(LEFT_CLICK), input.actions)
        input.gestures.advance(1580)
        assertEquals(listOf(LEFT_CLICK, LEFT_CLICK), input.actions)
    }

    @Test
    fun earlyMotionDisqualifiesSecondTouchAndMovesNormally() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.frame(1100, finger(2))
        input.frame(1101, finger(2, x = 0.5f))
        assertTrue(input.actions.isEmpty())
        input.frame(1102, finger(2, x = 16f))
        input.gestures.advance(1250)
        input.frame(1251, finger(2, x = 16f, pressed = false))
        assertEquals(listOf(LEFT_CLICK, Motion(16, 0)), input.actions)
    }

    @Test
    fun scrollAfterATapTakesPrecedenceOverTapDrag() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.frame(1100, finger(2))
        input.frame(1105, finger(2), finger(3, x = 30f))
        input.frame(1110, finger(2, y = 20f), finger(3, x = 30f, y = 20f))
        input.frame(1120, finger(2, y = 20f), finger(3, x = 30f, y = 20f, pressed = false))
        input.frame(1130, finger(2, y = 60f))
        input.frame(1140, finger(2, y = 60f, pressed = false))
        assertEquals(listOf(LEFT_CLICK, Wheel(1)), input.actions)
    }

    @Test
    fun cancelDiscardsPendingClicksAndCancelsAnActiveDrag() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.gestures.cancel()
        input.gestures.advance(1510)
        assertTrue(input.actions.isEmpty())

        input.tap(2000)
        input.frame(2100, finger(2))
        input.gestures.advance(2250)
        input.gestures.cancel()
        input.gestures.cancel()
        assertEquals(listOf(HOLD_LEFT, CANCEL_DRAG), input.actions)
    }

    @Test
    fun addingAFingerCancelsDragAndDropsItsUnsentMovement() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.frame(1100, finger(2))
        input.gestures.advance(1250)
        input.frame(1260, finger(2, x = 10f))
        input.frame(1262, finger(2, x = 20f))
        input.frame(1263, finger(2, x = 20f), finger(3))
        input.frame(1264, finger(2, pressed = false), finger(3, pressed = false))
        input.gestures.advance(1300)
        assertEquals(listOf(HOLD_LEFT, Motion(10, 0), CANCEL_DRAG), input.actions)
    }

    @Test
    fun stationaryHoldProducesOneRightClickDespiteSmallJitter() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1120, finger(1, x = 6f))
        input.frame(1150, finger(1, x = -6f))
        input.frame(1200, finger(1))
        input.gestures.advance(1500)
        input.frame(1520, finger(1, pressed = false))
        assertEquals(listOf(RIGHT_CLICK), input.actions)
    }

    @Test
    fun explicitCaptureMovesWithoutPendingClicksOrHoldTimers() {
        val input = Input(tapDragEnabled = true)
        input.tap(1000)
        input.leftLatched = true
        input.frame(1100, finger(2))
        input.gestures.advance(1700)
        input.frame(1710, finger(2, x = 4f))
        input.frame(1720, finger(2, x = 4f, pressed = false))
        assertEquals(listOf(Motion(4, 0)), input.actions)
    }

    @Test
    fun explicitCaptureCancellationUsesTheCancelPath() {
        val input = Input()
        input.leftLatched = true
        input.frame(1000, finger(1))
        input.frame(1010, finger(1, x = 12f))
        input.gestures.cancel()
        assertEquals(listOf(Motion(12, 0), CANCEL_DRAG), input.actions)
    }

    @Test
    fun aShortFlickAndMovementInTheFinalFrameAreNotClicks() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1005, finger(1, x = 10f))
        input.frame(1010, finger(1, x = 20f, pressed = false))
        input.gestures.advance(1014)
        assertEquals(listOf(Motion(10, 0), Motion(10, 0)), input.actions)

        input.frame(1030, finger(1))
        input.frame(1035, finger(1, x = 15f, pressed = false))
        assertEquals(Motion(15, 0), input.actions.last())
    }

    @Test
    fun fractionalMotionSurvivesRateLimiting() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1010, finger(1, x = 9.25f))
        input.frame(1012, finger(1, x = 9.5f))
        input.frame(1014, finger(1, x = 10f))
        input.frame(1015, finger(1, x = 10.5f, pressed = false))
        assertEquals(listOf(Motion(9, 0)), input.actions)
        input.gestures.advance(1019)
        assertEquals(listOf(Motion(9, 0), Motion(1, 0)), input.actions)
    }

    @Test
    fun wheelAccumulatesAFractionalDetentAndPreservesThePacketRemainder() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 30f))
        input.frame(1005, finger(1, y = 10f), finger(2, x = 30f, y = 10f))
        assertTrue(input.actions.isEmpty())
        input.frame(1010, finger(1, y = 20f), finger(2, x = 30f, y = 20f))
        input.frame(1020, finger(1, y = 3020f), finger(2, x = 30f, y = 3020f))
        input.frame(1025, finger(1, pressed = false), finger(2, pressed = false))
        assertEquals(listOf(Wheel(1)), input.actions)
        input.gestures.advance(1027)
        input.gestures.advance(1044)
        assertEquals(listOf(Wheel(1), Wheel(127), Wheel(23)), input.actions)
    }

    @Test
    fun liftingFromATwoFingerTapDoesNotClickOrMoveThePointer() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1010, finger(1), finger(2))
        input.frame(1020, finger(1), finger(2, pressed = false))
        input.frame(1030, finger(1, x = 100f, pressed = false))
        assertTrue(input.actions.isEmpty())
    }

    @Test
    fun threeFingerSwipeUsesOnlyMovementWhileAllThreeAreDown() {
        val input = Input()
        input.frame(1000, finger(1), finger(2), finger(3))
        input.frame(1010, finger(1), finger(2, pressed = false), finger(3, pressed = false))
        input.frame(1020, finger(1, y = -90f))
        input.frame(1030, finger(1, y = -90f, pressed = false))
        assertTrue(input.actions.isEmpty())

        input.frame(2010, finger(1), finger(2))
        input.frame(2015, finger(1, y = -10f), finger(2, y = -10f))
        input.frame(2020, finger(1, y = -10f), finger(2, y = -10f), finger(3, y = -10f))
        input.frame(2030, finger(1, y = -80f), finger(2, y = -80f), finger(3, y = -80f))
        input.frame(2040, finger(1), finger(2, pressed = false), finger(3, pressed = false))
        input.frame(2050, finger(1, pressed = false))
        assertEquals(listOf(Swipe(TrackpadMath.ThreeFinger.UP)), input.actions)
    }

    private class Input(tapDragEnabled: Boolean = false) {
        var leftLatched = false
        val actions = mutableListOf<TrackpadAction>()
        val gestures = TrackpadGestures(
            SettingsState(tapDragEnabled = tapDragEnabled),
            density = 1f,
            isLeftLatched = { leftLatched },
            emit = { actions.add(it) },
        )

        fun frame(atMs: Long, vararg contacts: TrackpadContact) {
            gestures.onFrame(atMs, contacts.toList())
        }

        fun tap(atMs: Long, x: Float = 0f) {
            frame(atMs, finger(1, x))
            frame(atMs + 10, finger(1, x, pressed = false))
        }
    }

    private companion object {
        fun finger(id: Long, x: Float = 0f, y: Float = 0f, pressed: Boolean = true): TrackpadContact =
            TrackpadContact(id, TrackpadPoint(x, y), pressed)
    }
}
