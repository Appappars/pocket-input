package dev.pocketinput.hid

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun interface ScheduledHidTask {
    fun cancel()
}

internal interface HidQueueScheduler : AutoCloseable {
    fun nowNanos(): Long

    // Runs actions asynchronously and serially, in scheduling order for equal deadlines.
    fun schedule(delayNanos: Long, action: () -> Unit): ScheduledHidTask
}

internal class HidWorker : HidQueueScheduler {
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

internal enum class HidDeltaKind { POINTER, SCROLL, ZOOM }
internal enum class HidReportRole { INPUT, RELEASE, KEYBOARD_RECOVERY }

internal sealed interface HidCommand {
    val enqueuedAt: Long
    val order: Long? get() = null
}

internal class HidSetState(
    val report: ByteArray,
    val retry: Boolean,
    override val enqueuedAt: Long,
    override val order: Long?,
) : HidCommand

internal class HidTap(
    val value: Int,
    val mods: Int,
    val holdMs: Long,
    override val enqueuedAt: Long,
    override val order: Long,
) : HidCommand

internal class HidProbe(val complete: (Boolean) -> Unit, override val enqueuedAt: Long) : HidCommand

internal class HidDelta(
    var x: Int,
    var y: Int,
    val kind: HidDeltaKind,
    override val enqueuedAt: Long,
    override val order: Long?,
) : HidCommand {
    fun isEmpty(): Boolean = x == 0 && y == 0
}

internal class HidReport(
    val report: ByteArray,
    val readySince: Long,
    val releaseTo: ByteArray? = null,
    val holdMs: Long = 0,
    val retry: Boolean = false,
    val probe: ((Boolean) -> Unit)? = null,
    val order: Long? = null,
    val keyboardInput: Boolean = false,
    val reportId: Int? = null,
    val horizontal: Int = 0,
    val deltaKind: HidDeltaKind? = null,
    val role: HidReportRole = HidReportRole.INPUT,
)

internal class HidLane(val id: Int, neutral: ByteArray) {
    val pending = ArrayDeque<HidCommand>()
    var base = neutral
    var accepted = neutral
    var timer: ScheduledHidTask? = null
    var sending = false
    var generation = 0L
    var delayed: HidReport? = null
    var nextDeltaAt: Long? = null
    var inFlightProbe: ((Boolean) -> Unit)? = null
    var inFlightOrder: Long? = null

    fun firstOrdered(): Long? = inFlightOrder ?: delayed?.order ?: pending.firstNotNullOfOrNull(HidCommand::order)

    fun hasSafeRelease(): Boolean {
        delayed?.let { return it.role != HidReportRole.INPUT }
        val next = pending.firstOrNull() as? HidSetState ?: return false
        return next.order == null
    }

    fun finish() {
        inFlightProbe = null
        inFlightOrder = null
        sending = false
    }

    fun takeReport(nowNanos: Long): HidReport? {
        delayed?.let {
            delayed = null
            return it
        }
        val next = pending.firstOrNull() ?: return null
        if (next !is HidDelta) pending.removeFirst()
        return when (next) {
            is HidSetState -> {
                base = next.report
                HidReport(
                    next.report, next.enqueuedAt, retry = next.retry, order = next.order,
                    keyboardInput = id == Descriptors.KEYBOARD_REPORT_ID.toInt() && next.order != null,
                    role = if (next.order == null) HidReportRole.RELEASE else HidReportRole.INPUT,
                )
            }
            is HidTap -> HidReport(
                tapReport(next), next.enqueuedAt, releaseTo = base, holdMs = next.holdMs, order = next.order,
                keyboardInput = id == Descriptors.KEYBOARD_REPORT_ID.toInt(),
            )
            is HidProbe -> HidReport(mouseReport(accepted[0].toInt()), next.enqueuedAt, probe = next.complete)
            is HidDelta -> takeDelta(next, nowNanos)
        }
    }

    private fun takeDelta(delta: HidDelta, nowNanos: Long): HidReport {
        val x = delta.x.coerceIn(-HID_DELTA_LIMIT, HID_DELTA_LIMIT)
        val y = delta.y.coerceIn(-HID_DELTA_LIMIT, HID_DELTA_LIMIT)
        delta.x -= x
        delta.y -= y
        if (delta.isEmpty()) pending.removeFirst()
        nextDeltaAt = nowNanos + DELTA_PERIOD_NS
        val buttons = base[0].toInt()
        return when (delta.kind) {
            HidDeltaKind.POINTER -> HidReport(mouseReport(buttons, dx = x, dy = y), delta.enqueuedAt)
            HidDeltaKind.SCROLL -> if (y == 0) {
                HidReport(
                    Descriptors.horizontalScrollReport(x), delta.enqueuedAt,
                    reportId = Descriptors.HORIZONTAL_SCROLL_REPORT_ID.toInt(),
                    deltaKind = HidDeltaKind.SCROLL,
                )
            } else {
                HidReport(
                    mouseReport(buttons, wheel = y), delta.enqueuedAt, horizontal = x, deltaKind = HidDeltaKind.SCROLL,
                )
            }
            HidDeltaKind.ZOOM -> HidReport(
                mouseReport(buttons, wheel = y), delta.enqueuedAt, order = delta.order, deltaKind = HidDeltaKind.ZOOM,
            )
        }
    }

    private fun tapReport(tap: HidTap): ByteArray {
        if (id == Descriptors.MOUSE_REPORT_ID.toInt()) return mouseReport(base[0].toInt() or tap.value)
        val mods = base[0].toInt() or tap.mods
        val held = base[2].toInt() and 0xFF
        return if (held == 0 || held == tap.value) {
            Descriptors.keyboardReport(mods, tap.value)
        } else {
            Descriptors.keyboardReport(mods, held, tap.value)
        }
    }

    private companion object {
        const val DELTA_PERIOD_NS = 8_000_000L
    }
}

internal const val HID_DELTA_LIMIT = 127

internal fun mouseReport(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0): ByteArray =
    Descriptors.mouseReport(buttons, dx, dy, wheel)
