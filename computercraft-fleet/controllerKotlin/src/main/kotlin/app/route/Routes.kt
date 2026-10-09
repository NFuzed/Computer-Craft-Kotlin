package app.route

import app.service.FleetService
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRoutes(fleet: FleetService) {
    routing {
        manifestRoutes()
        fileRoutes()
        bootstrapRoutes()
        apiRoutes(fleet)
    }
}
