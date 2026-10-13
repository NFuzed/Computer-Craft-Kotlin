package app.route

import app.service.FleetService
import app.service.StationService
import app.service.TripService
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRoutes(fleet: FleetService, stations: StationService, trips: TripService) {
    routing {
        manifestRoutes()
        fileRoutes()
        bootstrapRoutes()
        apiRoutes(fleet)
        stationRoutes(stations)
        gotoRoutes(trips)
        turtleSocketRoutes(fleet, trips)
    }
}
