package app.route

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import protocol.Protocol
import protocol.decode
import protocol.encode
import protocol.messages.Hello
import protocol.messages.Welcome
import java.util.*

fun Route.wsRoutes() {
    webSocket(Protocol.Paths.WS) {
        val sessionId = UUID.randomUUID().toString()

        println("WS connected from ${call.request.local.remoteHost}")

        val first = try {
            incoming.receive()
        } catch (e: Exception) {
            println("Failed to receive first frame: ${e.message}")
            return@webSocket
        }

        val text = (first as? Frame.Text)?.readText()
        if (text == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected text HELLO"))
            return@webSocket
        }

        val hello = try {
            decode<Hello>(text)
        } catch (e: Exception) {
            println("Bad HELLO JSON: ${e.message}")
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Bad HELLO JSON"))
            return@webSocket
        }

        val welcome = Welcome(
            sessionId = sessionId,
            serverTime = System.currentTimeMillis() / 1000
        )

        outgoing.send(Frame.Text(encode(welcome)))

        for (frame in incoming) {
            val msg = (frame as? Frame.Text)?.readText() ?: continue
            println("From ${hello.turtleId}: $msg")
        }
    }
}
