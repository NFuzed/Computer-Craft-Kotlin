package app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
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
    val ts: Double? = null
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
            // manifest
            get("/manifest.json") {
                val stream = this::class.java.classLoader.getResourceAsStream("manifest.json")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondText(
                    stream.bufferedReader().readText(),
                    ContentType.Application.Json
                )
            }


            // serve any resource file by path
            get("/files/{path...}") {
                val parts = call.parameters.getAll("path") ?: emptyList()
                val resourcePath = parts.joinToString("/")

                val stream = this::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Not found: $resourcePath")

                val bytes = stream.readBytes()

                call.respondBytes(
                    bytes = bytes,
                    contentType = ContentType.Application.OctetStream
                )
            }


            // boot itself (optional: or just serve under /files/bootstrap/boot.lua)
            get("/bootstrap/boot.lua") {
                val stream = this::class.java.classLoader.getResourceAsStream("bootstrap/boot.lua")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                call.respondText(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
            }

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