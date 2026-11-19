package dev.pocketinput.ui.trackpad

import dev.pocketinput.data.SettingsState
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class TrackpadPoint(val x: Float, val y: Float)
internal data class TrackpadSize(val widthDp: Float, val heightDp: Float)

internal data class TrackpadContact(
    val id: Long,
    val position: TrackpadPoint,
    val pressed: Boolean = true,
)

internal sealed interface TrackpadAction {
    enum class Button : TrackpadAction { LEFT_CLICK, RIGHT_CLICK, HOLD_LEFT, RELEASE_LEFT, CANCEL_DRAG }
    data class Motion(val dx: Int, val dy: Int) : TrackpadAction
    data class Wheel(val detents: Int, val horizontal: Int = 0) : TrackpadAction
    data class Zoom(val detents: Int) : TrackpadAction
    data object CancelScroll : TrackpadAction
    data class Swipe(val direction: TrackpadMath.ThreeFinger) : TrackpadAction
}

internal class TrackpadGestures(
    private val settings: SettingsState,
    density: Float,
    private val padSize: (() -> TrackpadSize)? = null,
    private val isLeftLatched: () -> Boolean = { false },
    private val emit: (TrackpadAction) -> Unit,
) {
    private enum class Mode { IDLE, TAP, POINTER, DRAG, TWO_FINGER, SWIPE, WAIT_FOR_UP }
    private enum class TwoFingerGesture { UNDECIDED, PAN, ZOOM }

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
    private val edgeX = EdgeAxis()
    private val edgeY = EdgeAxis()
    private var edgeAtMs = Long.MAX_VALUE
    private var twoFingerGesture = TwoFingerGesture.UNDECIDED
    private var previousSpanDp = 0f
    private val panX = WheelAccumulator()
    private val panY = WheelAccumulator()
    private val zoom = WheelAccumulator()
    private var wheelAtMs = 0L

    val nextDeadlineMs: Long?
        get() {
            val motionDeadline = if (hasMotion()) motionAtMs else Long.MAX_VALUE
            val wheelDeadline = if (panX.isReady || panY.isReady || zoom.isReady) {
                wheelAtMs
            } else {
                Long.MAX_VALUE
            }
            return minOf(motionDeadline, wheelDeadline, edgeAtMs).takeUnless { it == Long.MAX_VALUE }
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
            Mode.TWO_FINGER -> updateTwoFinger(active)
            Mode.SWIPE -> updateSwipe(active)
            else -> updateSingle(active, contacts, nowMs)
        }
        advance(nowMs)
    }

    fun advance(nowMs: Long) {
        flushMotion(nowMs)
        flushWheel(nowMs)
        if (nowMs >= edgeAtMs) {
            updateEdge(previous, 0f, 0f, nowMs)
            if (edgeAtMs != Long.MAX_VALUE) {
                emit(TrackpadAction.Motion(edgeX.step, edgeY.step))
                edgeAtMs = nowMs + EDGE_INTERVAL_MS
            }
        }
    }

    fun cancel() {
        if (dragging()) emit(TrackpadAction.Button.CANCEL_DRAG)
        if (mode == Mode.TWO_FINGER) emit(TrackpadAction.CancelScroll)
        mode = Mode.IDLE
        motionX = 0f
        motionY = 0f
        stopAtEdge()
        panX.clear()
        panY.clear()
        zoom.clear()
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
            val dx = position.x - previous.x
            val dy = position.y - previous.y
            motionX += dx * motionScale
            motionY += dy * motionScale
            if (finger.pressed) {
                updateEdge(position, dx, dy, nowMs)
            } else {
                stopAtEdge()
            }
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
        stopAtEdge()
        mode = when (active.size) {
            2 -> Mode.TWO_FINGER
            3 -> Mode.SWIPE
            else -> Mode.WAIT_FOR_UP
        }
        fingerIds = active.mapTo(mutableSetOf()) { it.id }
        start = centroid(active)
        previous = start
        twoFingerGesture = TwoFingerGesture.UNDECIDED
        previousSpanDp = if (active.size == 2) span(active) else 0f
    }

    private fun updateTwoFinger(active: List<TrackpadContact>) {
        if (active.size == 2 && active.all { it.id in fingerIds }) {
            val position = centroid(active)
            val spanDp = span(active)
            if (twoFingerGesture == TwoFingerGesture.UNDECIDED) {
                val movement = distance(start, position)
                val spread = abs(spanDp - previousSpanDp) / 2f
                twoFingerGesture = when {
                    spread >= settings.tapSlopDp / 2f && spread >= movement -> TwoFingerGesture.ZOOM
                    movement >= settings.tapSlopDp -> TwoFingerGesture.PAN
                    else -> return
                }
            }
            if (twoFingerGesture == TwoFingerGesture.ZOOM) {
                zoom.add(TrackpadMath.pinchUnits(previousSpanDp, spanDp))
            } else {
                // Positive horizontal HID scroll moves the viewport right.
                panX.add(-TrackpadMath.scrollUnits(position.x - previous.x, settings.scrollGain, settings.invertScroll))
                panY.add(TrackpadMath.scrollUnits(position.y - previous.y, settings.scrollGain, settings.invertScroll))
            }
            previous = position
            previousSpanDp = spanDp
        } else if (
            active.size == 3 && twoFingerGesture == TwoFingerGesture.UNDECIDED &&
            fingerIds.all { id -> active.any { it.id == id } }
        ) {
            startMulti(active)
        } else {
            panX.finishGesture()
            panY.finishGesture()
            zoom.finishGesture()
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

    private fun updateEdge(position: TrackpadPoint, dx: Float, dy: Float, nowMs: Long) {
        if (!dragging()) {
            stopAtEdge()
            return
        }
        val size = padSize?.invoke() ?: return
        edgeX.update(position.x, size.widthDp, dx)
        edgeY.update(position.y, size.heightDp, dy)
        if (edgeX.step == 0 && edgeY.step == 0) {
            edgeAtMs = Long.MAX_VALUE
        } else if (edgeAtMs == Long.MAX_VALUE) {
            edgeAtMs = nowMs + EDGE_INTERVAL_MS
        }
    }

    private fun stopAtEdge() {
        edgeX.clear()
        edgeY.clear()
        edgeAtMs = Long.MAX_VALUE
    }

    private class EdgeAxis {
        var step = 0
            private set
        private var nearest = Float.MAX_VALUE

        fun update(position: Float, extent: Float, movement: Float) {
            if (extent <= 0f) return clear()
            val zone = minOf(EDGE_ZONE_DP, extent / 5f)
            val direction = when {
                position <= zone -> -1
                position >= extent - zone -> 1
                else -> return clear()
            }
            val distance = if (direction < 0) position else extent - position
            if (step * direction > 0) {
                nearest = minOf(nearest, distance)
                if (distance - nearest >= EDGE_DIRECTION_SLOP_DP) return clear()
            } else {
                val outward = movement * direction
                val enteredZone = distance + outward > zone
                if (!enteredZone && outward < EDGE_DIRECTION_SLOP_DP) return clear()
                nearest = distance
            }
            val depth = (1f - distance / zone).coerceIn(0f, 1f)
            step = direction * (2f + 16f * depth).roundToInt()
        }

        fun clear() {
            step = 0
            nearest = Float.MAX_VALUE
        }
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
        val horizontal = panX.take()
        val vertical = panY.take()
        val zoomDetents = zoom.take()
        val panSent = horizontal != 0 || vertical != 0
        if (!panSent && zoomDetents == 0) return
        if (panSent) emit(TrackpadAction.Wheel(vertical, horizontal))
        if (zoomDetents != 0) emit(TrackpadAction.Zoom(zoomDetents))
        wheelAtMs = nowMs + WHEEL_INTERVAL_MS
    }

    private class WheelAccumulator {
        private var pending = 0f
        private var gesture = 0f
        val isReady: Boolean get() = abs(pending + gesture) >= 1f

        fun add(delta: Float) {
            gesture += delta
        }

        fun take(): Int {
            val accumulated = pending + gesture
            val detents = accumulated.toInt().coerceIn(-TrackpadMath.MAX_DELTA_PX, TrackpadMath.MAX_DELTA_PX)
            if (detents != 0) {
                pending = accumulated - detents
                gesture = 0f
            }
            return detents
        }

        fun finishGesture() {
            pending += gesture
            gesture = 0f
        }

        fun clear() {
            pending = 0f
            gesture = 0f
        }
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

    private fun span(contacts: List<TrackpadContact>): Float =
        distance(contacts[0].position, contacts[1].position)

    private companion object {
        const val MOTION_INTERVAL_MS = (TrackpadMath.MIN_REPORT_INTERVAL_NS + 999_999L) / 1_000_000L
        const val WHEEL_INTERVAL_MS = (TrackpadMath.MIN_WHEEL_INTERVAL_NS + 999_999L) / 1_000_000L
        const val EDGE_INTERVAL_MS = 16L
        const val EDGE_ZONE_DP = 18f
        const val EDGE_DIRECTION_SLOP_DP = 1f
    }
}
