package app.route

import app.service.FleetService
import app.service.StationService
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRoutes(fleet: FleetService, stations: StationService) {
    routing {
        manifestRoutes()
        fileRoutes()
        bootstrapRoutes()
        apiRoutes(fleet)
        stationRoutes(stations)
        turtleSocketRoutes(fleet)
    }
}
