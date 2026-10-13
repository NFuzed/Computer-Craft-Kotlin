package app.support

import app.module
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import protocol.Protocol
import protocol.ProtocolJson
import protocol.messages.Hello

/** What a [fleetTest] gets: a client for the API, and fake turtle connections. */
class FleetTest(val client: HttpClient) {
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
    FleetTest(client).block()
}
