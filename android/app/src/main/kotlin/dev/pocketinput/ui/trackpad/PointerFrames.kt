package dev.pocketinput.ui.trackpad

/**
 * Один сэмпл касания в координатах dp.
 *
 * Панель телефона отдаёт сэмплы чаще, чем Compose успевает отрисовать кадр, поэтому
 * в сэмпл попадают и исторические точки (`PointerInputChange.historical`), и текущая.
 */
internal data class PointerSample(
    val id: Long,
    val x: Float,
    val y: Float,
    val pressed: Boolean,
    val timeMs: Long,
)

/** Кадр жеста: пальцы, известные на момент времени [timeMs]. */
internal data class PointerFrame(
    val timeMs: Long,
    val contacts: List<TrackpadContact>,
)

/**
 * Раскладывает сэмплы по времени.
 *
 * - кадры идут строго по возрастанию времени;
 * - в одном кадре несколько сэмплов одного пальца схлопываются до последнего;
 * - порядок пальцев внутри кадра стабильный — по id.
 */
internal fun buildPointerFrames(samples: List<PointerSample>): List<PointerFrame> {
    if (samples.isEmpty()) return emptyList()
    val byTime = HashMap<Long, MutableMap<Long, PointerSample>>()
    for (sample in samples) {
        val slot = byTime.getOrPut(sample.timeMs) { HashMap() }
        slot[sample.id] = sample
    }
    return byTime.entries
        .sortedBy { it.key }
        .map { (timeMs, perPointer) ->
            PointerFrame(
                timeMs = timeMs,
                contacts = perPointer.entries
                    .sortedBy { it.key }
                    .map { (id, sample) ->
                        TrackpadContact(id, TrackpadPoint(sample.x, sample.y), sample.pressed)
                    },
            )
        }
}
