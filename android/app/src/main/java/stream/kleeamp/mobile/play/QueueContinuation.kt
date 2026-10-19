package stream.kleeamp.mobile.play

import stream.kleeamp.mobile.model.Station

/**
 * A paged source the player can keep walking after the rows it already holds
 * run out. [origin] is the stable directory key persisted with the queue, so
 * the same continuation can be rebuilt after a process restart. [load] fetches
 * the page starting at [offset] and advances the offset by the page returned,
 * which keeps pagination correct even when the user edits the loaded queue.
 */
class QueueContinuation(
    val origin: String,
    startOffset: Int,
    private val fetch: suspend (offset: Int) -> List<Station>,
) {
    var offset: Int = startOffset.coerceAtLeast(0)
        private set

    suspend fun load(): List<Station> {
        val page = fetch(offset)
        if (page.isNotEmpty()) offset += page.size
        return page
    }
}
