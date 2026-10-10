package app.store

import java.util.concurrent.ConcurrentHashMap

data class TurtleInfo(
    val turtleId: Int,
    val label: String? = null,
    val lastSeen: Long
)

/**
 * In-memory record of every turtle the server has heard from: its label and when it last sent a message.
 * Whether a turtle is online comes from its WebSocket connection (see TurtleSessions), not from this.
 */
class TurtleRegistry(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val map = ConcurrentHashMap<Int, TurtleInfo>()

    fun recordSeen(turtleId: Int) {
        val now = clock()
        map.compute(turtleId) { _, existing ->
            existing?.copy(lastSeen = now) ?: TurtleInfo(turtleId = turtleId, lastSeen = now)
        }
    }

    /** The turtle is authoritative for its label, so a turtle that clears its label shows as unlabelled. */
    fun setLabel(turtleId: Int, label: String?) {
        map[turtleId] = TurtleInfo(turtleId = turtleId, label = label, lastSeen = clock())
    }

    fun get(turtleId: Int): TurtleInfo? = map[turtleId]

    fun all(): List<TurtleInfo> = map.values.sortedBy { it.turtleId }
}
