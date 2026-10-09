package app.store

import java.util.concurrent.ConcurrentHashMap

data class TurtleInfo(
    val turtleId: Int,
    val label: String? = null,
    val lastSeen: Long
)

/**
 * In-memory record of every turtle the server has heard from.
 * A turtle counts as online if it has been seen within [onlineTimeoutSec].
 */
class TurtleRegistry(
    private val onlineTimeoutSec: Long = 30,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val map = ConcurrentHashMap<Int, TurtleInfo>()

    fun recordSeen(turtleId: Int, label: String? = null) {
        val now = clock()
        map.compute(turtleId) { _, existing ->
            TurtleInfo(
                turtleId = turtleId,
                label = label ?: existing?.label,
                lastSeen = now
            )
        }
    }

    fun get(turtleId: Int): TurtleInfo? = map[turtleId]

    fun all(): List<TurtleInfo> = map.values.sortedBy { it.turtleId }

    fun isOnline(info: TurtleInfo): Boolean = clock() - info.lastSeen <= onlineTimeoutSec
}
