package app.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol

fun Route.manifestRoutes() {
    get(Protocol.Paths.MANIFEST) {
        val stream = this::class.java.classLoader.getResourceAsStream("manifest.json")
            ?: return@get call.respond(HttpStatusCode.NotFound)

        call.respondText(stream.bufferedReader().readText(), ContentType.Application.Json)
    }
}
