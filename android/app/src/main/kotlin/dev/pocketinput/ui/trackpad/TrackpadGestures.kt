package dev.pocketinput.ui.trackpad

import dev.pocketinput.data.SettingsState
import kotlin.math.abs

internal data class TrackpadPoint(val x: Float, val y: Float)

internal data class TrackpadContact(
    val id: Long,
    val position: TrackpadPoint,
    val pressed: Boolean = true,
)

internal sealed interface TrackpadAction {
    enum class Button : TrackpadAction { LEFT_CLICK, RIGHT_CLICK, HOLD_LEFT, RELEASE_LEFT, CANCEL_DRAG }
    data class Motion(val dx: Int, val dy: Int) : TrackpadAction
    data class Wheel(val detents: Int) : TrackpadAction
    data class Swipe(val direction: TrackpadMath.ThreeFinger) : TrackpadAction
}

internal class TrackpadGestures(
    private val settings: SettingsState,
    density: Float,
    private val isLeftLatched: () -> Boolean = { false },
    private val emit: (TrackpadAction) -> Unit,
) {
    private enum class Mode { IDLE, TAP, POINTER, DRAG, SCROLL, SWIPE, WAIT_FOR_UP }

    private val motionScale = density * settings.sensitivity
    private var mode = Mode.IDLE
    private var fingerIds = emptySet<Long>()
    private var start = TrackpadPoint(0f, 0f)
    private var previous = start
    private var downAtMs = 0L
    private var movedBeforeHold = false

    private var motionX = 0f
    private var motionY = 0f
    private var motionAtMs = 0L
    private var scrollInGesture = 0f
    private var wheelPending = 0f
    private var wheelAtMs = 0L
    private var wheelSentInGesture = false

    val nextDeadlineMs: Long?
        get() {
            val motionDeadline = if (hasMotion()) motionAtMs else Long.MAX_VALUE
            val wheelDeadline = if (abs(wheelPending + scrollInGesture) >= 1f) {
                wheelAtMs
            } else {
                Long.MAX_VALUE
            }
            return minOf(motionDeadline, wheelDeadline).takeUnless { it == Long.MAX_VALUE }
        }

    fun onFrame(nowMs: Long, contacts: List<TrackpadContact>) {
        suspendTapRecognitionWhileLatched()
        val active = contacts.filter { it.pressed }
        when (mode) {
            Mode.IDLE -> when (active.size) {
                0 -> Unit
                1 -> startSingle(active.single(), nowMs)
                else -> startMulti(active)
            }
            Mode.WAIT_FOR_UP -> if (active.isEmpty()) mode = Mode.IDLE
            Mode.SCROLL -> updateScroll(active)
            Mode.SWIPE -> updateSwipe(active)
            else -> updateSingle(active, contacts, nowMs)
        }
        advance(nowMs)
    }

    fun advance(nowMs: Long) {
        flushMotion(nowMs)
        flushWheel(nowMs)
    }

    fun cancel() {
        if (dragging()) emit(TrackpadAction.Button.CANCEL_DRAG)
        mode = Mode.IDLE
        motionX = 0f
        motionY = 0f
        scrollInGesture = 0f
        wheelPending = 0f
        motionAtMs = 0L
        wheelAtMs = 0L
    }

    private fun startSingle(contact: TrackpadContact, nowMs: Long) {
        mode = if (isLeftLatched()) Mode.POINTER else Mode.TAP
        downAtMs = nowMs
        movedBeforeHold = false
        fingerIds = setOf(contact.id)
        start = contact.position
        previous = start
    }

    private fun suspendTapRecognitionWhileLatched() {
        if (mode == Mode.TAP && isLeftLatched()) {
            mode = Mode.POINTER
            previous = start
        }
    }

    private fun updateSingle(
        active: List<TrackpadContact>,
        contacts: List<TrackpadContact>,
        nowMs: Long,
    ) {
        val finger = contacts.firstOrNull { it.id in fingerIds }
        if (active.size > 1) {
            if (dragging()) {
                cancel()
                mode = Mode.WAIT_FOR_UP
            } else {
                flushMotion(nowMs, beforeButton = true)
                startMulti(active)
            }
            return
        }
        if (finger == null || (active.isNotEmpty() && !finger.pressed)) {
            cancel()
            if (active.isNotEmpty()) mode = Mode.WAIT_FOR_UP
            return
        }

        val position = finger.position
        val heldMs = nowMs - downAtMs
        if (mode == Mode.TAP) {
            val shiftDp = distance(start, position)
            val heldLongEnough = heldMs >= settings.tapAndHoldMs
            // Ранний сдвиг отменяет захват, сохраняя допустимое дрожание обычного тапа.
            if (!heldLongEnough && shiftDp >= settings.tapSlopDp / 2f) movedBeforeHold = true
            if (!TrackpadMath.isTap(shiftDp, settings.tapSlopDp)) {
                mode = if (finger.pressed && heldLongEnough && !movedBeforeHold) Mode.DRAG else Mode.POINTER
                previous = start
                if (mode == Mode.DRAG) sendButton(TrackpadAction.Button.HOLD_LEFT, nowMs)
            }
        }
        if (mode == Mode.POINTER || mode == Mode.DRAG) {
            motionX += (position.x - previous.x) * motionScale
            motionY += (position.y - previous.y) * motionScale
        }
        previous = position
        if (finger.pressed) return

        when (mode) {
            Mode.TAP -> {
                val rightClick = TrackpadMath.isLongPress(
                    heldMs,
                    distance(start, position),
                    holdThresholdMs = settings.longPressMs.toLong(),
                    thresholdDp = settings.tapSlopDp,
                )
                val click = if (rightClick) {
                    TrackpadAction.Button.RIGHT_CLICK
                } else {
                    TrackpadAction.Button.LEFT_CLICK
                }
                sendButton(click, nowMs)
            }
            Mode.DRAG -> sendButton(TrackpadAction.Button.RELEASE_LEFT, nowMs)
            else -> Unit
        }
        mode = Mode.IDLE
    }

    private fun startMulti(active: List<TrackpadContact>) {
        mode = when (active.size) {
            2 -> Mode.SCROLL
            3 -> Mode.SWIPE
            else -> Mode.WAIT_FOR_UP
        }
        fingerIds = active.mapTo(mutableSetOf()) { it.id }
        start = centroid(active)
        previous = start
        wheelSentInGesture = false
    }

    private fun updateScroll(active: List<TrackpadContact>) {
        if (active.size == 2 && active.all { it.id in fingerIds }) {
            val position = centroid(active)
            scrollInGesture += TrackpadMath.scrollUnits(
                position.y - previous.y, settings.scrollGain, settings.invertScroll,
            )
            previous = position
        } else if (
            active.size == 3 && !wheelSentInGesture &&
            fingerIds.all { id -> active.any { it.id == id } }
        ) {
            scrollInGesture = 0f
            startMulti(active)
        } else {
            wheelPending += scrollInGesture
            scrollInGesture = 0f
            mode = if (active.isEmpty()) Mode.IDLE else Mode.WAIT_FOR_UP
        }
    }

    private fun updateSwipe(active: List<TrackpadContact>) {
        if (active.size == 3 && active.all { it.id in fingerIds }) {
            previous = centroid(active)
            return
        }
        if (active.size < 3 && active.all { it.id in fingerIds }) {
            val direction = TrackpadMath.classifyThreeFinger(previous.x - start.x, previous.y - start.y)
            if (direction != TrackpadMath.ThreeFinger.NONE) emit(TrackpadAction.Swipe(direction))
        }
        mode = if (active.isEmpty()) Mode.IDLE else Mode.WAIT_FOR_UP
    }

    private fun sendButton(action: TrackpadAction.Button, nowMs: Long) {
        flushMotion(nowMs, beforeButton = true)
        emit(action)
    }

    private fun dragging(): Boolean = mode == Mode.DRAG || (mode == Mode.POINTER && isLeftLatched())

    private fun hasMotion(): Boolean = abs(motionX) >= 1f || abs(motionY) >= 1f

    private fun flushMotion(nowMs: Long, beforeButton: Boolean = false) {
        if (!beforeButton && nowMs < motionAtMs) return
        while (hasMotion()) {
            val dx = motionX.toInt().coerceIn(-TrackpadMath.MAX_DELTA_PX, TrackpadMath.MAX_DELTA_PX)
            val dy = motionY.toInt().coerceIn(-TrackpadMath.MAX_DELTA_PX, TrackpadMath.MAX_DELTA_PX)
            motionX -= dx
            motionY -= dy
            emit(TrackpadAction.Motion(dx, dy))
            motionAtMs = nowMs + MOTION_INTERVAL_MS
            if (!beforeButton) return
        }
    }

    private fun flushWheel(nowMs: Long) {
        if (nowMs < wheelAtMs) return
        val accumulated = wheelPending + scrollInGesture
        val detents = accumulated.toInt().coerceIn(-TrackpadMath.MAX_DELTA_PX, TrackpadMath.MAX_DELTA_PX)
        if (detents == 0) return
        emit(TrackpadAction.Wheel(detents))
        wheelPending = accumulated - detents
        scrollInGesture = 0f
        wheelAtMs = nowMs + WHEEL_INTERVAL_MS
        if (mode == Mode.SCROLL) wheelSentInGesture = true
    }

    private fun centroid(contacts: List<TrackpadContact>): TrackpadPoint {
        var x = 0f
        var y = 0f
        for (contact in contacts) {
            x += contact.position.x
            y += contact.position.y
        }
        return TrackpadPoint(x / contacts.size, y / contacts.size)
    }

    private fun distance(from: TrackpadPoint, to: TrackpadPoint): Float =
        TrackpadMath.distanceDp(to.x - from.x, to.y - from.y)

    private companion object {
        const val MOTION_INTERVAL_MS = (TrackpadMath.MIN_REPORT_INTERVAL_NS + 999_999L) / 1_000_000L
        const val WHEEL_INTERVAL_MS = (TrackpadMath.MIN_WHEEL_INTERVAL_NS + 999_999L) / 1_000_000L
    }
}
