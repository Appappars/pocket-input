package dev.pocketinput.ui.trackpad

import dev.pocketinput.data.SettingsState
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.CANCEL_DRAG
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.HOLD_LEFT
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.LEFT_CLICK
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.RELEASE_LEFT
import dev.pocketinput.ui.trackpad.TrackpadAction.Button.RIGHT_CLICK
import dev.pocketinput.ui.trackpad.TrackpadAction.CancelScroll
import dev.pocketinput.ui.trackpad.TrackpadAction.Motion
import dev.pocketinput.ui.trackpad.TrackpadAction.Swipe
import dev.pocketinput.ui.trackpad.TrackpadAction.Wheel
import dev.pocketinput.ui.trackpad.TrackpadAction.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGesturesTest {
    @Test
    fun ordinaryPointerStopsAtTheEdge() {
        val input = Input(padWidthDp = 200f)
        input.frame(1000, finger(1, x = 130f))
        input.frame(1020, finger(1, x = 198f))

        assertNull(input.gestures.nextDeadlineMs)
        input.gestures.advance(1200)
        input.frame(1220, finger(1, x = 203f))
        assertEquals(listOf(Motion(68, 0), Motion(5, 0)), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun movingBackStopsEdgeMotionWhileStillNearTheEdge() {
        val input = Input(padHeightDp = 200f)
        input.leftLatched = true
        input.frame(1000, finger(1, y = 70f))
        input.frame(1020, finger(1, y = 2f))
        input.gestures.advance(1036)
        val edgeStep = input.actions.last() as Motion
        assertTrue(edgeStep.dy < 0)

        input.frame(1040, finger(1, y = 8f))
        assertNull(input.gestures.nextDeadlineMs)
        input.gestures.advance(1200)
        assertEquals(listOf(Motion(0, -68), edgeStep, Motion(0, 6)), input.actions)
    }

    @Test
    fun smallStepsMovingInwardAccumulateAndStopEdgeMotion() {
        val input = Input(padHeightDp = 200f)
        input.leftLatched = true
        input.frame(1000, finger(1, y = 70f))
        input.frame(1020, finger(1, y = 2f))
        input.gestures.advance(1036)
        val edgeStep = input.actions.last() as Motion
        input.frame(1040, finger(1, y = 2.5f))
        input.frame(1047, finger(1, y = 3f))
        input.gestures.advance(1200)

        assertEquals(listOf(Motion(0, -68), edgeStep, Motion(0, 1)), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun aSmallStepIntoTheEdgeZoneStillStartsEdgeMotion() {
        val input = Input(padWidthDp = 200f)
        input.leftLatched = true
        input.frame(1000, finger(1, x = 130f))
        input.frame(1020, finger(1, x = 181.8f))
        input.frame(1040, finger(1, x = 182.2f))
        assertEquals(1056L, input.gestures.nextDeadlineMs)
        input.gestures.advance(1056)
        assertTrue((input.actions.last() as Motion).dx > 0)
    }

    @Test
    fun edgeMotionWhileDraggingStopsBeforeTheLeftButtonIsReleased() {
        val input = Input(padWidthDp = 200f)
        input.frame(1000, finger(1, x = 130f))
        input.frame(1150, finger(1, x = 198f))
        input.gestures.advance(1166)
        input.gestures.advance(1182)
        input.frame(1186, finger(1, x = 198f, pressed = false))
        input.gestures.advance(1300)

        assertEquals(5, input.actions.size)
        assertEquals(listOf(HOLD_LEFT, Motion(68, 0)), input.actions.take(2))
        assertTrue(input.actions.subList(2, 4).all { it is Motion && it.dx > 0 && it.dy == 0 })
        assertEquals(RELEASE_LEFT, input.actions.last())
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun unlockingLeftButtonStopsEdgeMotionWithFingerStillDown() {
        val input = Input(padWidthDp = 200f)
        input.leftLatched = true
        input.frame(1000, finger(1, x = 130f))
        input.frame(1020, finger(1, x = 198f))
        input.gestures.advance(1036)
        assertEquals(2, input.actions.size)
        val edgeStep = input.actions.last() as Motion
        assertTrue(edgeStep.dx > 0)

        input.leftLatched = false
        input.gestures.advance(1052)
        input.frame(1070, finger(1, x = 203f))

        assertEquals(listOf(Motion(68, 0), edgeStep, Motion(5, 0)), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun padResizeUpdatesTheEdgeWithoutCancellingTheDrag() {
        val input = Input(padWidthDp = 200f)
        input.frame(1000, finger(1, x = 130f))
        input.frame(1150, finger(1, x = 198f))
        input.padSize = TrackpadSize(250f, 0f)
        input.gestures.advance(1166)
        assertEquals(listOf(HOLD_LEFT, Motion(68, 0)), input.actions)
        assertNull(input.gestures.nextDeadlineMs)

        input.frame(1172, finger(1, x = 205f))
        input.frame(1175, finger(1, x = 205f, pressed = false))
        assertEquals(listOf(HOLD_LEFT, Motion(68, 0), Motion(7, 0), RELEASE_LEFT), input.actions)
    }

    @Test
    fun touchingAnEdgeWithoutApproachingItDoesNotStartMotion() {
        val input = Input(padWidthDp = 200f)
        input.frame(1000, finger(1, x = 198f))
        input.gestures.advance(1150)
        input.frame(1151, finger(1, x = 198f, pressed = false))
        assertEquals(listOf(LEFT_CLICK), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun movingTwoFingersSidewaysPansHorizontally() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = 20f), finger(2, x = 60f))
        assertEquals(listOf(Wheel(0, horizontal = -1)), input.actions)
    }

    @Test
    fun stretchingTwoFingersZoomsEvenWithACentroidAtRest() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = -6f), finger(2, x = 46f))
        assertEquals(listOf(Zoom(2)), input.actions)
    }

    @Test
    fun shrinkingTwoFingersZoomsOutWithoutVerticalScroll() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = 4f, y = 2f), finger(2, x = 36f, y = 2f))
        assertEquals(listOf(Zoom(-2)), input.actions)
    }

    @Test
    fun diagonalPanMovesBothAxesAndIgnoresLaterSpread() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = 20f, y = 20f), finger(2, x = 60f, y = 20f))
        input.frame(1040, finger(1, x = 12f, y = 20f), finger(2, x = 68f, y = 20f))
        assertEquals(listOf(Wheel(1, horizontal = -1)), input.actions)
    }

    @Test
    fun liftingOneFingerAfterPinchDoesNotMoveTheCursor() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = -6f), finger(2, x = 46f))
        input.frame(1040, finger(1, x = -6f), finger(2, x = 46f, pressed = false))
        input.frame(1060, finger(1, x = -46f))
        input.frame(1070, finger(1, x = -46f, pressed = false))
        assertEquals(listOf(Zoom(2)), input.actions)
    }

    @Test
    fun cancellingAnActivePinchCancelsQueuedScroll() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1020, finger(1, x = -6f), finger(2, x = 46f))
        input.gestures.cancel()
        assertEquals(listOf(Zoom(2), CancelScroll), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
    }

    @Test
    fun addingAThirdFingerDoesNotTurnARecognizedPanIntoASwipe() {
        val input = Input()
        input.frame(1000, finger(1), finger(2, x = 40f))
        input.frame(1010, finger(1, y = -10f), finger(2, x = 40f, y = -10f))
        input.frame(1020, finger(1, y = -10f), finger(2, x = 40f, y = -10f), finger(3))
        input.frame(1030, finger(1, y = -90f), finger(2, x = 40f, y = -90f), finger(3, y = -80f))
        input.frame(1040, finger(1, pressed = false), finger(2, pressed = false), finger(3, pressed = false))
        assertTrue(input.actions.isEmpty())
    }

    @Test
    fun heldContactPressesLeftBeforeSendingItsFirstMovement() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1060, finger(1, x = 2f))
        input.frame(1150, finger(1, x = 12f))
        input.frame(1153, finger(1, x = 17f, pressed = false))
        assertEquals(listOf(HOLD_LEFT, Motion(12, 0), Motion(5, 0), RELEASE_LEFT), input.actions)
    }

    @Test
    fun holdingPastRightClickThresholdStillAllowsDrag() {
        val input = Input()
        input.frame(1000, finger(1))
        input.gestures.advance(1600)
        assertTrue(input.actions.isEmpty())
        input.frame(1605, finger(1, x = 15f))
        input.frame(1610, finger(1, x = 15f, pressed = false))
        assertEquals(listOf(HOLD_LEFT, Motion(15, 0), RELEASE_LEFT), input.actions)
    }

    @Test
    fun eachFastTapClicksOnReleaseWithoutADeadline() {
        val input = Input()
        input.frame(1000, finger(1))
        assertTrue(input.actions.isEmpty())
        assertNull(input.gestures.nextDeadlineMs)
        input.frame(1010, finger(1, pressed = false))
        assertEquals(listOf(LEFT_CLICK), input.actions)
        assertNull(input.gestures.nextDeadlineMs)
        input.tap(1050)
        assertEquals(listOf(LEFT_CLICK, LEFT_CLICK), input.actions)
    }

    @Test
    fun movementBeforeTheHoldStaysPointerMovement() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1149, finger(1, x = 16f))
        input.frame(1700, finger(1, x = 24f))
        input.frame(1710, finger(1, x = 24f, pressed = false))
        assertEquals(listOf(Motion(16, 0), Motion(8, 0)), input.actions)
    }

    @Test
    fun slowMovementBeforeTheHoldDoesNotStartDragLater() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1060, finger(1, x = 4f))
        input.frame(1155, finger(1, x = 9f))
        input.frame(1160, finger(1, x = 9f, pressed = false))
        assertEquals(listOf(Motion(9, 0)), input.actions)
    }

    @Test
    fun releaseWithMovementCannotStartAHold() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1550, finger(1, x = 16f, pressed = false))
        assertEquals(listOf(Motion(16, 0)), input.actions)
    }

    @Test
    fun scrollAfterATapStillWorks() {
        val input = Input()
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
    fun cancelDropsAnUnfinishedTapAndCancelsAnActiveDrag() {
        val input = Input()
        input.frame(1000, finger(1))
        input.gestures.cancel()
        input.gestures.advance(1510)
        assertTrue(input.actions.isEmpty())

        input.frame(2000, finger(2))
        input.frame(2150, finger(2, x = 12f))
        input.gestures.cancel()
        input.gestures.cancel()
        assertEquals(listOf(HOLD_LEFT, Motion(12, 0), CANCEL_DRAG), input.actions)
    }

    @Test
    fun addingAFingerCancelsDragAndDropsItsUnsentMovement() {
        val input = Input()
        input.frame(1000, finger(2))
        input.frame(1150, finger(2, x = 10f))
        input.frame(1152, finger(2, x = 20f))
        input.frame(1153, finger(2, x = 20f), finger(3))
        input.frame(1154, finger(2, pressed = false), finger(3, pressed = false))
        input.gestures.advance(1300)
        assertEquals(listOf(HOLD_LEFT, Motion(10, 0), CANCEL_DRAG), input.actions)
    }

    @Test
    fun stationaryHoldRightClicksOnlyOnReleaseDespiteSmallJitter() {
        val input = Input()
        input.frame(1000, finger(1))
        input.frame(1120, finger(1, x = 6f))
        input.frame(1150, finger(1, x = -6f))
        input.frame(1200, finger(1))
        input.gestures.advance(1500)
        assertTrue(input.actions.isEmpty())
        input.frame(1520, finger(1, pressed = false))
        assertEquals(listOf(RIGHT_CLICK), input.actions)
    }

    @Test
    fun explicitCaptureMovesEvenBelowTapSlop() {
        val input = Input()
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
        input.frame(2015, finger(1, y = -4f), finger(2, y = -4f))
        input.frame(2020, finger(1, y = -4f), finger(2, y = -4f), finger(3, y = -4f))
        input.frame(2030, finger(1, y = -80f), finger(2, y = -80f), finger(3, y = -80f))
        input.frame(2040, finger(1), finger(2, pressed = false), finger(3, pressed = false))
        input.frame(2050, finger(1, pressed = false))
        assertEquals(listOf(Swipe(TrackpadMath.ThreeFinger.UP)), input.actions)
    }

    private class Input(padWidthDp: Float = 0f, padHeightDp: Float = 0f) {
        var leftLatched = false
        var padSize = TrackpadSize(padWidthDp, padHeightDp)
        val actions = mutableListOf<TrackpadAction>()
        val gestures = TrackpadGestures(
            SettingsState(),
            density = 1f,
            padSize = { padSize },
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
