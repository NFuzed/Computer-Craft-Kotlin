package app.service

import app.path.*
import app.store.*
import io.ktor.websocket.*
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
 * - [maxMakeWayDepth]: how long a chain of turtles making way for each other may be (a turtle making way for one that
 *   makes way for the trip is 2)
 * - [maxMakeWayPlans]: the most paths planned looking for ways out of the way, for one plan of the trip
 */
data class TripConfig(
    val surfaceGuess: Int = 62,
    val margin: Int = 24,
    val verticalMargin: Int = 16,
    val maxReplans: Int = 20,
    val exploreUntilBlocked: Int = 3,
    val retryDelayMs: Long = 1_000,
    val maxDistance: Int = 512,
    val maxMakeWayDepth: Int = 4,
    val maxMakeWayPlans: Int = 32,
    val minY: Int = -64,    // the overworld's build limits
    val maxY: Int = 319
)

/**
 * Sends turtles places. A trip plans a path with [PathPlanner] over what the world map knows, sends it to the turtle,
 * and when the turtle reports it was blocked (by then the server has the blocks it saw), plans a new path from where
 * it stopped. Station chests are never entered; the turtle digs only if it has a tool, and never in a no-dig zone.
 *
 * Other turtles are never bumped into. One that can make way (connected, with fuel, and not on a trip of its own) may
 * be planned through, for [PathCosts.nudge] extra: then the trip waits while that turtle goes to the nearest cell off
 * the path (on a trip of its own, with [Trip.makingWayFor] set), and plans again once it has. The rest are impassable.
 * Its way off may in turn go through turtles that can make way, which then go first: depth first, each trip waiting
 * for the ones making way for it (see [prepare]).
 *
 * A trip belongs to the connection its path was sent on: when that one closes the trip fails, even if the turtle has
 * already connected again (main.lua stops a path when its connection drops, and doesn't report it).
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
    private val zones: ZoneStore,
    private val config: TripConfig = TripConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    // planning is CPU-bound and can take up to a second, so it runs off the threads serving requests and messages
    private val planningDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /**
     * A trip and what the server needs to replan it. [session] is the connection its path was sent on (while waiting,
     * the one it will be); [blocked] counts the times the turtle was stopped. A trip making way for another turtle has
     * [avoid], that turtle's path: it goes to the nearest cell off it, wherever that is, rather than to [goal].
     */
    private data class ActiveTrip(
        val trip: Trip,
        val session: WebSocketSession,
        val goal: BlockPos,
        val goalHeading: Heading?,
        val steps: List<PathStep>,
        val blocked: Int = 0,
        val avoid: Set<BlockPos>? = null
    )

    /** What planning a trip came to. */
    private sealed interface Prepared

    /** A path, to send once the turtles in [makeWay] have moved off it. */
    private data class Ready(val path: Path, val makeWay: List<MakeWay>) : Prepared

    private data class NoWay(val error: String) : Prepared

    /**
     * A turtle in the way, and its way off [avoid]: the paths of the turtles it makes way for. [ready] may need others
     * to make way for it first.
     */
    private data class MakeWay(val turtleId: Int, val start: Pose, val ready: Ready, val avoid: Set<BlockPos>)

    /**
     * One search for ways out of a trip's way. [chain]: the turtles already moving, or planned to: the trip's own and
     * those making way, which are never asked to move (again). [taken]: where those making way go, so no two head for
     * the same cell. [plansLeft] bounds the search.
     */
    private class MakeWaySearch(turtleId: Int, var plansLeft: Int) {
        val chain = hashSetOf(turtleId)
        val taken = HashSet<BlockPos>()
    }

    /** A plan, and the turtles it was allowed to plan through, by cell. */
    private data class Planned(val result: PathResult, val movable: Map<BlockPos, Int>)

    /**
     * What a plan is for: from [start] to [goal], ending facing [heading] if given; or, with [avoid], to the nearest
     * cell off it, for a turtle making way (then [goal] only sizes the search, and is [start]'s cell).
     */
    private data class Route(val start: Pose, val goal: BlockPos, val heading: Heading?, val avoid: Set<BlockPos>?)

    // Trips change as API calls and turtle messages arrive, so one lock keeps each change whole
    private val mutex = Mutex()
    private val trips = ConcurrentHashMap<Int, ActiveTrip>()

    // Start at the current time in ms so ids stay unique across server restarts
    private val nextTripId = AtomicLong(System.currentTimeMillis())
    private val nextPathId = AtomicLong(System.currentTimeMillis())

    /** The turtle's current trip, or its last one. */
    fun trip(turtleId: Int): Trip? = trips[turtleId]?.trip

    /** Plans a path to the goal and sends it, or waits for turtles in the way; a trip already in progress is replaced. */
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
        val ready = when (val prepared = prepare(turtleId, Route(start, goal, heading, avoid = null), explore = true)) {
            is Ready -> prepared
            is NoWay -> return GotoOutcome.Impossible(prepared.error)
        }
        val session = sessions.session(turtleId) ?: return GotoOutcome.NotReady("Turtle $turtleId is not connected")

        val now = clock()
        val trip = Trip(
            tripId = nextTripId.incrementAndGet(), x = goal.x, y = goal.y, z = goal.z, heading = heading?.name,
            status = TripStatuses.MOVING, pathId = 0, steps = 0, fuel = 0, replans = 0, started = now, updated = now
        )
        // the new trip replaces the one in progress. If that one was making way, the trip waiting for it is only let go
        // once the new one is stored, so it can't ask this turtle to move again meanwhile
        val old = trips[turtleId]?.takeIf { it.trip.status in TripStatuses.IN_PROGRESS }
        val replaced = old?.let { end(turtleId, it, TripStatuses.CANCELLED, "Replaced by trip ${trip.tripId}", false) }
        // a new path stops the old one by itself, but waiting sends none
        if (old?.trip?.status == TripStatuses.MOVING && ready.makeWay.isNotEmpty()) cancelPath(
            turtleId,
            old.trip.pathId
        )

        val started = launch(turtleId, ActiveTrip(trip, session, goal, heading, emptyList()), start, ready)
        if (replaced != null) release(turtleId, replaced)
        if (started == null) return GotoOutcome.NotReady("Turtle $turtleId is not connected")
        GotoOutcome.Started(started.trip)
    }

    /** Stops the turtle's trip, after its current step if it's moving. Returns null if it has none in progress. */
    suspend fun cancel(turtleId: Int): Trip? = mutex.withLock {
        val active = trips[turtleId]?.takeIf { it.trip.status in TripStatuses.IN_PROGRESS } ?: return null
        if (active.trip.status == TripStatuses.MOVING) cancelPath(turtleId, active.trip.pathId)
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

    /**
     * The turtle's connection [session] closed. A trip sent (or waiting to be sent) on it fails, even if the turtle
     * has already connected again: the turtle stopped following the path when the connection dropped.
     */
    suspend fun disconnected(turtleId: Int, session: WebSocketSession) = mutex.withLock {
        val active = trips[turtleId]?.takeIf { it.trip.status in TripStatuses.IN_PROGRESS } ?: return@withLock
        if (active.session === session) end(turtleId, active, TripStatuses.FAILED, "The turtle disconnected")
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
        val route = Route(start, active.goal, active.goalHeading, active.avoid)
        val ready = when (val prepared = prepare(turtleId, route, explore)) {
            is Ready -> prepared
            is NoWay -> {
                end(turtleId, active, TripStatuses.FAILED, prepared.error)
                return
            }
        }
        val replanned = active.copy(trip = trip.copy(replans = trip.replans + 1))
        if (launch(turtleId, replanned, start, ready) == null) {
            end(turtleId, replanned, TripStatuses.FAILED, "The turtle disconnected")
        }
    }

    /**
     * Plans the trip's [route]. Turtles in the way that can make way may be planned through, each with its own way off
     * this path, found the same way: so a turtle that can only get out once another has moves that one first, depth
     * first, up to [TripConfig.maxMakeWayDepth] deep. One with no way off is planned round instead, undoing what was
     * planned for it: that's the backtracking.
     */
    private suspend fun prepare(
        turtleId: Int, route: Route, explore: Boolean,
        search: MakeWaySearch = MakeWaySearch(turtleId, config.maxMakeWayPlans), depth: Int = 0
    ): Prepared {
        val stuck = HashSet<BlockPos>() // turtles that can't make way after all
        while (true) {
            val nudge = depth < config.maxMakeWayDepth && search.plansLeft-- > 0
            val (result, movable) =
                plan(turtleId, route, explore, nudge, keepOut = stuck + search.taken, exclude = search.chain)
            val path = (result as? PathResult.Found)?.path
                ?: return NoWay(noPath(result, route.start.pos, if (route.avoid == null) route.goal else null))
            fuelShortage(turtleId, path)?.let { return NoWay(it) }

            // nearest first: one further along may have to move first, for it, which its own way off finds
            val order = path.steps.withIndex().associate { (i, step) -> step.to.pos to i }
            val inTheWay = movable.filterKeys { it in order }.entries.sortedBy { order[it.key] }
            val off = route.avoid.orEmpty() + order.keys // a turtle making way for this one must stay off its path too
            val chain = HashSet(search.chain)
            val taken = HashSet(search.taken)
            val makeWay = ArrayList<MakeWay>()
            var blocked: BlockPos? = null
            for ((cell, other) in inTheWay) {
                if (other in search.chain) continue // already making way for one nearer, which keeps it off this path
                val way = wayOff(other, off, explore, search, depth + 1)
                if (way == null) {
                    blocked = cell
                    break
                }
                makeWay += way
            }
            if (blocked == null) return Ready(path, makeWay)
            // undo what was planned for the others, and plan again round the one that's stuck; that ends, as every
            // pass adds one
            search.chain.retainAll(chain)
            search.taken.retainAll(taken)
            stuck += blocked
        }
    }

    /** Turtle [turtleId]'s way off [avoid], which others may make way for in turn; or null if it has none. */
    private suspend fun wayOff(
        turtleId: Int, avoid: Set<BlockPos>, explore: Boolean, search: MakeWaySearch, depth: Int
    ): MakeWay? {
        val start = poseOf(turtleId) ?: return null
        search.chain += turtleId
        val ready = prepare(turtleId, Route(start, start.pos, null, avoid), explore, search, depth) as? Ready
        if (ready == null) {
            search.chain -= turtleId
            return null
        }
        search.taken += ready.path.steps.lastOrNull()?.to?.pos ?: start.pos
        return MakeWay(turtleId, start, ready, avoid)
    }

    /**
     * Sends [ready]'s path for [active]; or if turtles are in the way, sends them off it and waits. Returns the trip as
     * stored, or null if the turtle isn't connected.
     */
    private suspend fun launch(turtleId: Int, active: ActiveTrip, start: Pose, ready: Ready): ActiveTrip? {
        val path = ready.path
        // a turtle making way goes wherever its path off the other's ends
        val goal = if (active.avoid != null) path.steps.lastOrNull()?.to?.pos ?: start.pos else active.goal
        if (ready.makeWay.isNotEmpty()) {
            val session = sessions.session(turtleId) ?: return null
            val waitingFor = ready.makeWay.filter { makeWay(it, turtleId) }.map { it.turtleId }
            if (waitingFor.isEmpty()) {
                // none of them could be sent off: they've gone offline, so planning again goes round them
                trips[turtleId] = active
                replan(turtleId, active, "The turtles in the way disconnected")
                return trips[turtleId]
            }
            val waiting = active.trip.copy(
                x = goal.x, y = goal.y, z = goal.z, status = TripStatuses.WAITING, steps = path.steps.size,
                fuel = path.fuel, waitingFor = waitingFor, updated = clock()
            )
            return active.copy(trip = waiting, session = session, goal = goal, steps = emptyList())
                .also { trips[turtleId] = it }
        }

        val pathId = nextPathId.incrementAndGet()
        val moving = active.trip.copy(
            x = goal.x, y = goal.y, z = goal.z, status = TripStatuses.MOVING, pathId = pathId, steps = path.steps.size,
            fuel = path.fuel, waitingFor = emptyList(), updated = clock()
        )
        // the turtle may have connected again since the last path, in which case this one goes out on the new connection
        val session = send(turtleId, pathId, start, path) ?: return null
        return active.copy(trip = moving, session = session, goal = goal, steps = path.steps)
            .also { trips[turtleId] = it }
    }

    /** Starts a trip moving [way]'s turtle out of [forTurtle]'s way. Returns false if it couldn't be sent. */
    private suspend fun makeWay(way: MakeWay, forTurtle: Int): Boolean {
        val now = clock()
        val trip = Trip(
            tripId = nextTripId.incrementAndGet(), x = way.start.pos.x, y = way.start.pos.y, z = way.start.pos.z,
            status = TripStatuses.MOVING, pathId = 0, steps = 0, fuel = 0, replans = 0, makingWayFor = forTurtle,
            started = now, updated = now
        )
        val session = sessions.session(way.turtleId) ?: return false
        val active = ActiveTrip(trip, session, way.start.pos, null, emptyList(), avoid = way.avoid)
        // it waits in turn for any making way for it
        return launch(way.turtleId, active, way.start, way.ready) != null
    }

    /**
     * Turtle [turtleId]'s trip [ended]. If it was making way, the trip waiting for it plans again once nothing else is
     * in its way: by then the turtle is out of the way, or, if it didn't get there, is planned round or asked again.
     */
    private suspend fun release(turtleId: Int, ended: Trip) {
        val forTurtle = ended.makingWayFor ?: return
        val waiting = trips[forTurtle]?.takeIf { it.trip.status == TripStatuses.WAITING } ?: return
        if (turtleId !in waiting.trip.waitingFor) return
        val rest = waiting.trip.waitingFor - turtleId
        val updated = waiting.copy(trip = waiting.trip.copy(waitingFor = rest, updated = clock()))
        trips[forTurtle] = updated
        if (rest.isEmpty()) replan(forTurtle, updated, null)
    }

    /**
     * Plans the [route] over what the world map knows around its start and goal. When [explore]ing, cells nobody has
     * seen count as open; otherwise they're guessed. With [nudge], other turtles that can make way may be planned
     * through, except those in [exclude]. [keepOut] is never entered.
     */
    private suspend fun plan(
        turtleId: Int, route: Route, explore: Boolean, nudge: Boolean, keepOut: Set<BlockPos>, exclude: Set<Int>
    ): Planned {
        val (start, goal, heading, avoid) = route
        val position = positions.get(turtleId) ?: return Planned(PathResult.Unreachable, emptyMap())
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
        val others = otherTurtles(turtleId, mapId)
        val movable = if (!nudge) emptyMap() else others.filter { (cell, id) ->
            cell !in keepOut && id !in exclude && canMakeWay(id)
        }
        val rules = PathRules(
            canDig = equipments.get(turtleId)?.capabilities.orEmpty().contains(Capabilities.DIG),
            impassable = (others.keys - movable.keys) + stationChests(mapId) + keepOut,
            movable = movable.keys,
            noDigZones = noDigZones(mapId),
            bounds = bounds
        )
        val world = KnownWorld(known, config.surfaceGuess, assumeUnseenOpen = explore)
        val result = withContext(planningDispatcher) {
            val planner = PathPlanner(world)
            if (avoid != null) planner.planAway(start, avoid, rules) else planner.plan(start, goal, heading, rules)
        }
        return Planned(result, movable)
    }

    /** Where every other turtle in the same map is, and which turtle: a turtle can't move into another. */
    private fun otherTurtles(turtleId: Int, mapId: MapId): Map<BlockPos, Int> =
        positions.ids().filter { it != turtleId }
            .mapNotNull { id -> positions.get(id)?.let { id to it } }
            .filter { (_, it) -> MapId(it.world, it.dimension) == mapId }
            .associate { (id, it) -> BlockPos(it.x, it.y, it.z) to id }

    /** Whether the turtle can be asked to move out of the way: it's connected, has fuel, and isn't on a trip. */
    private fun canMakeWay(turtleId: Int): Boolean {
        if (!sessions.isConnected(turtleId)) return false
        if (trips[turtleId]?.trip?.status in TripStatuses.IN_PROGRESS) return false
        val fuel = inventories.get(turtleId)?.fuel ?: return false
        return fuel.level.let { it == null || it > 0 } // null: unlimited
    }

    private fun stationChests(mapId: MapId): Set<BlockPos> =
        stations.all().filter { it.mapId == mapId }.map { it.chestCell }.toSet()

    /** The no-dig zones in the same map, read for every plan so a zone changed mid-trip counts from the next replan. */
    private fun noDigZones(mapId: MapId): List<Box> =
        zones.all().filter { it.type == ZoneTypes.NO_DIG && it.mapId == mapId }.map { it.box }

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

    /** Ends the trip and, unless told not to [release] yet, lets go of a trip it was making way for. */
    private suspend fun end(
        turtleId: Int, active: ActiveTrip, status: String, error: String? = null, release: Boolean = true
    ): Trip {
        val ended = active.trip.copy(status = status, error = error, waitingFor = emptyList(), updated = clock())
        trips[turtleId] = active.copy(trip = ended)
        if (release) release(turtleId, ended)
        return ended
    }

    private suspend fun cancelPath(turtleId: Int, pathId: Long) {
        sessions.send(turtleId, ProtocolJson.encodeToString(PathCancelMessage(pathId = pathId)))
    }

    /** Sends the path. Returns the connection it went out on, or null if the turtle isn't connected or it failed. */
    private suspend fun send(turtleId: Int, pathId: Long, start: Pose, path: Path): WebSocketSession? {
        val steps = path.steps.map { PathStepMessage(action = ACTIONS.getValue(it.action), dig = it.dig) }
        val from = PathStart(start.pos.x, start.pos.y, start.pos.z, start.heading.name)
        val message = PathMessage(pathId = pathId, start = from, steps = steps)
        val session = sessions.session(turtleId) ?: return null
        return session.takeIf { runCatching { it.send(Frame.Text(ProtocolJson.encodeToString(message))) }.isSuccess }
    }

    /** Why there's no path from [from] to [to], or out of another turtle's way when [to] is null. */
    private fun noPath(result: PathResult, from: BlockPos, to: BlockPos?): String {
        val route =
            "from ${from.x} ${from.y} ${from.z} " + (to?.let { "to ${it.x} ${it.y} ${it.z}" } ?: "out of the way")
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
