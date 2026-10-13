package app.route

import app.service.GotoOutcome
import app.service.TripService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.ApiError
import protocol.api.GotoRequest

private const val INVALID_TURTLE_ID = "Invalid turtle id"

/** Sending a turtle somewhere: the server plans the route, and replans when something is in the way. */
fun Route.gotoRoutes(trips: TripService) {
    route(Protocol.Paths.API) {
        // Plans a path straight away, so an impossible trip is refused (422) instead of failing later
        post(Protocol.Paths.TURTLE_GOTO) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<GotoRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid goto body"))
            }

            when (val outcome = trips.goto(id, request)) {
                is GotoOutcome.Started -> call.respond(HttpStatusCode.Accepted, outcome.trip)
                is GotoOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ApiError(outcome.error))
                is GotoOutcome.NotReady -> call.respond(HttpStatusCode.Conflict, ApiError(outcome.error))
                is GotoOutcome.Impossible -> call.respond(HttpStatusCode.UnprocessableEntity, ApiError(outcome.error))
            }
        }

        // The turtle's current trip, or its last one since the server started
        get(Protocol.Paths.TURTLE_GOTO) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val trip = trips.trip(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Turtle $id has no trip"))

            call.respond(trip)
        }

        // Stops the turtle after its current step
        delete(Protocol.Paths.TURTLE_GOTO) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val trip = trips.cancel(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("Turtle $id has no trip in progress"))

            call.respond(HttpStatusCode.Accepted, trip)
        }
    }
}
