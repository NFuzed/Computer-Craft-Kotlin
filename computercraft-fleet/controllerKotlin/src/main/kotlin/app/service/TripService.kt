package app.service

import app.path.*
import app.store.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import protocol.ProtocolJson
import protocol.api.*
import protocol.messages.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What asking a turtle to go somewhere came to. */
sealed interface GotoOutcome {
    data class Started(val trip: Trip) : GotoOutcome
    data class Invalid(val error: String) : GotoOutcome        // the request itself is wrong
    data class NotReady(val error: String) : GotoOutcome       // the turtle is offline or hasn't been seeded
    data class Impossible(val error: String) : GotoOutcome     // no path, or not enough fuel
}

/**
 * Settings for planning trips.
 * - [surfaceGuess]: where the ground probably is when nothing is known; see [KnownWorld]
 * - [margin], [verticalMargin]: how far past the start and goal the search may go
 * - [maxReplans]: give up after this many new paths in one trip
 * - [exploreUntilBlocked]: plan through cells nobody has seen as if they were open until the trip has been blocked this
 *   many times, then use [KnownWorld]'s guesses. Exploring takes the shortest way through unexplored ground, which
 *   maps the route the turtle will use again; the guesses stop it probing rock all round once it's clearly in rock
 * - [retryDelayMs]: wait before replanning when the turtle was stopped by something that isn't a block (e.g. a mob)
 * - [maxDistance]: the furthest a goal may be, along x or z
 */
data class TripConfig(
    val surfaceGuess: Int = 62,
    val margin: Int = 24,
    val verticalMargin: Int = 16,
    val maxReplans: Int = 20,
    val exploreUntilBlocked: Int = 3,
    val retryDelayMs: Long = 1_000,
    val maxDistance: Int = 512,
    val minY: Int = -64,    // the overworld's build limits
    val maxY: Int = 319
)

/**
 * Sends turtles places. A trip plans a path with [PathPlanner] over what the world map knows, sends it to the turtle,
 * and when the turtle reports it was blocked (by then the server has the blocks it saw), plans a new path from where
 * it stopped. Other turtles' cells and station chests are never entered; the turtle digs only if it has a tool.
 *
 * Trips are kept in memory: only each turtle's latest, for the dashboard to follow.
 */
class TripService(
    private val sessions: TurtleSessions,
    private val positions: PositionStore,
    private val equipments: EquipmentStore,
    private val inventories: InventoryStore,
    private val worldMap: WorldMapStore,
    private val stations: StationStore,
    private val config: TripConfig = TripConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    // planning is CPU-bound and can take up to a second, so it runs off the threads serving requests and messages
    private val planningDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /** A trip and what the server needs to replan it. [blocked] counts the times the turtle was stopped. */
    private data class ActiveTrip(
        val trip: Trip,
        val goal: BlockPos,
        val goalHeading: Heading?,
        val steps: List<PathStep>,
        val blocked: Int = 0
    )

    // Trips change as API calls and turtle messages arrive, so one lock keeps each change whole
    private val mutex = Mutex()
    private val trips = ConcurrentHashMap<Int, ActiveTrip>()

    // Start at the current time in ms so ids stay unique across server restarts
    private val nextTripId = AtomicLong(System.currentTimeMillis())
    private val nextPathId = AtomicLong(System.currentTimeMillis())

    /** The turtle's current trip, or its last one. */
    fun trip(turtleId: Int): Trip? = trips[turtleId]?.trip

    /** Plans a path to the goal and sends it; a trip already in progress is replaced. */
    suspend fun goto(turtleId: Int, request: GotoRequest): GotoOutcome = mutex.withLock {
        val heading = request.heading?.let { h ->
            Heading.entries.find { it.name == h }
                ?: return GotoOutcome.Invalid("heading must be one of ${HEADINGS.joinToString()}, got '$h'")
        }
        if (!sessions.isConnected(turtleId)) return GotoOutcome.NotReady("Turtle $turtleId is not connected")
        val start = poseOf(turtleId)
            ?: return GotoOutcome.NotReady("Turtle $turtleId has no position yet; seed it first")
        val goal = BlockPos(request.x, request.y, request.z)
        if (max(abs(goal.x - start.pos.x), abs(goal.z - start.pos.z)) > config.maxDistance) {
            return GotoOutcome.Impossible("The goal is more than ${config.maxDistance} blocks away")
        }

        val path = when (val result = plan(turtleId, start, goal, heading, explore = true)) {
            is PathResult.Found -> result.path
            else -> return GotoOutcome.Impossible(noPath(result, start.pos, goal))
        }
        fuelShortage(turtleId, path)?.let { return GotoOutcome.Impossible(it) }

        val now = clock()
        val pathId = nextPathId.incrementAndGet()
        val trip = Trip(
            tripId = nextTripId.incrementAndGet(), x = goal.x, y = goal.y, z = goal.z, heading = heading?.name,
            status = TripStatuses.MOVING, pathId = pathId, steps = path.steps.size, fuel = path.fuel, replans = 0,
            started = now, updated = now
        )
        // a new path replaces the one the turtle is following, so the old trip ends here
        trips[turtleId]?.takeIf { it.trip.status == TripStatuses.MOVING }?.let { old ->
            end(turtleId, old, TripStatuses.CANCELLED, "Replaced by trip ${trip.tripId}")
        }
        if (!send(turtleId, pathId, start, path)) return GotoOutcome.NotReady("Turtle $turtleId is not connected")
        trips[turtleId] = ActiveTrip(trip, goal, heading, path.steps)
        GotoOutcome.Started(trip)
    }

    /** Stops the turtle's trip after its current step. Returns null if it has none in progress. */
    suspend fun cancel(turtleId: Int): Trip? = mutex.withLock {
        val active = trips[turtleId]?.takeIf { it.trip.status == TripStatuses.MOVING } ?: return null
        sessions.send(turtleId, ProtocolJson.encodeToString(PathCancelMessage(pathId = active.trip.pathId)))
        end(turtleId, active, TripStatuses.CANCELLED)
    }

    /** The turtle finished or stopped following a path. A blocked turtle gets a new path. */
    suspend fun pathResult(update: PathResultUpdate) {
        val turtleId = update.turtleId
        val blocked = mutex.withLock {
            val active = current(turtleId, update.pathId) ?: return
            when (update.status) {
                PathResults.DONE -> end(turtleId, active, TripStatuses.ARRIVED)
                PathResults.CANCELLED -> end(turtleId, active, TripStatuses.CANCELLED, update.error)
                PathResults.BLOCKED -> return@withLock active.copy(blocked = active.blocked + 1)
                    .also { trips[turtleId] = it }

                else -> end(turtleId, active, TripStatuses.FAILED, "The turtle stopped: ${update.error}")
            }
            null
        } ?: return

        // something that isn't a block (a mob, a player) usually moves on by itself, so give it a moment
        if (stoppedByNothing(turtleId, blocked, update.stepsDone)) delay(config.retryDelayMs)

        mutex.withLock {
            val active = current(turtleId, update.pathId) ?: return // cancelled or replaced meanwhile
            replan(turtleId, active, update.error)
        }
    }

    /** The turtle's connection closed. Its trip fails, unless it has already reconnected. */
    suspend fun disconnected(turtleId: Int) = mutex.withLock {
        val active = trips[turtleId]?.takeIf { it.trip.status == TripStatuses.MOVING } ?: return@withLock
        if (!sessions.isConnected(turtleId)) end(turtleId, active, TripStatuses.FAILED, "The turtle disconnected")
    }

    private suspend fun replan(turtleId: Int, active: ActiveTrip, error: String?) {
        val trip = active.trip
        if (trip.replans >= config.maxReplans) {
            end(turtleId, active, TripStatuses.FAILED, "Still blocked after ${trip.replans} new paths: $error")
            return
        }
        val start = poseOf(turtleId)
        if (start == null) {
            end(turtleId, active, TripStatuses.FAILED, "The turtle's position is unknown")
            return
        }
        val explore = active.blocked < config.exploreUntilBlocked
        val result = plan(turtleId, start, active.goal, active.goalHeading, explore)
        if (result !is PathResult.Found) {
            end(turtleId, active, TripStatuses.FAILED, noPath(result, start.pos, active.goal))
            return
        }
        val path = result.path
        val shortage = fuelShortage(turtleId, path)
        if (shortage != null) {
            end(turtleId, active, TripStatuses.FAILED, shortage)
            return
        }

        val pathId = nextPathId.incrementAndGet()
        val replanned = trip.copy(
            pathId = pathId, steps = path.steps.size, fuel = path.fuel, replans = trip.replans + 1, updated = clock()
        )
        trips[turtleId] = active.copy(trip = replanned, steps = path.steps)
        if (!send(turtleId, pathId, start, path)) {
            end(turtleId, trips.getValue(turtleId), TripStatuses.FAILED, "The turtle disconnected")
        }
    }

    /**
     * Plans from [start] to [goal] over what the world map knows around them. When [explore]ing, cells nobody has seen
     * count as open; otherwise they're guessed.
     */
    private suspend fun plan(
        turtleId: Int, start: Pose, goal: BlockPos, heading: Heading?, explore: Boolean
    ): PathResult {
        val position = positions.get(turtleId) ?: return PathResult.Unreachable
        val mapId = MapId(position.world, position.dimension)
        val minX = min(start.pos.x, goal.x) - config.margin
        val maxX = max(start.pos.x, goal.x) + config.margin
        val minZ = min(start.pos.z, goal.z) - config.margin
        val maxZ = max(start.pos.z, goal.z) + config.margin
        val known = worldMap.region(mapId, BlockPos(minX, config.minY, minZ), BlockPos(maxX, config.maxY, maxZ))

        // tall enough to go over the top of anything known, or up to the surface, rather than only tunnel
        val highest = known.filterNot { BlockRules.isOpen(it.name) }.maxOfOrNull { it.y } ?: config.surfaceGuess
        val top = maxOf(start.pos.y, goal.y, highest, config.surfaceGuess) + config.verticalMargin
        val bottom = min(start.pos.y, goal.y) - config.verticalMargin
        val bounds = Box(
            BlockPos(minX, max(bottom, config.minY), minZ),
            BlockPos(maxX, min(top, config.maxY), maxZ)
        )
        val rules = PathRules(
            canDig = equipments.get(turtleId)?.capabilities.orEmpty().contains(Capabilities.DIG),
            impassable = otherTurtles(turtleId, mapId) + stationChests(mapId),
            bounds = bounds
        )
        val world = KnownWorld(known, config.surfaceGuess, assumeUnseenOpen = explore)
        return withContext(planningDispatcher) { PathPlanner(world).plan(start, goal, heading, rules) }
    }

    /** Where every other turtle in the same map is: a turtle can't move into another. */
    private fun otherTurtles(turtleId: Int, mapId: MapId): Set<BlockPos> =
        positions.ids().filter { it != turtleId }.mapNotNull { positions.get(it) }
            .filter { MapId(it.world, it.dimension) == mapId }
            .map { BlockPos(it.x, it.y, it.z) }.toSet()

    private fun stationChests(mapId: MapId): Set<BlockPos> =
        stations.all().filter { it.mapId == mapId }.map { it.chestCell }.toSet()

    /** Why the turtle can't afford [path], or null if it can (or its fuel isn't known, or is unlimited). */
    private fun fuelShortage(turtleId: Int, path: Path): String? {
        val level = inventories.get(turtleId)?.fuel?.level ?: return null
        return if (path.fuel > level) "The path needs ${path.fuel} fuel but the turtle has $level" else null
    }

    /**
     * Whether the step the turtle was stopped at led into a cell the map says is open: then it wasn't a block in the
     * way (the turtle reported what it saw before its result), but something like a mob.
     */
    private fun stoppedByNothing(turtleId: Int, active: ActiveTrip, stepsDone: Int): Boolean {
        val step = active.steps.getOrNull(stepsDone) ?: return false
        val position = positions.get(turtleId) ?: return false
        val pose = poseOf(turtleId) ?: return false
        val cell = when (step.action) {
            PathAction.FORWARD -> pose.pos + pose.heading.delta
            PathAction.BACK -> pose.pos - pose.heading.delta
            PathAction.UP -> pose.pos.copy(y = pose.pos.y + 1)
            PathAction.DOWN -> pose.pos.copy(y = pose.pos.y - 1)
            else -> return false
        }
        val block = worldMap.get(MapId(position.world, position.dimension), cell) ?: return false
        return BlockRules.isOpen(block.name)
    }

    private fun poseOf(turtleId: Int): Pose? {
        val position = positions.get(turtleId) ?: return null
        val heading = Heading.entries.find { it.name == position.heading } ?: return null
        return Pose(BlockPos(position.x, position.y, position.z), heading)
    }

    /** The turtle's trip, if it's still following [pathId]; results for older paths are ignored. */
    private fun current(turtleId: Int, pathId: Long): ActiveTrip? =
        trips[turtleId]?.takeIf { it.trip.pathId == pathId && it.trip.status == TripStatuses.MOVING }

    private fun end(turtleId: Int, active: ActiveTrip, status: String, error: String? = null): Trip {
        val ended = active.trip.copy(status = status, error = error, updated = clock())
        trips[turtleId] = active.copy(trip = ended)
        return ended
    }

    private suspend fun send(turtleId: Int, pathId: Long, start: Pose, path: Path): Boolean {
        val steps = path.steps.map { PathStepMessage(action = ACTIONS.getValue(it.action), dig = it.dig) }
        val from = PathStart(start.pos.x, start.pos.y, start.pos.z, start.heading.name)
        val message = PathMessage(pathId = pathId, start = from, steps = steps)
        return sessions.send(turtleId, ProtocolJson.encodeToString(message))
    }

    private fun noPath(result: PathResult, from: BlockPos, to: BlockPos): String {
        val route = "from ${from.x} ${from.y} ${from.z} to ${to.x} ${to.y} ${to.z}"
        return when (result) {
            PathResult.TooFar -> "No path found $route before giving up; try a closer goal"
            else -> "No path $route"
        }
    }

    private companion object {
        val ACTIONS = mapOf(
            PathAction.FORWARD to PathActions.FORWARD,
            PathAction.BACK to PathActions.BACK,
            PathAction.UP to PathActions.UP,
            PathAction.DOWN to PathActions.DOWN,
            PathAction.TURN_LEFT to PathActions.TURN_LEFT,
            PathAction.TURN_RIGHT to PathActions.TURN_RIGHT
        )
    }
}
