package app.support

import app.module
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import protocol.Protocol
import protocol.ProtocolJson
import protocol.api.GotoRequest
import protocol.api.Trip
import protocol.api.TripStatuses
import protocol.messages.Hello
import kotlin.test.fail

/**
 * What a [fleetTest] gets: a client for the API, fake turtle connections, and simulated turtles that run in the
 * background until the test ends.
 */
class FleetTest(val client: HttpClient, private val scope: CoroutineScope) {
    private val simJobs = HashMap<Int, MutableList<Job>>()

    /**
     * Connects as turtle [turtleId], says hello, and runs [block] with the connection. The connection closes when
     * [block] returns.
     */
    suspend fun turtle(turtleId: Int, label: String? = null, block: suspend FakeTurtle.() -> Unit) {
        client.webSocket(Protocol.Paths.WS_TURTLE) {
            val turtle = FakeTurtle(turtleId, this)
            turtle.send(Hello(turtleId = turtleId, label = label))
            turtle.block()
        }
    }

    /**
     * Puts a [SimTurtle] in [world] at [start] and connects it. Returns once the server knows its position, equipment
     * (a pickaxe if [canDig]) and [fuel] (null: unlimited). [stepDelayMs] slows it down, to act while it moves.
     */
    suspend fun simTurtle(
        turtleId: Int,
        world: SimWorld,
        start: app.path.Pose,
        canDig: Boolean = false,
        fuel: Int? = 1000,
        stepDelayMs: Long = 0
    ): SimTurtle {
        val turtle = SimTurtle(turtleId, world, start, canDig, fuel, stepDelayMs)
        connect(turtle)
        return turtle
    }

    /**
     * Connects a sim turtle again without closing its connection first, as when its watchdog gives up on a connection
     * the server still thinks is open. Returns once the server has what it reported on the new connection.
     */
    suspend fun reconnect(turtle: SimTurtle) = connect(turtle)

    private suspend fun connect(turtle: SimTurtle) {
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch { client.webSocket(Protocol.Paths.WS_TURTLE) { turtle.run(this, ready) } }
        simJobs.getOrPut(turtle.turtleId) { mutableListOf() } += job
        withTimeout(5_000) { ready.await() }
    }

    /** Closes a sim turtle's connections, as if it was unloaded or crashed. */
    suspend fun disconnect(turtle: SimTurtle) {
        simJobs.remove(turtle.turtleId)?.forEach { it.cancelAndJoin() }
    }

    suspend fun goto(turtleId: Int, x: Int, y: Int, z: Int, heading: String? = null): HttpResponse =
        client.post("/api/turtles/$turtleId/goto") {
            contentType(ContentType.Application.Json)
            setBody(GotoRequest(x, y, z, heading))
        }

    suspend fun trip(turtleId: Int): Trip = client.get("/api/turtles/$turtleId/goto").body()

    /** Waits until the turtle's trip has ended (arrived, failed or cancelled) and returns it. */
    suspend fun awaitTrip(turtleId: Int, timeoutMs: Long = 10_000): Trip {
        var last: Trip? = null
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                last = trip(turtleId)
                if (last!!.status in TripStatuses.ENDED) return@withTimeoutOrNull
                delay(20)
            }
        } ?: fail("Trip didn't end within $timeoutMs ms: $last")
        return last!!
    }

    internal suspend fun stopSims() {
        simJobs.values.flatten().forEach { it.cancelAndJoin() }
        simJobs.clear()
    }
}

/**
 * Runs [block] against the whole server (routes, WebSockets, services and stores) in memory, on a fresh database.
 * Nothing listens on a real port, so tests don't clash with a server you're running.
 */
fun fleetTest(block: suspend FleetTest.() -> Unit) = testApplication {
    val db = TestPostgres.freshDatabase()
    application { module(db) } // closes db when the test application stops
    val client = createClient {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets)
    }
    coroutineScope {
        val test = FleetTest(client, this)
        try {
            test.block()
        } finally {
            test.stopSims()
        }
    }
}
