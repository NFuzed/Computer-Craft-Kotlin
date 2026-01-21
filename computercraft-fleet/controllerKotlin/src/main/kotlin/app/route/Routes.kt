package app.route

import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRoutes() {
    routing {
        manifestRoutes()
        fileRoutes()
        bootstrapRoutes()
        wsRoutes()
    }
}
