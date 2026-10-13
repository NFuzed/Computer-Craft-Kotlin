package app.route

import app.support.fleetTest
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import protocol.Protocol
import protocol.api.TurtleStatus
import protocol.messages.BlockObservations
import protocol.messages.ErrorMessage
import protocol.messages.ObservedBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurtleSocketRoutesTest {

    @Test
    fun `a connection that doesn't start with hello is closed`() = fleetTest {
        client.webSocket(Protocol.Paths.WS_TURTLE) {
            send(Frame.Text("""{"v":1,"type":"label_update","turtleId":7}"""))

            for (frame in incoming) Unit // wait for the server to close it
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, closeReason.await()?.knownReason)
        }
    }

    @Test
    fun `a turtle is online while connected`() = fleetTest {
        turtle(7, label = "Miner") {
            sync()
            val status = client.get("/api/turtles/7").body<TurtleStatus>()
            assertEquals(true, status.online)
            assertEquals("Miner", status.label)
        }
    }

    @Test
    fun `a message claiming to be from another turtle is refused`() = fleetTest {
        turtle(7) {
            send(BlockObservations(turtleId = 8, blocks = emptyList()))

            val error = receive<ErrorMessage>(Protocol.MessageTypes.ERROR)
            assertEquals("turtleId 8 does not match connection (7)", error.error)
        }
    }

    @Test
    fun `too many observed blocks in one message are refused`() = fleetTest {
        turtle(7) {
            send(BlockObservations(turtleId = 7, blocks = (1..65).map { ObservedBlock(it, 0, 0, "minecraft:stone") }))

            val error = receive<ErrorMessage>(Protocol.MessageTypes.ERROR)
            assertTrue("At most 64" in error.error, error.error)
        }
    }
}
