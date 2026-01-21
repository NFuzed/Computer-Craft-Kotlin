package app.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol

fun Route.bootstrapRoutes() {
    get(Protocol.Paths.BOOT) {
        val stream = this::class.java.classLoader.getResourceAsStream("bootstrap/boot.lua")
            ?: return@get call.respond(HttpStatusCode.NotFound)

        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        call.respondText(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
    }
}
