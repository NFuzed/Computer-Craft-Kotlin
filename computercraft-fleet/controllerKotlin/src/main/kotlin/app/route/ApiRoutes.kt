package app.route

import app.service.FleetService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import protocol.Protocol
import protocol.api.*
import protocol.messages.PositionUpdate
import protocol.messages.RegisterRequest

private const val INVALID_TURTLE_ID = "Invalid turtle id"

fun Route.apiRoutes(fleet: FleetService) {
    route(Protocol.Paths.API) {
        get(Protocol.Paths.TURTLES) {
            call.respond(fleet.allStatuses())
        }

        get(Protocol.Paths.TURTLE) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val status = fleet.status(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Unknown turtle: $id"))

            call.respond(status)
        }

        // Turtles call this once after boot
        post(Protocol.Paths.TURTLE_REGISTER) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<RegisterRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid register body"))
            }

            if (request.turtleId != id) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("turtleId in body (${request.turtleId}) does not match path ($id)")
                )
            }

            call.respond(fleet.register(request))
        }

        // Keeps a registered turtle online and hands it any pending seed; 404 tells the turtle to register again
        post(Protocol.Paths.TURTLE_HEARTBEAT) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val response = fleet.heartbeat(id)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("Turtle $id is not registered"))

            call.respond(response)
        }

        // Dashboard (and later commander turtles) set where a turtle is; delivered on its next heartbeat
        post(Protocol.Paths.TURTLE_SEED) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<SeedRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("Invalid seed body"))
            }

            if (request.heading !in HEADINGS) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("heading must be one of ${HEADINGS.joinToString()}, got '${request.heading}'")
                )
            }

            call.respond(HttpStatusCode.Accepted, fleet.seed(id, request))
        }

        // Dashboard renames a turtle; the turtle sets its own label on its next heartbeat
        put(Protocol.Paths.TURTLE_LABEL) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

            val request = runCatching { call.receive<LabelRequest>() }.getOrElse {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("Invalid label body"))
            }

            val label = request.label?.trim()?.takeIf { it.isNotEmpty() }
            labelError(label)?.let {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError(it))
            }

            call.respond(HttpStatusCode.Accepted, fleet.rename(id, label))
        }

        // Lets turtles (or curl) report a position over plain HTTP until /ws/turtle exists
        post(Protocol.Paths.TURTLE_POSITION) {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError(INVALID_TURTLE_ID))

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

/** Returns why [label] can't be used as a turtle label, or null if it's fine (null clears the label). */
private fun labelError(label: String?): String? = when {
    label == null -> null
    label.length > MAX_LABEL_LENGTH -> "label must be at most $MAX_LABEL_LENGTH characters, got ${label.length}"
    // ComputerCraft drops other characters, so the turtle's label would never match and the rename would repeat
    label.any { it !in ' '..'~' } -> "label may only contain printable ASCII characters"
    else -> null
}
