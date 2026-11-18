package dev.pocketinput.hid

import java.util.concurrent.TimeUnit

internal const val KEYBOARD_QUEUE_CAPACITY = 128

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
            MOUSE -> mouseReport(session?.mouse?.accepted?.get(0)?.toInt() ?: 0)
            KEYBOARD -> session?.keyboard?.accepted?.copyOf() ?: Descriptors.keyboardReport(0)
            HORIZONTAL_SCROLL -> Descriptors.horizontalScrollReport(0)
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
            val next = Session(current.id, current.send)
            if (current.inputPaused) {
                next.inputPaused = true
                next.keyboard.delayed = keyboardNeutralRecovery()
            }
            replaceSessionLocked(next)
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
            if (current.inputPaused || mask == 0 || current.mouse.pending.size >= MOUSE_LIMIT) return
            current.mouse.pending.addLast(HidTap(mask, 0, holdMs, scheduler.nowNanos(), current.nextOrder++))
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
            cancelMouseLocked(current, keepMask and BUTTON_MASK)
        }
        rejectProbes(cancelled)
    }

    fun sendMotion(dx: Int, dy: Int) = enqueueDelta(dx, dy, HidDeltaKind.POINTER)

    fun wheel(units: Int) = scroll(0, units)

    fun scroll(horizontal: Int, vertical: Int) = enqueueDelta(horizontal, vertical, HidDeltaKind.SCROLL)

    fun zoom(units: Int) = enqueueDelta(0, units, HidDeltaKind.ZOOM)

    fun cancelScroll() {
        synchronized(lock) {
            val current = session ?: return
            current.scrollVersion++
            current.mouse.pending.removeAll { it is HidDelta && it.kind != HidDeltaKind.POINTER }
            scheduleReadyLocked(current)
        }
    }

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
            if (current == null || current.inputPaused || current.mouse.pending.size >= MOUSE_LIMIT) {
                false
            } else {
                current.mouse.pending.addLast(HidProbe(callback, scheduler.nowNanos()))
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
            if (current.inputPaused) {
                if (after and before != after) return
                return@synchronized cancelMouseLocked(current, after)
            }
            val lane = current.mouse
            val releasesButton = (before and after) != before || lane.pending.size >= MOUSE_LIMIT
            val pendingProbes = makeRoomForButtonsLocked(lane)
            current.desiredButtons = after
            appendStateLocked(current, lane, mouseReport(after), retry = releasesButton)
            pendingProbes
        }
        rejectProbes(cancelled)
    }

    private fun cancelMouseLocked(state: Session, keepMask: Int): List<(Boolean) -> Unit> {
        val lane = state.mouse
        val mask = if (state.inputPaused) keepMask and lane.accepted[0].toInt() else keepMask
        state.desiredButtons = mask
        val cancelled = clearLaneLocked(lane)
        appendStateLocked(state, lane, mouseReport(mask), retry = true, ordered = false)
        return cancelled
    }

    private fun makeRoomForButtonsLocked(lane: HidLane): List<(Boolean) -> Unit> {
        if (lane.pending.size < MOUSE_LIMIT) return emptyList()
        val firstDelta = lane.pending.indexOfFirst { it is HidDelta }
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
                lane.pending.addLast(HidTap(usage, modifiers, holdMs, now, current.nextOrder++))
            }
            scheduleReadyLocked(current)
            true
        }

    private fun matchingSessionLocked(expected: Long?): Session? =
        session?.takeIf { !it.inputPaused && (expected == null || it.id == expected) }

    private fun enqueueDelta(dx: Int, dy: Int, kind: HidDeltaKind) {
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            val current = session ?: return
            if (current.inputPaused) return
            val lane = current.mouse
            val previous = lane.pending.lastOrNull() as? HidDelta
            val adjacent = previous?.kind == kind &&
                (kind != HidDeltaKind.ZOOM || previous.order == current.nextOrder - 1)
            if (previous != null && adjacent) {
                previous.x = boundedDelta(previous.x, dx)
                previous.y = boundedDelta(previous.y, dy)
                if (previous.isEmpty()) lane.pending.removeLast()
            } else if (lane.pending.size < MOUSE_LIMIT) {
                lane.pending.addLast(
                    HidDelta(
                        x = boundedDelta(0, dx),
                        y = boundedDelta(0, dy),
                        kind = kind,
                        enqueuedAt = scheduler.nowNanos(),
                        order = if (kind == HidDeltaKind.ZOOM) current.nextOrder++ else null,
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
            if (!next.inputPaused) appendStateLocked(next, next.keyboard, next.keyboard.base, retry = true, ordered = false)
        }
        return cancelled
    }

    private fun clearLaneLocked(lane: HidLane): List<(Boolean) -> Unit> {
        val probes = lane.pending.filterIsInstance<HidProbe>().map { it.complete } + listOfNotNull(lane.inFlightProbe)
        lane.generation++
        lane.timer?.cancel()
        lane.timer = null
        lane.finish()
        lane.pending.clear()
        lane.delayed = null
        lane.nextDeltaAt = null
        return probes
    }

    private fun appendStateLocked(
        state: Session,
        lane: HidLane,
        report: ByteArray,
        retry: Boolean,
        ordered: Boolean = true,
    ) {
        val order = if (ordered) state.nextOrder++ else null
        lane.pending.addLast(HidSetState(report, retry, scheduler.nowNanos(), order))
        scheduleReadyLocked(state)
    }

    private fun scheduleReadyLocked(state: Session) {
        scheduleLocked(state, state.mouse, state.keyboard)
        scheduleLocked(state, state.keyboard, state.mouse)
    }

    private fun scheduleLocked(state: Session, lane: HidLane, other: HidLane) {
        if (lane.timer != null || lane.sending) return
        if (!canDispatch(state, lane, other)) return
        val command = lane.pending.firstOrNull()
        val now = scheduler.nowNanos()
        val due = lane.delayed?.readySince ?: when (command) {
            null -> return
            is HidDelta -> lane.nextDeltaAt ?: now
            else -> now
        }
        val generation = lane.generation
        lane.timer = scheduler.schedule((due - now).coerceAtLeast(0)) { drain(state, lane, generation) }
    }

    private fun canDispatch(state: Session, lane: HidLane, other: HidLane): Boolean {
        if (state.inputPaused) return lane.hasSafeRelease()
        val delayed = lane.delayed
        val order = if (delayed != null) delayed.order else lane.pending.firstOrNull()?.order
        return order == null || other.firstOrdered()?.let { it < order } != true
    }

    private fun drain(state: Session, lane: HidLane, generation: Long) {
        val scrollVersion: Long
        val plan = synchronized(lock) {
            if (session !== state || lane.generation != generation) return
            lane.timer = null
            val other = if (lane === state.mouse) state.keyboard else state.mouse
            if (!canDispatch(state, lane, other)) return
            val next = lane.takeReport(scheduler.nowNanos()) ?: return
            scrollVersion = state.scrollVersion
            lane.sending = true
            lane.inFlightOrder = next.order
            lane.inFlightProbe = next.probe
            next
        }
        val waitedMs = (scheduler.nowNanos() - plan.readySince).coerceAtLeast(0L) / 1_000_000f
        val expectedScrollVersion = when (plan.deltaKind) {
            HidDeltaKind.SCROLL, HidDeltaKind.ZOOM -> scrollVersion
            else -> null
        }
        if (!isCurrent(state, lane, generation, expectedScrollVersion)) {
            synchronized(lock) {
                if (session === state && lane.generation == generation) {
                    lane.finish()
                    scheduleReadyLocked(state)
                }
            }
            return
        }
        if (plan.deltaKind == HidDeltaKind.ZOOM) {
            drainZoom(state, lane, generation, plan, waitedMs, scrollVersion)
            return
        }
        val reportId = plan.reportId ?: lane.id
        val ok = transmit(state, reportId, plan.report)
        val horizontalOk = if (plan.horizontal != 0 && isCurrent(state, lane, generation, scrollVersion)) {
            transmit(state, HORIZONTAL_SCROLL, Descriptors.horizontalScrollReport(plan.horizontal))
        } else {
            null
        }
        val cancelled = synchronized(lock) {
            if (session !== state || lane.generation != generation) return
            if (ok && reportId == lane.id) lane.accepted = plan.report
            lane.finish()
            val release = plan.releaseTo
            var probes = emptyList<(Boolean) -> Unit>()
            when {
                ok && plan.role == HidReportRole.KEYBOARD_RECOVERY -> state.inputPaused = false
                !ok && plan.keyboardInput -> recoverKeyboardLocked(state, plan.order)
                release != null -> {
                    val due = scheduler.nowNanos() + TimeUnit.MILLISECONDS.toNanos(plan.holdMs.coerceAtLeast(0L))
                    lane.delayed = HidReport(
                        release, due, retry = true, order = plan.order, keyboardInput = plan.keyboardInput,
                    )
                }
                !ok && plan.retry -> {
                    lane.delayed = HidReport(
                        plan.report, scheduler.nowNanos() + RETRY_DELAY_NS, order = plan.order, role = plan.role,
                    )
                }
                !ok && plan.role == HidReportRole.KEYBOARD_RECOVERY -> probes = dropPausedInputLocked(state)
            }
            scheduleReadyLocked(state)
            probes
        }
        rejectProbes(cancelled)
        recordSend(state, waitedMs, ok)
        if (horizontalOk != null) recordSend(state, waitedMs, horizontalOk)
        plan.probe?.let { complete -> runCatching { complete(ok) } }
    }

    private fun drainZoom(
        state: Session, mouse: HidLane, generation: Long, plan: HidReport, waitedMs: Float, scrollVersion: Long,
    ) {
        val keyboard = state.keyboard
        val previous = synchronized(lock) {
            if (session !== state || mouse.generation != generation) return
            keyboard.base
        }
        val control = previous.copyOf().apply { this[0] = (this[0].toInt() or LEFT_CTRL).toByte() }
        val controlOk = transmit(state, KEYBOARD, control)
        var wheelOk: Boolean? = null
        var restored: Boolean? = null
        if (controlOk) {
            val sendWheel = synchronized(lock) {
                if (session !== state) return
                keyboard.accepted = control
                mouse.generation == generation && state.scrollVersion == scrollVersion
            }
            if (sendWheel) wheelOk = transmit(state, MOUSE, plan.report)
            // Mouse-only cancellation still restores the keyboard. A new session starts with neutral reports.
            if (!synchronized(lock) { session === state }) return
            restored = transmit(state, KEYBOARD, previous)
        }
        synchronized(lock) {
            if (session !== state) return
            if (restored == true) keyboard.accepted = previous
            if (restored == false) recoverKeyboardLocked(state, plan.order)
            if (mouse.generation == generation) {
                if (wheelOk == true) mouse.accepted = plan.report
                val remainder = mouse.pending.firstOrNull() as? HidDelta
                if ((!controlOk || restored == false) && remainder?.kind == HidDeltaKind.ZOOM && remainder.order == plan.order) {
                    mouse.pending.removeFirst()
                }
                mouse.finish()
            }
            scheduleReadyLocked(state)
        }
        recordSend(state, waitedMs, controlOk)
        if (wheelOk != null) recordSend(state, waitedMs, wheelOk)
        if (restored != null) recordSend(state, waitedMs, restored)
    }

    private fun isCurrent(state: Session, lane: HidLane, generation: Long, scrollVersion: Long?): Boolean =
        synchronized(lock) {
            session === state && lane.generation == generation && (scrollVersion == null || state.scrollVersion == scrollVersion)
        }

    private fun transmit(state: Session, reportId: Int, bytes: ByteArray): Boolean =
        runCatching { state.send(reportId, bytes) }.getOrDefault(false)

    private fun recordSend(state: Session, waitedMs: Float, ok: Boolean) {
        runCatching { onSendResult(state.id, waitedMs, ok) }
    }

    private fun recoverKeyboardLocked(state: Session, failedOrder: Long?) {
        keyboardVersion++
        state.inputPaused = true
        val lane = state.keyboard
        lane.generation++
        lane.timer?.cancel()
        lane.timer = null
        lane.pending.clear()
        val recovery = keyboardNeutralRecovery(failedOrder)
        lane.base = recovery.report
        lane.accepted = recovery.report
        lane.delayed = recovery
    }

    private fun keyboardNeutralRecovery(order: Long? = null) = HidReport(
        Descriptors.keyboardReport(0), scheduler.nowNanos(), retry = true, order = order,
        role = HidReportRole.KEYBOARD_RECOVERY,
    )

    private fun dropPausedInputLocked(state: Session): List<(Boolean) -> Unit> {
        val release = state.mouse.accepted[0].toInt() != 0
        state.desiredButtons = 0
        val probes = clearLaneLocked(state.mouse)
        if (release) appendStateLocked(state, state.mouse, mouseReport(0), retry = true, ordered = false)
        return probes
    }

    private fun rejectProbes(callbacks: List<(Boolean) -> Unit>) {
        for (complete in callbacks) runCatching { complete(false) }
    }

    private class Session(val id: Long, val send: (Int, ByteArray) -> Boolean) {
        val mouse = HidLane(MOUSE, mouseReport(0))
        val keyboard = HidLane(KEYBOARD, Descriptors.keyboardReport(0))
        var desiredButtons = 0
        var nextOrder = 0L
        var scrollVersion = 0L
        var inputPaused = false
    }

    private companion object {
        const val MOUSE = Descriptors.MOUSE_REPORT_ID.toInt()
        const val KEYBOARD = Descriptors.KEYBOARD_REPORT_ID.toInt()
        const val HORIZONTAL_SCROLL = Descriptors.HORIZONTAL_SCROLL_REPORT_ID.toInt()
        const val LEFT_CTRL = 0x01
        const val BUTTON_MASK = 0x1F
        const val MOUSE_LIMIT = 32
        const val MAX_DELTA = HID_DELTA_LIMIT * 32L
        const val RETRY_DELAY_NS = 10_000_000L

        fun boundedDelta(current: Int, added: Int): Int =
            (current.toLong() + added).coerceIn(-MAX_DELTA, MAX_DELTA).toInt()
    }
}
