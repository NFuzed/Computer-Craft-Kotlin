package app.route

import app.service.FleetService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.ApiError
import protocol.messages.PositionUpdate

fun Route.apiRoutes(fleet: FleetService) {
    route(Protocol.Paths.API) {
        get(Protocol.Paths.TURTLES) {
            call.respond(fleet.allStatuses())
        }

        get(Protocol.Paths.TURTLE) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("Invalid turtle id"))

            val status = fleet.status(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown turtle: $id"))

            call.respond(status)
        }

        // Lets turtles (or curl) report a position over plain HTTP until /ws/turtle exists
        post(Protocol.Paths.TURTLE_POSITION) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid turtle id"))

            val update = runCatching { call.receive<PositionUpdate>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid position body"))
            }

            if (update.turtleId != id) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("turtleId in body (${update.turtleId}) does not match path ($id)")
                )
            }

            fleet.recordPosition(update)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
