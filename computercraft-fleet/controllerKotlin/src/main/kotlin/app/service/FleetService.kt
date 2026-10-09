package app.service

import app.store.PositionStore
import app.store.SeedStore
import app.store.TurtleRegistry
import protocol.api.PositionSeed
import protocol.api.SeedRequest
import protocol.api.TurtlePosition
import protocol.api.TurtleStatus
import protocol.messages.HeartbeatResponse
import protocol.messages.PositionUpdate
import protocol.messages.RegisterRequest
import java.util.concurrent.atomic.AtomicLong

/**
 * Combines the registry (who is connected), position store (where they are) and seed store
 * (positions waiting to be handed to turtles) into the [TurtleStatus] view served by the API.
 */
class FleetService(
    private val registry: TurtleRegistry,
    private val positions: PositionStore,
    private val seeds: SeedStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    // Starts at the current time in ms so ids stay unique across server restarts
    private val nextSeedId = AtomicLong(System.currentTimeMillis())

    fun allStatuses(): List<TurtleStatus> {
        val ids = registry.all().map { it.turtleId } + positions.ids() + seeds.ids()
        return ids.distinct().sorted().mapNotNull { status(it) }
    }

    fun status(turtleId: Int): TurtleStatus? {
        val info = registry.get(turtleId)
        val pos = positions.get(turtleId)
        val seed = seeds.get(turtleId)
        if (info == null && pos == null && seed == null) return null

        return TurtleStatus(
            turtleId = turtleId,
            label = info?.label,
            online = info?.let { registry.isOnline(it) } ?: false,
            lastSeen = info?.lastSeen,
            position = pos,
            pendingSeed = seed
        )
    }

    fun register(request: RegisterRequest): TurtleStatus {
        registry.register(request.turtleId, request.label?.takeIf { it.isNotBlank() })
        return status(request.turtleId)!!
    }

    /** Returns null if the turtle is unknown (e.g. the server restarted), so it should register again. */
    fun heartbeat(turtleId: Int): HeartbeatResponse? {
        if (!registry.touch(turtleId)) return null
        return HeartbeatResponse(seed = seeds.get(turtleId))
    }

    /**
     * Queues a position for the turtle to adopt on its next heartbeat. The turtle doesn't need to be
     * registered yet, so a seed can be set before the turtle is first turned on.
     */
    fun seed(turtleId: Int, request: SeedRequest): PositionSeed {
        val seed = PositionSeed(
            seedId = nextSeedId.incrementAndGet(),
            x = request.x,
            y = request.y,
            z = request.z,
            heading = request.heading,
            world = request.world,
            dimension = request.dimension,
            source = request.source,
            ts = clock()
        )
        seeds.put(turtleId, seed)
        return seed
    }

    fun recordPosition(update: PositionUpdate) {
        registry.recordSeen(update.turtleId)
        update.seedId?.let { seeds.acknowledge(update.turtleId, it) }
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
