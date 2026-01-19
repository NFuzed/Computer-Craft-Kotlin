package app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.*

@Serializable
data class Hello(
    val v: Int,
    val type: String,
    val turtleId: Int,
    val label: String? = null,
    val capabilities: List<String> = emptyList(),
    val ts: Double? = null // instead of Long?
)

@Serializable
data class Welcome(
    val v: Int = 1,
    val type: String = "welcome",
    val sessionId: String,
    val serverTime: Long,
    val heartbeatSec: Int = 5
)

fun main() {
    embeddedServer(Netty, host = "0.0.0.0", port = 8000) {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(30)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        routing {
            webSocket("/ws") {
                val json = Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
                val sessionId = UUID.randomUUID().toString()

                println("WS connected from ${call.request.local.remoteHost}")

                val first = try {
                    incoming.receive()
                } catch (e: Exception) {
                    println("Failed to receive first frame: ${e.message}")
                    return@webSocket
                }

                val text = (first as? Frame.Text)?.readText()
                println("First frame: $first")
                println("First text: $text")

                if (text == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Expected text HELLO"))
                    println("Closed: expected text")
                    return@webSocket
                }

                val hello = try {
                    json.decodeFromString<Hello>(text)
                } catch (e: Exception) {
                    println("Bad HELLO JSON: ${e.message}")
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Bad HELLO JSON"))
                    return@webSocket
                }

                println("HELLO OK: $hello")

                val welcome = Welcome(
                    sessionId = sessionId,
                    serverTime = System.currentTimeMillis() / 1000
                )

                val welcomeText = json.encodeToString(welcome)
                println("Sending WELCOME: $welcomeText")

                outgoing.send(Frame.Text(welcomeText))
                println("WELCOME sent")

                for (frame in incoming) {
                    val msg = (frame as? Frame.Text)?.readText() ?: continue
                    println("From ${hello.turtleId}: $msg")
                }
            }
        }
    }.start(wait = true)
}