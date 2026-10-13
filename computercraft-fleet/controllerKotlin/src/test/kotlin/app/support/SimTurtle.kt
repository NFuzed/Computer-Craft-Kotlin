package app.support

import app.path.Heading
import app.path.Pose
import app.path.minus
import app.path.plus
import app.store.BlockPos
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import protocol.Protocol
import protocol.ProtocolJson
import protocol.api.EquippedUpgrade
import protocol.api.InventorySlot
import protocol.api.TurtleFuel
import protocol.messages.*

private val UP = BlockPos(0, 1, 0)

/**
 * A turtle in a [SimWorld], connected to the server over /ws/turtle like a real one. It does what main.lua does with
 * paths: runs each step (digging first when the step says so), reports its position and the blocks in front, above
 * and below after each one, stops when a move fails, and sends a path_result. A path_cancel stops it after the
 * current step. Other messages are ignored.
 *
 * Start one with FleetTest.simTurtle, which waits until the server knows where it is.
 */
class SimTurtle(
    val turtleId: Int,
    private val world: SimWorld,
    start: Pose,
    private val canDig: Boolean,
    fuel: Int?, // null: unlimited
    private val stepDelayMs: Long
) {
    @Volatile
    var pose: Pose = start
        private set

    @Volatile
    var fuel: Int? = fuel
        private set

    /** Paths this turtle was sent, in order. */
    val pathsReceived = mutableListOf<PathMessage>()

    /** What was in the way each time a move failed, e.g. "minecraft:stone" or [TURTLE_BLOCK]. */
    val obstructions = mutableListOf<String>()

    private val ready = CompletableDeferred<Unit>()
    private lateinit var session: DefaultClientWebSocketSession
    private val pending = ArrayDeque<JsonObject>() // messages that arrived while a path was running

    init {
        world.place(turtleId, start.pos)
    }

    suspend fun awaitReady() = ready.await()

    /** Connects, reports, and handles messages until the connection closes. */
    suspend fun run(session: DefaultClientWebSocketSession) {
        this.session = session
        try {
            send(Hello(turtleId = turtleId))
            send(
                EquipmentUpdate(
                    turtleId = turtleId, itemsKnown = true,
                    left = if (canDig) EquippedUpgrade(item = "minecraft:diamond_pickaxe") else null
                )
            )
            send(
                InventoryUpdate(
                    turtleId = turtleId, selectedSlot = 1, slots = emptyList<InventorySlot>(),
                    fuel = TurtleFuel(level = fuel, limit = fuel?.let { 100_000 })
                )
            )
            reportPosition()
            reportSurroundings()
            sendRaw("""{"v":${Protocol.VERSION},"type":"test_sync","turtleId":$turtleId}""")
            while (true) {
                val message = pending.removeFirstOrNull() ?: receive() ?: break
                handle(message)
            }
        } finally {
            world.remove(turtleId)
        }
    }

    private suspend fun handle(message: JsonObject) {
        when (message.type) {
            Protocol.MessageTypes.ERROR -> if ("test_sync" in message.toString()) ready.complete(Unit)
            Protocol.MessageTypes.PATH -> follow(ProtocolJson.decodeFromJsonElement(message))
        }
    }

    /** Moves the turtle without telling the server, as if it moved before a message reached it. */
    fun moveBehindServersBack(to: Pose) {
        check(world.move(turtleId, to.pos)) { "Can't move turtle $turtleId to $to" }
        pose = to
    }

    private suspend fun follow(path: PathMessage) {
        pathsReceived += path
        // like main.lua: a path planned from somewhere else would lead somewhere else, so refuse it
        val start = path.start
        if (pose != Pose(BlockPos(start.x, start.y, start.z), Heading.valueOf(start.heading))) {
            reportPosition()
            return result(path, PathResults.BLOCKED, 0, NOT_AT_START)
        }
        for ((i, step) in path.steps.withIndex()) {
            // like main.lua, look for a cancel (or a newer path) between steps
            for (message in drainIncoming()) {
                when {
                    message.type == Protocol.MessageTypes.PATH_CANCEL &&
                            ProtocolJson.decodeFromJsonElement<PathCancelMessage>(message).pathId == path.pathId ->
                        return result(path, PathResults.CANCELLED, i)

                    message.type == Protocol.MessageTypes.PATH -> {
                        pending.addFirst(message) // run it next, instead of this one
                        return result(path, PathResults.CANCELLED, i)
                    }

                    else -> pending += message
                }
            }
            val error = run(step)
            reportPosition()
            reportSurroundings()
            if (error != null) {
                val status = if (error == OUT_OF_FUEL) PathResults.FAILED else PathResults.BLOCKED
                return result(path, status, i, error)
            }
            delay(stepDelayMs)
        }
        result(path, PathResults.DONE, path.steps.size)
    }

    /** Runs one step. Returns the turtle API's reason if it failed. */
    private fun run(step: PathStepMessage): String? {
        val here = pose
        return when (step.action) {
            PathActions.TURN_LEFT -> null.also { pose = here.copy(heading = here.heading.left) }
            PathActions.TURN_RIGHT -> null.also { pose = here.copy(heading = here.heading.right) }
            PathActions.FORWARD -> move(here.pos + here.heading.delta, step.dig)
            PathActions.UP -> move(here.pos + UP, step.dig)
            PathActions.DOWN -> move(here.pos - UP, step.dig)
            PathActions.BACK -> move(here.pos - here.heading.delta, dig = false)
            else -> "Unknown action ${step.action}"
        }
    }

    private fun move(to: BlockPos, dig: Boolean): String? {
        if (fuel == 0) return OUT_OF_FUEL
        if (dig && canDig) world.dig(to) // fails harmlessly if it's air or can't be dug, like turtle.dig
        if (!world.move(turtleId, to)) {
            obstructions += world.blockAt(to)
            return "Movement obstructed"
        }
        pose = pose.copy(pos = to)
        fuel = fuel?.minus(1)
        return null
    }

    private suspend fun result(path: PathMessage, status: String, stepsDone: Int, error: String? = null) {
        send(
            PathResultUpdate(
                turtleId = turtleId, pathId = path.pathId, status = status, stepsDone = stepsDone, error = error
            )
        )
    }

    private suspend fun reportPosition() {
        val p = pose
        send(PositionUpdate(turtleId = turtleId, x = p.pos.x, y = p.pos.y, z = p.pos.z, heading = p.heading.name))
    }

    /** turtle.inspect, inspectUp and inspectDown. */
    private suspend fun reportSurroundings() {
        val p = pose
        val cells = listOf(p.pos + p.heading.delta, p.pos + UP, p.pos - UP)
        val blocks = cells.map { ObservedBlock(it.x, it.y, it.z, world.blockAt(it)) }
        send(BlockObservations(turtleId = turtleId, blocks = blocks))
    }

    private suspend fun receive(): JsonObject? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame !is Frame.Text) continue
            val json = ProtocolJson.parseToJsonElement(frame.readText()).jsonObject
            if (json.type != Protocol.MessageTypes.HEARTBEAT) return json
        }
    }

    /** Messages that have already arrived, without waiting. */
    private fun drainIncoming(): List<JsonObject> = buildList {
        while (true) {
            val frame = session.incoming.tryReceive().getOrNull() ?: break
            if (frame !is Frame.Text) continue
            val json = ProtocolJson.parseToJsonElement(frame.readText()).jsonObject
            if (json.type != Protocol.MessageTypes.HEARTBEAT) add(json)
        }
    }

    private suspend inline fun <reified T> send(message: T) = sendRaw(ProtocolJson.encodeToString(message))
    private suspend fun sendRaw(text: String) = session.send(Frame.Text(text))

    private companion object {
        const val OUT_OF_FUEL = "Out of fuel"
        const val NOT_AT_START = "Not where the path starts"
    }
}

/** Starting pose helper: `at(1, 70, 0, Heading.E)`. */
fun at(x: Int, y: Int, z: Int, heading: Heading) = Pose(BlockPos(x, y, z), heading)
