package app.route

import app.service.FleetService
import app.service.StationService
import app.service.TripService
import app.service.ZoneService
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRoutes(
    fleet: FleetService, stations: StationService, zones: ZoneService, trips: TripService
) {
    routing {
        manifestRoutes()
        fileRoutes()
        bootstrapRoutes()
        apiRoutes(fleet)
        stationRoutes(stations)
        zoneRoutes(zones)
        gotoRoutes(trips)
        turtleSocketRoutes(fleet, trips)
    }
}
