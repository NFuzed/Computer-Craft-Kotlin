package app.service

import app.store.PositionStore
import app.store.TurtleRegistry
import protocol.api.TurtlePosition
import protocol.api.TurtleStatus
import protocol.messages.PositionUpdate

/**
 * Combines the registry (who is connected) and position store (where they are)
 * into the [TurtleStatus] view served by the API.
 */
class FleetService(
    private val registry: TurtleRegistry,
    private val positions: PositionStore
) {
    fun allStatuses(): List<TurtleStatus> {
        val ids = registry.all().map { it.turtleId } + positions.ids()
        return ids.distinct().sorted().mapNotNull { status(it) }
    }

    fun status(turtleId: Int): TurtleStatus? {
        val info = registry.get(turtleId)
        val pos = positions.get(turtleId)
        if (info == null && pos == null) return null

        return TurtleStatus(
            turtleId = turtleId,
            label = info?.label,
            online = info?.let { registry.isOnline(it) } ?: false,
            lastSeen = info?.lastSeen,
            position = pos
        )
    }

    fun recordPosition(update: PositionUpdate) {
        registry.recordSeen(update.turtleId)
        positions.upsert(
            update.turtleId,
            TurtlePosition(
                x = update.x,
                y = update.y,
                z = update.z,
                heading = update.heading,
                world = update.world,
                dimension = update.dimension,
                ts = update.ts
            )
        )
    }
}
