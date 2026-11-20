package dev.pocketinput.hid

enum class LinkState {
    STOPPED,
    NEEDS_PERMISSION,
    BLUETOOTH_OFF,
    NO_ADAPTER,
    INIT,
    NO_PROFILE,
    REGISTERED,
    CONNECTING,
    RECOVERING,
    FAILED,
    CONNECTED,
}

enum class LinkCause {
    PROFILE_UNAVAILABLE,
    PROFILE_TIMEOUT,
    PROFILE_LOST,
    REGISTRATION_FAILED,
    REGISTRATION_LOST,
    HOST_DISCONNECTED,
    HOST_UNAVAILABLE,
    SERVICE_FAILED,
}

data class LinkEvent(val atMs: Long, val state: LinkState, val cause: LinkCause?)

data class LinkSnapshot(
    val state: LinkState = LinkState.STOPPED,
    val cause: LinkCause? = null,
    val sessionId: Long = 0,
    val keyboardRevision: Long = 0,
    val keyboardFailureRevision: Long? = null,
    val peer: String? = null,
    val attemptsRemaining: Int = 0,
    val queueP95Ms: Float = 0f,
    val sampleCount: Int = 0,
    val recentFailures: Int = 0,
    val sentOk: Long = 0,
    val sendFailed: Long = 0,
    val reportRatePerSec: Int = 0,
    val reportIntervalP50Ms: Float = 0f,
    val history: List<LinkEvent> = emptyList(),
) {
    val isConnected: Boolean get() = state == LinkState.CONNECTED
}

internal class LinkMeasurements {
    private data class Sample(val queueMs: Float, val sent: Boolean)

    private val samples = ArrayDeque<Sample>(WINDOW)
    private val sentTimes = ArrayDeque<Long>(TIMES_WINDOW)
    private var successes = 0L
    private var failures = 0L

    fun reset() {
        samples.clear()
        sentTimes.clear()
        successes = 0
        failures = 0
    }

    fun record(queueMs: Float, sent: Boolean) {
        if (samples.size == WINDOW) samples.removeFirst()
        samples.addLast(Sample(queueMs, sent))
        if (sent) {
            successes++
            if (sentTimes.size == TIMES_WINDOW) sentTimes.removeFirst()
            sentTimes.addLast(System.currentTimeMillis())
        } else failures++
    }

    fun update(snapshot: LinkSnapshot): LinkSnapshot {
        val delays = samples.map { it.queueMs }.sorted()
        val p95Index = ((delays.size * 95 + 99) / 100 - 1).coerceAtLeast(0)
        val cutoff = System.currentTimeMillis() - 1000L
        var rate = 0
        var previous: Long? = null
        val intervals = ArrayList<Float>(sentTimes.size)
        for (at in sentTimes) {
            if (at >= cutoff) rate++
            previous?.let { intervals.add((at - it).toFloat()) }
            previous = at
        }
        intervals.sort()
        val median = if (intervals.isEmpty()) 0f else intervals[intervals.size / 2]
        return snapshot.copy(
            queueP95Ms = delays.getOrNull(p95Index) ?: 0f,
            sampleCount = samples.size,
            recentFailures = samples.count { !it.sent },
            sentOk = successes,
            sendFailed = failures,
            reportRatePerSec = rate,
            reportIntervalP50Ms = median,
        )
    }

    companion object {
        const val WINDOW = 64
        const val TIMES_WINDOW = 256
    }
}

internal class LinkBackoff {
    private var next = 0
    val remaining: Int get() = delays.size - next

    fun takeDelayMs(): Long? = delays.getOrNull(next)?.also { next++ }

    fun reset() {
        next = 0
    }

    private companion object {
        val delays = longArrayOf(750, 2_000, 5_000)
    }
}
