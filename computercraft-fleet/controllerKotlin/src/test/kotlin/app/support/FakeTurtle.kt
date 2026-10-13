package app.support

import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import protocol.Protocol
import protocol.ProtocolJson
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val RECEIVE_TIMEOUT_MS = 2_000L

/**
 * Plays a turtle over /ws/turtle, the way main.lua and net.lua do: it sends protocol messages and reads what the
 * server pushes. Heartbeats are skipped, as net.lua does.
 */
class FakeTurtle(val turtleId: Int, val session: DefaultClientWebSocketSession) {

    suspend inline fun <reified T> send(message: T) = sendRaw(ProtocolJson.encodeToString(message))

    suspend fun sendRaw(text: String) = session.send(Frame.Text(text))

    /** The next message from the server that isn't a heartbeat. Fails if none arrives within 2 s. */
    suspend fun receiveJson(): JsonObject = withTimeout(RECEIVE_TIMEOUT_MS) {
        var json: JsonObject? = null
        while (json == null) {
            val frame = session.incoming.receive() as? Frame.Text ?: continue
            json = ProtocolJson.parseToJsonElement(frame.readText()).jsonObject
                .takeIf { it.type != Protocol.MessageTypes.HEARTBEAT }
        }
        json
    }

    /** The next message from the server, which must be of [type], decoded as [T]. */
    suspend inline fun <reified T> receive(type: String): T {
        val json = receiveJson()
        assertEquals(type, json.type, "message type, in $json")
        return ProtocolJson.decodeFromJsonElement(json)
    }

    /** Fails if the server sends anything other than a heartbeat within [ms]. */
    suspend fun expectNothing(ms: Long = 300) {
        val message = withTimeoutOrNull(ms) { receiveJson() }
        assertNull(message, "expected no message from the server")
    }

    /**
     * Waits until the server has handled everything sent so far. The server handles a connection's messages in order
     * and answers an unknown type with an error, so once that error comes back, everything before it is done.
     */
    suspend fun sync() {
        sendRaw("""{"v":${Protocol.VERSION},"type":"test_sync","turtleId":$turtleId}""")
        val reply = receiveJson()
        if (reply.type != Protocol.MessageTypes.ERROR || "test_sync" !in reply.toString()) {
            fail("expected the error for test_sync, got $reply")
        }
    }
}

val JsonObject.type: String? get() = this["type"]?.jsonPrimitive?.contentOrNull
