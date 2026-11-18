package dev.pocketinput.hid

/**
 * Состояние канала «телефон ↔ ПК» для индикатора и экрана диагностики.
 *
 * Честная оговорка: RSSI (уровень радиосигнала) для классического Bluetooth-HID публичный
 * Android API не отдаёт — `BluetoothDevice` не имеет метода вроде `readRemoteRssi()` для
 * BR/EDR-соединения, а `BluetoothGatt` относится только к BLE. Поэтому «качество» здесь —
 * не сила сигнала, а измеряемая пригодность канала: задержка постановки отчёта в очередь,
 * доля неудачных `sendReport` и число разрывов.
 */
enum class LinkState {
    /** Транспорт создан, профиль ещё запрашивается. */
    INIT,

    /** На телефоне нет Bluetooth-адаптера. */
    NO_ADAPTER,

    /** Прошивка не отдала профиль HID_DEVICE (OEM без поддержки) — нужен агент. */
    NO_PROFILE,

    /** HID-приложение зарегистрировано, но ПК не подключён. */
    REGISTERED,

    /** Регистрация не удалась. */
    FAILED,

    /** Канал поднят: ПК подключён, отчёты уходят. */
    CONNECTED,
}

/** Уровень качества канала для индикатора. */
enum class Quality { UNKNOWN, GOOD, MEDIUM, POOR }

/**
 * Снимок состояния канала (неизменяемый, публикуется в Compose-состояние).
 *
 * @param score 0..100; −1 означает «не измерено» (нет связи или ещё нет отправок).
 */
data class LinkSnapshot(
    val state: LinkState = LinkState.INIT,
    val peer: String? = null,
    val quality: Quality = Quality.UNKNOWN,
    val score: Int = -1,
    val p50Ms: Float = 0f,
    val p95Ms: Float = 0f,
    val sentOk: Long = 0,
    val sendFailed: Long = 0,
    val disconnects: Int = 0,
    val connectedSinceMs: Long = 0,
    val lastDisconnectMs: Long = 0,
) {
    val isConnected: Boolean get() = state == LinkState.CONNECTED
    val totalSends: Long get() = sentOk + sendFailed
}

/**
 * Оценка качества канала по измеряемым признакам. Чистая функция — покрыта JVM-тестом.
 *
 * Штрафы:
 *  - задержка очереди: до 8 мс — 0, далее линейно до 45 баллов на 40 мс;
 *  - доля неудачных отправок: до 30 баллов (100 % ошибок = −30);
 *  - разрывы за сессию: по 7 баллов, не больше 20.
 */
object LinkQuality {

    const val LATENCY_FREE_MS = 8f
    const val LATENCY_BAD_MS = 40f

    fun score(p50Ms: Float, failedRatio: Float, disconnects: Int): Int {
        val latencyOver =
            ((p50Ms - LATENCY_FREE_MS) / (LATENCY_BAD_MS - LATENCY_FREE_MS)).coerceIn(0f, 1f)
        val latencyPenalty = latencyOver * 45f
        val failurePenalty = failedRatio.coerceIn(0f, 1f) * 30f
        val disconnectPenalty = (disconnects * 7).coerceAtMost(20).toFloat()
        return (100f - latencyPenalty - failurePenalty - disconnectPenalty)
            .coerceIn(0f, 100f)
            .toInt()
    }

    /** Уровень по баллам; [score] < 0 — не измерено. */
    fun level(score: Int): Quality = when {
        score < 0 -> Quality.UNKNOWN
        score >= 85 -> Quality.GOOD
        score >= 55 -> Quality.MEDIUM
        else -> Quality.POOR
    }

    /** 95-й процентиль по окну замеров (пустое окно — 0). */
    fun percentile95(samples: List<Float>): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * 0.95f).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}