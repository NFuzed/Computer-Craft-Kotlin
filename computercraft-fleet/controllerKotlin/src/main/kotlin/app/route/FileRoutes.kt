package app.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol

fun Route.fileRoutes() {
    get(Protocol.Paths.FILES) {
        val parts = call.parameters.getAll("path") ?: emptyList()
        val resourcePath = parts.joinToString("/")

        val stream = this::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: return@get call.respond(HttpStatusCode.NotFound, "Not found: $resourcePath")

        call.respondBytes(
            bytes = stream.readBytes(),
            contentType = ContentType.Application.OctetStream
        )
    }
}
