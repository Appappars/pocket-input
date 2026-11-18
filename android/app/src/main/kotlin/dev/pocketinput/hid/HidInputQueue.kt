package dev.pocketinput.hid

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val KEYBOARD_QUEUE_CAPACITY = 128

internal fun interface ScheduledHidTask {
    fun cancel()
}

internal interface HidQueueScheduler : AutoCloseable {
    fun nowNanos(): Long

    // Runs actions asynchronously and serially, in scheduling order for equal deadlines.
    fun schedule(delayNanos: Long, action: () -> Unit): ScheduledHidTask
}

private class HidWorker : HidQueueScheduler {
    private val executor = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }

    override fun nowNanos(): Long = System.nanoTime()

    override fun schedule(delayNanos: Long, action: () -> Unit): ScheduledHidTask {
        val future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        return ScheduledHidTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdown()
    }
}

internal class HidInputQueue(
    private val onSendResult: (sessionId: Long, waitedMs: Float, ok: Boolean) -> Unit,
    private val scheduler: HidQueueScheduler = HidWorker(),
) : AutoCloseable {
    private val lock = Any()
    private var session: Session? = null
    private var closed = false
    private var keyboardVersion = 0L

    val currentButtons: Int
        get() = synchronized(lock) { session?.desiredButtons ?: 0 }

    val keyboardRevision: Long
        get() = synchronized(lock) { keyboardVersion }

    fun currentReport(id: Int): ByteArray? = synchronized(lock) {
        when (id) {
            MOUSE -> mouse(session?.mouse?.accepted?.get(0)?.toInt() ?: 0)
            KEYBOARD -> session?.keyboard?.accepted?.copyOf() ?: Descriptors.keyboardReport(0)
            else -> null
        }
    }

    fun open(sessionId: Long, send: (Int, ByteArray) -> Boolean) {
        val cancelled = synchronized(lock) {
            if (closed || session?.id == sessionId) return
            replaceSessionLocked(Session(sessionId, send))
        }
        rejectProbes(cancelled)
    }

    fun disconnect() {
        val cancelled = synchronized(lock) { replaceSessionLocked(null) }
        rejectProbes(cancelled)
    }

    fun resetInput() {
        val cancelled = synchronized(lock) {
            keyboardVersion++
            val current = session ?: return
            replaceSessionLocked(Session(current.id, current.send))
        }
        rejectProbes(cancelled)
    }

    override fun close() {
        val cancelled = synchronized(lock) {
            if (closed) return
            closed = true
            replaceSessionLocked(null)
        }
        scheduler.close()
        rejectProbes(cancelled)
    }

    fun tap(button: Int = 1, holdMs: Long = 40) {
        synchronized(lock) {
            val current = session ?: return
            val mask = button and BUTTON_MASK
            if (mask == 0 || current.mouse.pending.size >= MOUSE_LIMIT) return
            current.mouse.pending.addLast(Tap(mask, 0, holdMs, scheduler.nowNanos(), current.nextOrder++))
            scheduleReadyLocked(current)
        }
    }

    fun pressButton(bit: Int) {
        if (bit in 1..5) changeButtons { it or (1 shl (bit - 1)) }
    }

    fun releaseButton(bit: Int) {
        if (bit in 1..5) changeButtons { it and (1 shl (bit - 1)).inv() }
    }

    fun setButtons(mask: Int) = changeButtons { mask and BUTTON_MASK }

    fun releaseAllButtons() = cancelMouseInput(0)

    fun cancelMouseInput(keepMask: Int) {
        val cancelled = synchronized(lock) {
            val current = session ?: return
            val mask = keepMask and BUTTON_MASK
            current.desiredButtons = mask
            val pendingProbes = clearLaneLocked(current.mouse)
            appendStateLocked(current, current.mouse, mouse(mask), retry = true, ordered = false)
            pendingProbes
        }
        rejectProbes(cancelled)
    }

    fun sendMotion(dx: Int, dy: Int) = enqueueDelta(dx, dy, wheel = 0, scroll = false)

    fun wheel(units: Int) = enqueueDelta(0, 0, wheel = units, scroll = true)

    fun keyTap(usage: Int, mods: Int = 0, holdMs: Long = 20, expectedSessionId: Long? = null): Boolean =
        enqueueKeyTaps(listOf(usage to mods), holdMs, expectedSessionId, expectedKeyboardRevision = null)

    fun keyTaps(
        keys: List<Pair<Int, Int>>,
        expectedSessionId: Long,
        expectedKeyboardRevision: Long? = null,
    ): Boolean = enqueueKeyTaps(keys, 20, expectedSessionId, expectedKeyboardRevision)

    fun keyDown(usage: Int, mods: Int = 0, expectedSessionId: Long? = null): Boolean = synchronized(lock) {
        val current = matchingSessionLocked(expectedSessionId) ?: return false
        if (current.keyboard.pending.size >= KEYBOARD_QUEUE_CAPACITY) return false
        appendStateLocked(current, current.keyboard, Descriptors.keyboardReport(mods, usage), retry = false)
        true
    }

    fun keyUp(mods: Int = 0, expectedSessionId: Long? = null): Boolean = synchronized(lock) {
        val current = matchingSessionLocked(expectedSessionId) ?: return false
        val lane = current.keyboard
        // An explicit release has one reserved slot even when a text edit filled the queue.
        if (lane.pending.size > KEYBOARD_QUEUE_CAPACITY) lane.pending.removeLast()
        appendStateLocked(current, lane, Descriptors.keyboardReport(mods), retry = true)
        true
    }

    fun probe(callback: (Boolean) -> Unit) {
        val queued = synchronized(lock) {
            val current = session
            if (current == null || current.mouse.pending.size >= MOUSE_LIMIT) {
                false
            } else {
                current.mouse.pending.addLast(Probe(callback, scheduler.nowNanos()))
                scheduleReadyLocked(current)
                true
            }
        }
        if (!queued) runCatching { callback(false) }
    }

    private fun changeButtons(transform: (Int) -> Int) {
        val cancelled = synchronized(lock) {
            val current = session ?: return
            val before = current.desiredButtons
            val after = transform(before)
            if (before == after) return
            val lane = current.mouse
            val releasesButton = (before and after) != before || lane.pending.size >= MOUSE_LIMIT
            val pendingProbes = makeRoomForButtonsLocked(lane)
            current.desiredButtons = after
            appendStateLocked(current, lane, mouse(after), retry = releasesButton)
            pendingProbes
        }
        rejectProbes(cancelled)
    }

    private fun makeRoomForButtonsLocked(lane: Lane): List<(Boolean) -> Unit> {
        if (lane.pending.size < MOUSE_LIMIT) return emptyList()
        val firstDelta = lane.pending.indexOfFirst { it is Delta }
        if (firstDelta < 0) return clearLaneLocked(lane)
        lane.pending.removeAt(firstDelta)
        return emptyList()
    }

    private fun enqueueKeyTaps(
        keys: List<Pair<Int, Int>>,
        holdMs: Long,
        expectedSessionId: Long?,
        expectedKeyboardRevision: Long?,
    ): Boolean =
        synchronized(lock) {
            val current = matchingSessionLocked(expectedSessionId) ?: return false
            if (expectedKeyboardRevision != null && expectedKeyboardRevision != keyboardVersion) return false
            if (keys.isEmpty()) return true
            val lane = current.keyboard
            if (keys.size > KEYBOARD_QUEUE_CAPACITY - lane.pending.size) return false
            val now = scheduler.nowNanos()
            for ((usage, modifiers) in keys) {
                lane.pending.addLast(Tap(usage, modifiers, holdMs, now, current.nextOrder++))
            }
            scheduleReadyLocked(current)
            true
        }

    private fun matchingSessionLocked(expected: Long?): Session? =
        session?.takeIf { expected == null || it.id == expected }

    private fun enqueueDelta(dx: Int, dy: Int, wheel: Int, scroll: Boolean) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        synchronized(lock) {
            val current = session ?: return
            val lane = current.mouse
            val previous = lane.pending.lastOrNull() as? Delta
            if (previous != null && previous.scroll == scroll) {
                previous.x = boundedDelta(previous.x, dx)
                previous.y = boundedDelta(previous.y, dy)
                previous.wheel = boundedDelta(previous.wheel, wheel)
                if (previous.isEmpty()) lane.pending.removeLast()
            } else if (lane.pending.size < MOUSE_LIMIT) {
                lane.pending.addLast(
                    Delta(
                        x = boundedDelta(0, dx),
                        y = boundedDelta(0, dy),
                        wheel = boundedDelta(0, wheel),
                        scroll = scroll,
                        enqueuedAt = scheduler.nowNanos(),
                    ),
                )
            }
            scheduleReadyLocked(current)
        }
    }

    private fun replaceSessionLocked(next: Session?): List<(Boolean) -> Unit> {
        val cancelled = session?.let { clearLaneLocked(it.mouse) + clearLaneLocked(it.keyboard) }.orEmpty()
        session = next
        if (next != null) {
            appendStateLocked(next, next.mouse, next.mouse.base, retry = true, ordered = false)
            appendStateLocked(next, next.keyboard, next.keyboard.base, retry = true, ordered = false)
        }
        return cancelled
    }

    private fun clearLaneLocked(lane: Lane): List<(Boolean) -> Unit> {
        val probes = lane.pending.filterIsInstance<Probe>().map { it.complete } + listOfNotNull(lane.inFlightProbe)
        lane.generation++
        lane.timer?.cancel()
        lane.timer = null
        lane.sending = false
        lane.inFlightOrder = null
        lane.pending.clear()
        lane.delayed = null
        lane.nextDeltaAt = null
        lane.inFlightProbe = null
        return probes
    }

    private fun appendStateLocked(
        state: Session,
        lane: Lane,
        report: ByteArray,
        retry: Boolean,
        ordered: Boolean = true,
    ) {
        val order = if (ordered) state.nextOrder++ else null
        lane.pending.addLast(SetState(report, retry, scheduler.nowNanos(), order))
        scheduleReadyLocked(state)
    }

    private fun scheduleReadyLocked(state: Session) {
        scheduleLocked(state, state.mouse, state.keyboard)
        scheduleLocked(state, state.keyboard, state.mouse)
    }

    private fun scheduleLocked(state: Session, lane: Lane, other: Lane) {
        if (lane.timer != null || lane.sending) return
        val delayed = lane.delayed
        val command = lane.pending.firstOrNull()
        val order = if (delayed != null) delayed.order else command?.order
        // A click must wait for earlier text, and later text must wait for the click to finish.
        if (order != null && other.firstOrdered()?.let { it < order } == true) return
        val now = scheduler.nowNanos()
        val due = delayed?.readySince ?: when (command) {
            null -> return
            is Delta -> lane.nextDeltaAt ?: now
            else -> now
        }
        val generation = lane.generation
        lane.timer = scheduler.schedule((due - now).coerceAtLeast(0)) { drain(state, lane, generation) }
    }

    private fun drain(state: Session, lane: Lane, generation: Long) {
        val plan = synchronized(lock) {
            if (session !== state || lane.generation != generation) return
            lane.timer = null
            val next = takeReportLocked(lane) ?: return
            lane.sending = true
            lane.inFlightOrder = next.order
            lane.inFlightProbe = next.probe
            next
        }
        val waitedMs = (scheduler.nowNanos() - plan.readySince).coerceAtLeast(0L) / 1_000_000f
        val ok = runCatching { state.send(lane.id, plan.report) }.getOrDefault(false)
        synchronized(lock) {
            if (session !== state || lane.generation != generation) return
            if (ok) lane.accepted = plan.report
            lane.inFlightProbe = null
            lane.inFlightOrder = null
            lane.sending = false
            val release = plan.releaseTo
            when {
                !ok && plan.keyboardInput -> recoverKeyboardLocked(lane, plan.order)
                release != null -> {
                    val due = scheduler.nowNanos() + TimeUnit.MILLISECONDS.toNanos(plan.holdMs.coerceAtLeast(0L))
                    lane.delayed = Report(release, due, retry = true, order = plan.order, keyboardInput = plan.keyboardInput)
                }
                !ok && plan.retry -> {
                    lane.delayed = Report(plan.report, scheduler.nowNanos() + RETRY_DELAY_NS, order = plan.order)
                }
            }
            scheduleReadyLocked(state)
        }
        runCatching { onSendResult(state.id, waitedMs, ok) }
        plan.probe?.let { complete -> runCatching { complete(ok) } }
    }

    private fun recoverKeyboardLocked(lane: Lane, failedOrder: Long?) {
        keyboardVersion++
        lane.pending.clear()
        val neutral = Descriptors.keyboardReport(0)
        lane.base = neutral
        lane.accepted = neutral
        lane.delayed = Report(neutral, scheduler.nowNanos(), retry = true, order = failedOrder)
    }

    private fun takeReportLocked(lane: Lane): Report? {
        lane.delayed?.let {
            lane.delayed = null
            return it
        }
        val next = lane.pending.firstOrNull() ?: return null
        if (next !is Delta) lane.pending.removeFirst()
        return when (next) {
            is SetState -> {
                lane.base = next.report
                Report(
                    next.report, next.enqueuedAt, retry = next.retry, order = next.order,
                    keyboardInput = lane.id == KEYBOARD && next.order != null,
                )
            }
            is Tap -> Report(
                tapReport(lane, next), next.enqueuedAt, releaseTo = lane.base, holdMs = next.holdMs, order = next.order,
                keyboardInput = lane.id == KEYBOARD,
            )
            is Probe -> Report(mouse(lane.accepted[0].toInt()), next.enqueuedAt, probe = next.complete)
            is Delta -> {
                val dx = next.x.coerceIn(-HID_LIMIT, HID_LIMIT)
                val dy = next.y.coerceIn(-HID_LIMIT, HID_LIMIT)
                val wheel = next.wheel.coerceIn(-HID_LIMIT, HID_LIMIT)
                next.x -= dx
                next.y -= dy
                next.wheel -= wheel
                if (next.isEmpty()) lane.pending.removeFirst()
                lane.nextDeltaAt = scheduler.nowNanos() + DELTA_PERIOD_NS
                Report(mouse(lane.base[0].toInt(), dx, dy, wheel), next.enqueuedAt)
            }
        }
    }

    private fun tapReport(lane: Lane, tap: Tap): ByteArray {
        if (lane.id == MOUSE) return mouse(lane.base[0].toInt() or tap.value)
        val mods = lane.base[0].toInt() or tap.mods
        val held = lane.base[2].toInt() and 0xFF
        return if (held == 0 || held == tap.value) {
            Descriptors.keyboardReport(mods, tap.value)
        } else {
            Descriptors.keyboardReport(mods, held, tap.value)
        }
    }

    private fun rejectProbes(callbacks: List<(Boolean) -> Unit>) {
        for (complete in callbacks) runCatching { complete(false) }
    }

    private class Session(val id: Long, val send: (Int, ByteArray) -> Boolean) {
        val mouse = Lane(MOUSE, mouse(0))
        val keyboard = Lane(KEYBOARD, Descriptors.keyboardReport(0))
        var desiredButtons = 0
        var nextOrder = 0L
    }

    private class Lane(val id: Int, neutral: ByteArray) {
        val pending = ArrayDeque<Command>()
        var base = neutral
        var accepted = neutral
        var timer: ScheduledHidTask? = null
        var sending = false
        var generation = 0L
        var delayed: Report? = null
        var nextDeltaAt: Long? = null
        var inFlightProbe: ((Boolean) -> Unit)? = null
        var inFlightOrder: Long? = null

        fun firstOrdered(): Long? = inFlightOrder ?: delayed?.order ?: pending.firstNotNullOfOrNull(Command::order)
    }

    private sealed interface Command {
        val enqueuedAt: Long
        val order: Long? get() = null
    }

    private class SetState(
        val report: ByteArray,
        val retry: Boolean,
        override val enqueuedAt: Long,
        override val order: Long?,
    ) : Command

    private class Tap(
        val value: Int,
        val mods: Int,
        val holdMs: Long,
        override val enqueuedAt: Long,
        override val order: Long,
    ) : Command
    private class Probe(val complete: (Boolean) -> Unit, override val enqueuedAt: Long) : Command
    private class Delta(
        var x: Int,
        var y: Int,
        var wheel: Int,
        val scroll: Boolean,
        override val enqueuedAt: Long,
    ) : Command {
        fun isEmpty(): Boolean = x == 0 && y == 0 && wheel == 0
    }

    private class Report(
        val report: ByteArray,
        val readySince: Long,
        val releaseTo: ByteArray? = null,
        val holdMs: Long = 0,
        val retry: Boolean = false,
        val probe: ((Boolean) -> Unit)? = null,
        val order: Long? = null,
        val keyboardInput: Boolean = false,
    )

    private companion object {
        const val MOUSE = Descriptors.MOUSE_REPORT_ID.toInt()
        const val KEYBOARD = Descriptors.KEYBOARD_REPORT_ID.toInt()
        const val BUTTON_MASK = 0x1F
        const val MOUSE_LIMIT = 32
        const val HID_LIMIT = 127
        const val MAX_DELTA = HID_LIMIT * 32L
        const val DELTA_PERIOD_NS = 8_000_000L
        const val RETRY_DELAY_NS = 10_000_000L

        fun boundedDelta(current: Int, added: Int): Int =
            (current.toLong() + added).coerceIn(-MAX_DELTA, MAX_DELTA).toInt()

        fun mouse(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0): ByteArray =
            Descriptors.mouseReport(buttons, dx, dy, wheel)
    }
}
